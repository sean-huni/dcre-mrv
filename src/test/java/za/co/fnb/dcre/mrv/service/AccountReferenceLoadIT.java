package za.co.fnb.dcre.mrv.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import za.co.fnb.dcre.mrv.data.model.AccountReferenceLoadEntity;
import za.co.fnb.dcre.mrv.data.repo.AccountReferenceLoadRepo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The applied path, driven against the REAL committed artifact at
 * {@code infra/dcre-infra/fixtures/reference/account/2026.08.09-001/} through the COMMITTED
 * yml defaults. Hand-built fixtures on both sides of a seam are a drift class green tests
 * cannot see, so the case that proves the loader works reads the artifact the fleet ships.
 */
class AccountReferenceLoadIT extends AbstractAccountReferenceIT {

    @Autowired
    Environment environment;
    @Autowired
    JobOperator jobOperator;
    @Autowired
    AccountReferenceLoadRepo loads;
    @Autowired
    @Qualifier("mrvAccountReferenceLoadJob")
    Job accountReferenceLoadJob;

    /**
     * The committed relative default must resolve from THIS module's directory. A relative
     * path default that silently does not resolve is how a loader ends up creating a stray
     * tree instead of failing.
     */
    @Test
    void theCommittedDefaultResolvesToTheRealArtifact() {
        final Path directory = committedProperties.datasetDirectory();

        assertThat(Files.isDirectory(directory))
                .as("committed default %s must resolve from the module directory", directory)
                .isTrue();
        assertThat(committedProperties.datasetVersion()).isEqualTo("2026.08.09-001");
        assertThat(committedProperties.maxAge())
                .as("max-age stays unset until A-4 defines the freshness contract")
                .isNull();
    }

    /**
     * The whole applied path over the real artifact: 110 rows in, the 100 MANDATES-shape
     * rows materialised, the checksum verified on the way (a wrong one would have refused at
     * gate 5), and the provenance row recording both counts.
     */
    @Test
    void theRealArtifactMaterialisesItsHundredMandatesRowsAndRecordsTheLoad() {
        final int applied = serviceFor(committedProperties.root(),
                committedProperties.datasetVersion(), null).load(null);

        assertThat(applied).isEqualTo(100);
        assertThat(accountRowCount()).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account WHERE account_type_code = 'SAV'",
                Integer.class)).as("the projection is loaded verbatim, SAV accounts included").isEqualTo(20);

        final AccountReferenceLoadEntity load = loads.findAll().iterator().next();
        assertThat(load.getDatasetVersion()).isEqualTo("2026.08.09-001");
        assertThat(load.getSchemaVersion()).isEqualTo(1);
        assertThat(load.getSourceId()).isEqualTo("fixture:fnb_dcre_ctv_toolkit/dcre_accounts.csv");
        assertThat(load.getChecksum())
                .isEqualTo("5831d612cbcce77f17f5f4ee50dd05cfed14bfc6ce72182649f4eccdb64e75a6");
        assertThat(load.getEffectiveTs()).isEqualTo(Instant.parse("2026-08-09T00:00:00Z"));
        assertThat(load.getPublicationTs()).isEqualTo(Instant.parse("2026-08-09T00:00:00Z"));
        assertThat(load.getRowCount()).as("the manifest's count over the WHOLE artifact").isEqualTo(110);
        assertThat(load.getAppliedRowCount()).as("what MRV materialised").isEqualTo(100);
        assertThat(load.getJobExecutionId()).as("no Batch execution drove this call").isNull();
    }

    /**
     * account_type is a CLOSED VOCABULARY seeded by Liquibase and is not part of the
     * artifact. "The loader owns account reference data" is the natural reading of the
     * class name and the wrong one, so the invariant is asserted rather than commented.
     */
    @Test
    void theLoadNeverTouchesTheClosedAccountTypeVocabulary() {
        serviceFor(committedProperties.root(), committedProperties.datasetVersion(), null).load(null);

        assertThat(jdbc.queryForList("SELECT code FROM account_type ORDER BY code", String.class))
                .containsExactly("CC", "CHQ", "RF", "SAV", "TRN");
        assertThat(jdbc.queryForList(
                "SELECT code FROM account_type WHERE mandates_allowed = false", String.class))
                .as("SAV is the only code disallowing mandates, and the loader may not change that")
                .containsExactly("SAV");
    }

    /**
     * Gate 7, inert half: with no max-age configured the load PROCEEDS and says so on every
     * run. The log line is the deliverable here, so it is asserted rather than assumed.
     */
    @Test
    void anUnsetMaxAgeLeavesTheFreshnessGateInertAndSaysSoOnEveryRun() {
        final ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            serviceFor(committedProperties.root(), committedProperties.datasetVersion(), null).load(null);

            assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage))
                    .as("an inert gate that says nothing is indistinguishable from an absent one")
                    .anySatisfy(message -> assertThat(message)
                            .contains("freshness check is INERT pending A-4")
                            .contains("dcre.mrv.reference.account.max-age is unset")
                            .contains("2026-08-09T00:00:00Z"));
            assertThat(accountRowCount()).as("inert means the load proceeds").isEqualTo(100);
        } finally {
            detach(appender);
        }
    }

    /**
     * Both jobs live in one context and a launch picks one BY NAME. The committed default
     * stays the validation flow: a reference load must never happen because somebody
     * launched MRV.
     */
    @Test
    void theLoadJobIsSelectableByNameAndIsNotTheCommittedDefault() {
        assertThat(environment.getProperty("spring.batch.job.name"))
                .as("the committed selector default")
                .isEqualTo("mrvJob");
        assertThat(accountReferenceLoadJob.getName()).isEqualTo("mrvAccountReferenceLoadJob");
    }

    /** The job actually drives the tasklet, and the run's execution id lands on the load record. */
    @Test
    void theLoadJobRunsTheLoadAndStampsItsExecutionId() throws Exception {
        final JobExecution run = jobOperator.start(accountReferenceLoadJob,
                new JobParametersBuilder().addString("run.id", UUID.randomUUID().toString(), true)
                        .toJobParameters());

        assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(accountRowCount()).isEqualTo(100);
        assertThat(jdbc.queryForObject(
                "SELECT job_execution_id FROM account_reference_load", Long.class))
                .isEqualTo(run.getId());
        assertThat(jdbc.queryForList("SELECT applied_row_count FROM account_reference_load", Integer.class))
                .containsExactly(100);
    }

    private static ListAppender<ILoggingEvent> attachAppender() {
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        logger().setLevel(Level.INFO);
        logger().addAppender(appender);
        return appender;
    }

    private static void detach(final ListAppender<ILoggingEvent> appender) {
        logger().detachAppender(appender);
        appender.stop();
    }

    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger(AccountReferenceLoadService.class);
    }
}
