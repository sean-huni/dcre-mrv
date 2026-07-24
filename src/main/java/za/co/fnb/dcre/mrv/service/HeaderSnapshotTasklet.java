package za.co.fnb.dcre.mrv.service;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Thin entry adapter (3-tier, configuration.md point 21): resolves the client
 * token and captures the single CTV F51 as-of HLC into the job execution context,
 * shared by the validate + rollup steps.
 */
@Component
public class HeaderSnapshotTasklet implements Tasklet {

    private final ManValidationService service;

    public HeaderSnapshotTasklet(final ManValidationService service) {
        this.service = service;
    }

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final UUID arrivalId = UUID.fromString(
                (String) chunkContext.getStepContext().getJobParameters().get("arrival.id"));
        final var context = chunkContext.getStepContext().getStepExecution()
                .getJobExecution().getExecutionContext();
        final ManValidationService.HeaderSnapshot snapshot = service.checkHeader(arrivalId);
        context.putString("clientToken", snapshot.clientToken());
        context.putString("asOfTimestamp", snapshot.asOfTimestamp());
        return RepeatStatus.FINISHED;
    }
}
