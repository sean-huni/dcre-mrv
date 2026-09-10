package za.co.fnb.dcre.mrv.config;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import za.co.fnb.dcre.mrv.service.HeaderSnapshotTasklet;
import za.co.fnb.dcre.mrv.service.ManRollupService;
import za.co.fnb.dcre.mrv.service.RollupTasklet;
import za.co.fnb.dcre.mrv.service.ValidationTasklet;
import za.co.fnb.dcre.platform.batch.CrdbRetryExceptionHandler;
import za.co.fnb.dcre.platform.batch.HeartbeatWriter;
import za.co.fnb.dcre.platform.batch.OutcomeSeamListener;

/**
 * MRV job shape (CTV skeleton clone, un-partitioned: instruction books are low
 * volume, so the whole arrival is one validation pass, KISS): headerSnapshot
 * (capture the F51 as-of HLC + client token) -> validate (item-tier VerdictChain
 * over the spine, writes man_validation_log) -> rollup (R-41 acceptance-mode exit +
 * spine_state VALIDATED|REJECTED transition). Identifying JobParameter: arrival.id
 * (R-16). Runs on the default SERIALIZABLE isolation (cluster_logical_timestamp()
 * AS-OF snapshot is SERIALIZABLE-only; only CRG carries READ COMMITTED, SCRUM-90).
 */
@Configuration
@EnableConfigurationProperties(AcceptanceModeProperties.class)
public class MrvJobConfig {

    /**
     * CRDB 40001 retry for the steps that WRITE business rows (validate writes
     * man_validation_log; rollup writes the guarded spine_state transitions): the
     * aborts hit the chunk-commit boundary, which only a stepOperations-level
     * handler sees. The read-only header step stays without it.
     */
    private final CrdbRetryExceptionHandler crdbRetry = new CrdbRetryExceptionHandler("MRV");

    @Bean
    public Step headerSnapshotStep(final JobRepository repo, final PlatformTransactionManager tx,
                                   final HeaderSnapshotTasklet tasklet) {
        return new StepBuilder("headerSnapshotStep", repo).tasklet(tasklet, tx).build();
    }

    @Bean
    public Step validateStep(final JobRepository repo, final PlatformTransactionManager tx,
                             final ValidationTasklet tasklet) {
        return new StepBuilder("validateStep", repo).tasklet(tasklet, tx).exceptionHandler(crdbRetry).build();
    }

    @Bean
    public Step rollupStep(final JobRepository repo, final PlatformTransactionManager tx,
                           final RollupTasklet tasklet) {
        return new StepBuilder("rollupStep", repo).tasklet(tasklet, tx).exceptionHandler(crdbRetry).build();
    }

    @Bean
    public Job mrvJob(final JobRepository repo, final Step headerSnapshotStep, final Step validateStep,
                      final Step rollupStep, final HeartbeatWriter heartbeatWriter,
                      @Value("${dcre.exchange-root}") final String exchangeRoot) {
        return new JobBuilder("mrvJob", repo)
                .listener(new OutcomeSeamListener("mrv", exchangeRoot, MrvJobConfig::seamVerdict))
                .listener(heartbeatWriter)
                .start(headerSnapshotStep)
                    .on("FAILED").fail()
                .from(headerSnapshotStep).on("*").to(validateStep)
                .from(validateStep).on("FAILED").fail()
                .from(validateStep).on("*").to(rollupStep)
                .from(rollupStep).on(ManRollupService.BUSINESS_FILE_REJECTED)
                    .end(ManRollupService.BUSINESS_FILE_REJECTED)
                .from(rollupStep).on(ManRollupService.BUSINESS_PARTIAL).end(ManRollupService.BUSINESS_PARTIAL)
                .from(rollupStep).on(ManRollupService.BUSINESS_ACCEPTED).end(ManRollupService.BUSINESS_ACCEPTED)
                .from(rollupStep).on("*").fail()
                .end()
                .build();
    }

    /**
     * Seam verdict (supplied to the shared OutcomeSeamListener, SCRUM-58): the
     * rollup's acceptance-mode exit status. MRV has no whole-file structural FATAL
     * of its own (MRR owns file-level FILE_FATAL); a per-row structural fault rolls
     * up through the log to BUSINESS_FILE_REJECTED like any other file rejection.
     */
    private static String seamVerdict(final JobExecution execution) {
        return execution.getExecutionContext().getString("seamVerdict", ManRollupService.BUSINESS_ACCEPTED);
    }
}
