package za.co.fnb.dcre.mrv.service;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Thin entry adapter: classifies every spine row of the arrival against the
 * reference stores read AS OF the header step's captured HLC, writing the durable
 * man_validation_log.
 */
@Component
public class ValidationTasklet implements Tasklet {

    private final ManValidationService service;

    public ValidationTasklet(final ManValidationService service) {
        this.service = service;
    }

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final UUID arrivalId = UUID.fromString(
                (String) chunkContext.getStepContext().getJobParameters().get("arrival.id"));
        final var context = chunkContext.getStepContext().getStepExecution()
                .getJobExecution().getExecutionContext();
        service.validate(arrivalId, context.getString("asOfTimestamp"));
        return RepeatStatus.FINISHED;
    }
}
