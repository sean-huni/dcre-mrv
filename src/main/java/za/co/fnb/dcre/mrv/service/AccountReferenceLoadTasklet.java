package za.co.fnb.dcre.mrv.service;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * Thin Batch adapter (3-tier rule): extract the execution id, call ONE service method,
 * report the row count into the step's write count. No business logic, no persistence.
 */
@Component
public class AccountReferenceLoadTasklet implements Tasklet {

    private final AccountReferenceLoadService service;

    public AccountReferenceLoadTasklet(final AccountReferenceLoadService service) {
        this.service = service;
    }

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final Long jobExecutionId =
                chunkContext.getStepContext().getStepExecution().getJobExecutionId();
        contribution.incrementWriteCount(service.load(jobExecutionId));
        return RepeatStatus.FINISHED;
    }
}
