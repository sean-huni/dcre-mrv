package za.co.fnb.dcre.mrv.service;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Thin entry adapter: rolls the durable verdicts up to the job's business exit
 * status and transitions the spine. The verdict is mirrored into the job execution
 * context as seamVerdict because the OutcomeSeamListener runs afterJob (before the
 * flow's terminal exit code is applied), so it must read the durable context value,
 * not getExitStatus() (CTV pattern).
 */
@Component
public class RollupTasklet implements Tasklet {

    private final ManRollupService service;

    public RollupTasklet(final ManRollupService service) {
        this.service = service;
    }

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final UUID arrivalId = UUID.fromString(
                (String) chunkContext.getStepContext().getJobParameters().get("arrival.id"));
        final var context = chunkContext.getStepContext().getStepExecution()
                .getJobExecution().getExecutionContext();
        final String verdict = service.rollupAndTransition(arrivalId, context.getString("clientToken", ""));
        context.putString("seamVerdict", verdict);
        contribution.setExitStatus(new ExitStatus(verdict));
        return RepeatStatus.FINISHED;
    }
}
