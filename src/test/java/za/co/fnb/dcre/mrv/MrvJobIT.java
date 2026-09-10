package za.co.fnb.dcre.mrv;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MRV job over the real chain + CockroachDB (Testcontainers, fleet pattern). Every
 * VerdictChain stage is exercised end to end through the job (item reject +
 * spine_state transition), plus the R-41 rollup modes (ACCEPTED / PARTIAL /
 * BUSINESS_FILE_REJECTED under ALL_OR_NOTHING and structural override), the
 * action-specific known-ref checks (derived status view + prior spine), and the
 * resume/idempotency guarantee (re-run = zero duplicate verdicts, spine stable).
 * FNBCC01 is the default ALL_OR_NOTHING client; FNBRF01 is mapped to PARTIAL.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false",
        "dcre.mrv.acceptance-mode.clients.[FNBRF01]=PARTIAL"})
class MrvJobIT {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static final Path EXCHANGE = freshExchangeRoot();

    static {
        CRDB.start();
    }

    static Path freshExchangeRoot() {
        try {
            return Files.createTempDirectory("mrv-seam-it");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
        registry.add("dcre.exchange-root", EXCHANGE::toString);
    }

    @Autowired
    Job mrvJob;
    @Autowired
    JobOperator jobOperator;
    @Autowired
    JdbcTemplate jdbc;

    /**
     * The account master AND the load ledger that says a materialisation put it there. The
     * ledger row is not decoration: MRV halts a run whose account master was never
     * materialised, so a fixture that seeds accounts without it describes an environment no
     * validation should ever be allowed to run in.
     */
    @BeforeEach
    void seedReference() {
        ManReadSideSchema.apply(jdbc);
        ManTestTables.seedAccount(jdbc, "6200000021", "CHQ");
        ManTestTables.seedAccount(jdbc, "6200000099", "SAV");
        ManTestTables.seedAccountReferenceLoad(jdbc, "2026.08.09-001", 2);
    }

    private JobExecution run(final UUID arrival, final String attempt) throws Exception {
        final JobParametersBuilder b = new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true);
        if (attempt != null) {
            b.addString("attempt", attempt, true);
        }
        final JobParameters params = b.toJobParameters();
        return jobOperator.start(mrvJob, params);
    }

    private String outcome(final UUID arrival, final int seq) {
        return jdbc.queryForObject(
                "SELECT outcome FROM man_validation_log WHERE arrival_id=? AND sequence=?",
                String.class, arrival, seq);
    }

    private List<String> spineStates(final UUID arrival) {
        return jdbc.queryForList(
                "SELECT spine_state FROM mandate_request_entry WHERE arrival_id=? ORDER BY sequence",
                String.class, arrival);
    }

    @Test
    void cleanCreateArrivalIsAllValidatedAndAccepted() throws Exception {
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, "FNBCC01", 2);
        ManTestTables.insertEntry(jdbc, arrival, 1, "CREATE", "MREF-A", "CTRAA1", "6200000021", false);
        ManTestTables.insertEntry(jdbc, arrival, 2, "CREATE", "MREF-B", "CTRAA2", "6200000021", false);

        final JobExecution run = run(arrival, null);
        assertEquals(BatchStatus.COMPLETED, run.getStatus());
        assertEquals("BUSINESS_ACCEPTED", run.getExitStatus().getExitCode());
        assertEquals("PASS", outcome(arrival, 1));
        assertEquals("PASS", outcome(arrival, 2));
        assertEquals(List.of("VALIDATED", "VALIDATED"), spineStates(arrival));

        Assumptions.assumeTrue(System.getenv("JOB_NAME") == null, "seam name requires JOB_NAME absent");
        final Path seam = EXCHANGE.resolve("outcomes").resolve("local-mrv-" + run.getId());
        assertTrue(Files.exists(seam), "expected self-describing seam file at " + seam);
        assertEquals(List.of("BUSINESS_ACCEPTED"), Files.readAllLines(seam));
    }

    @Test
    void allOrNothingRejectsWholeFileAndAllRowsOnAnyFail() throws Exception {
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, "FNBCC01", 2); // default ALL_OR_NOTHING
        ManTestTables.insertEntry(jdbc, arrival, 1, "CREATE", "MREF-C", "CTRBB1", "6200000021", false);
        ManTestTables.insertEntry(jdbc, arrival, 2, "CREATE", "MREF-D", "CTRBB2", "6299999999", false); // unknown acct

        final JobExecution run = run(arrival, null);
        assertEquals("BUSINESS_FILE_REJECTED", run.getExitStatus().getExitCode());
        assertEquals("PASS", outcome(arrival, 1));
        assertEquals("FAIL_ACCOUNT_NOT_FOUND", outcome(arrival, 2));
        assertEquals(List.of("REJECTED", "REJECTED"), spineStates(arrival),
                "ALL_OR_NOTHING: even the passing row is REJECTED so nothing proceeds to MAS");
    }

    @Test
    void partialModeRejectsOnlyTheFailingRow() throws Exception {
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, "FNBRF01", 2); // PARTIAL
        ManTestTables.insertEntry(jdbc, arrival, 1, "CREATE", "MREF-E", "CTRCC1", "6200000021", false);
        ManTestTables.insertEntry(jdbc, arrival, 2, "CREATE", "MREF-F", "CTRCC2", "6299999999", false);

        final JobExecution run = run(arrival, null);
        assertEquals("BUSINESS_PARTIAL", run.getExitStatus().getExitCode());
        assertEquals(List.of("VALIDATED", "REJECTED"), spineStates(arrival));
    }

    @Test
    void structuralFailRejectsWholeFileEvenUnderPartial() throws Exception {
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, "FNBRF01", 2); // PARTIAL, but structural is whole-file fatal
        ManTestTables.insertEntry(jdbc, arrival, 1, "CREATE", "MREF-G", "CTRDD1", "6200000021", false);
        ManTestTables.insertEntry(jdbc, arrival, 2, "BOGUS", "MREF-H", "CTRDD2", "6200000021", false);

        final JobExecution run = run(arrival, null);
        assertEquals("BUSINESS_FILE_REJECTED", run.getExitStatus().getExitCode());
        assertEquals("FAIL_STRUCTURE", outcome(arrival, 2));
        assertEquals(List.of("REJECTED", "REJECTED"), spineStates(arrival));
    }

    @Test
    void disallowedAccountTypeIsItemReject() throws Exception {
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, "FNBRF01", 1);
        ManTestTables.insertEntry(jdbc, arrival, 1, "CREATE", "MREF-I", "CTREE1", "6200000099", false); // SAV

        run(arrival, null);
        assertEquals("FAIL_ACCOUNT_TYPE_DISALLOWED", outcome(arrival, 1));
        assertEquals(List.of("REJECTED"), spineStates(arrival));
    }

    @Test
    void badContractFormatIsItemReject() throws Exception {
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, "FNBRF01", 1);
        ManTestTables.insertEntry(jdbc, arrival, 1, "CREATE", "MREF-J", "CTR/BAD*", "6200000021", false);

        run(arrival, null);
        assertEquals("FAIL_CONTRACT_FORMAT", outcome(arrival, 1));
        assertEquals(List.of("REJECTED"), spineStates(arrival));
    }

    /**
     * SCRUM-91: the known-ref check's first arm reads mandate_effective_status, not the
     * deleted MSR projection. The predecessor here is REJECTED on the spine, so ONLY the
     * derived-view arm can make its ref known: it proves that arm still resolves.
     */
    @Test
    void amendKnownViaEffectiveStatusPassesUnknownRejects() throws Exception {
        final UUID prior = UUID.randomUUID();
        ManEffectiveStatusTables.seedPriorRegistration(jdbc, prior, "FNBRF01", "MREF-EXIST-P", "CTRP",
                "6200000021", "7300000001", "20991231", "MREQ-EXIST-P", "REJECTED");
        ManEffectiveStatusTables.seedPbsr(jdbc, "MREQ-EXIST-P", "MREF-EXIST-P", "CANC", "MD07");

        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, "FNBRF01", 2);
        ManTestTables.insertEntry(jdbc, arrival, 1, "AMEND", "MREF-EXIST-P", "CTRP", "6200000021", false);
        ManTestTables.insertEntry(jdbc, arrival, 2, "AMEND", "MREF-UNKNOWN", "CTRFF2", "6200000021", false);

        run(arrival, null);
        assertEquals("PASS", outcome(arrival, 1));
        assertEquals("FAIL_AMEND_UNKNOWN_REF", outcome(arrival, 2));
        assertEquals(List.of("VALIDATED", "REJECTED"), spineStates(arrival));
    }

    @Test
    void createExistingRefViaPriorSpineIsDuplicateCancelKnownPasses() throws Exception {
        final UUID prior = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, prior, "FNBRF01", 1);
        ManTestTables.insertPriorEntry(jdbc, prior, 1, "CREATE", "MREF-PRIOR", "VALIDATED");

        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, "FNBRF01", 2);
        ManTestTables.insertEntry(jdbc, arrival, 1, "CREATE", "MREF-PRIOR", "CTRGG1", "6200000021", false);
        ManTestTables.insertEntry(jdbc, arrival, 2, "CANCEL", "MREF-PRIOR", "CTRGG2", "6200000021", false);

        run(arrival, null);
        assertEquals("FAIL_DUPLICATE_REF", outcome(arrival, 1), "CREATE collides with a prior registration");
        assertEquals("PASS", outcome(arrival, 2), "CANCEL of a known ref passes");
        assertEquals(List.of("REJECTED", "VALIDATED"), spineStates(arrival));
    }

    @Test
    void intraFileDuplicateFlaggedRowRejects() throws Exception {
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, "FNBRF01", 2);
        ManTestTables.insertEntry(jdbc, arrival, 1, "CREATE", "MREF-K", "CTRHH1", "6200000021", false);
        ManTestTables.insertEntry(jdbc, arrival, 2, "CREATE", "MREF-K", "CTRHH2", "6200000021", true); // dup_in_file

        run(arrival, null);
        assertEquals("PASS", outcome(arrival, 1));
        assertEquals("FAIL_DUPLICATE_REF", outcome(arrival, 2));
        assertEquals(List.of("VALIDATED", "REJECTED"), spineStates(arrival));
    }

    @Test
    void reRunIsIdempotentZeroDuplicateAndSpineStable() throws Exception {
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, "FNBCC01", 2);
        ManTestTables.insertEntry(jdbc, arrival, 1, "CREATE", "MREF-R1", "CTRII1", "6200000021", false);
        ManTestTables.insertEntry(jdbc, arrival, 2, "CREATE", "MREF-R2", "CTRII2", "6200000021", false);

        assertEquals(BatchStatus.COMPLETED, run(arrival, "1").getStatus());
        assertEquals(List.of("VALIDATED", "VALIDATED"), spineStates(arrival));

        // Fresh JobInstance re-processing the same arrival: verdicts and transitions replay idempotently.
        assertEquals(BatchStatus.COMPLETED, run(arrival, "2").getStatus());

        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM man_validation_log WHERE arrival_id=?", Integer.class, arrival));
        assertEquals(jdbc.queryForObject(
                        "SELECT count(*) FROM man_validation_log WHERE arrival_id=?", Integer.class, arrival),
                jdbc.queryForObject(
                        "SELECT count(DISTINCT (arrival_id, sequence)) FROM man_validation_log WHERE arrival_id=?",
                        Integer.class, arrival),
                "zero-duplicate audit: count == distinct business identity");
        assertEquals(List.of("VALIDATED", "VALIDATED"), spineStates(arrival),
                "re-run does not re-transition (guard spine_state='RECEIVED')");
    }
}
