package za.co.fnb.dcre.mrv.config;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import za.co.fnb.dcre.mrv.service.AccountReferenceLoadTasklet;
import za.co.fnb.dcre.platform.batch.HeartbeatWriter;
import za.co.fnb.dcre.platform.batch.OutcomeSeamListener;

/**
 * MRV's SECOND job: materialise the versioned account reference artifact into
 * {@code dcre_man.account}. One step, no partitioning, 100 rows.
 *
 * <p>It is NOT the default job of the validation flow. Both jobs are plain beans in one
 * context and a launch selects one BY NAME through {@code spring.batch.job.name}, which the
 * yml binds to {@code DCRE_MRV_JOB_NAME} with {@code mrvJob} as the committed default. That
 * is the same selector MRG already uses for its report/sweep pair, and it is Boot's own
 * mechanism: {@code JobLauncherApplicationRunner} refuses to start at all with more than one
 * Job bean and no name, so a second job cannot silently displace the first.
 *
 * <p>Listeners match the family shape: the shared heartbeat writer so AGT observes the run,
 * and the outcome seam so a completed load self-describes into the exchange like every other
 * DCRE stage.
 */
@Configuration
@EnableConfigurationProperties(AccountReferenceProperties.class)
public class AccountReferenceLoadJobConfig {

    /** The name a launch supplies to select this job instead of the validation flow. */
    public static final String JOB = "mrvAccountReferenceLoadJob";

    private final String exchangeRoot;

    public AccountReferenceLoadJobConfig(@Value("${dcre.exchange-root}") final String exchangeRoot) {
        this.exchangeRoot = exchangeRoot;
    }

    @Bean
    public Step accountReferenceLoadStep(final JobRepository repo, final PlatformTransactionManager tx,
                                         final AccountReferenceLoadTasklet tasklet) {
        return new StepBuilder("accountReferenceLoadStep", repo).tasklet(tasklet, tx).build();
    }

    @Bean
    public Job mrvAccountReferenceLoadJob(final JobRepository repo, final Step accountReferenceLoadStep,
                                          final HeartbeatWriter heartbeatWriter) {
        return new JobBuilder(JOB, repo)
                .listener(new OutcomeSeamListener("mrv-account-reference", exchangeRoot,
                        execution -> "BUSINESS_ACCEPTED"))
                .listener(heartbeatWriter)
                .start(accountReferenceLoadStep)
                .build();
    }
}
