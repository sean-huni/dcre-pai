package za.co.fnb.dcre.pai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import za.co.fnb.dcre.pai.service.AccountInitService.InitCounts;

import java.util.UUID;

/** Thin entry adapter (3-tier, configuration.md point 21). */
@Component
public class AccountInitTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(AccountInitTasklet.class);

    private final AccountInitService service;

    public AccountInitTasklet(AccountInitService service) {
        this.service = service;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        UUID arrivalId = UUID.fromString(
                (String) chunkContext.getStepContext().getJobParameters().get("arrival.id"));
        InitCounts counts = service.init(arrivalId);
        log.info("Account init for arrival {}: {} existing, {} created", arrivalId,
                counts.existing(), counts.created());
        chunkContext.getStepContext().getStepExecution().getJobExecution()
                .getExecutionContext().putInt("existing", counts.existing());
        chunkContext.getStepContext().getStepExecution().getJobExecution()
                .getExecutionContext().putInt("created", counts.created());
        return RepeatStatus.FINISHED;
    }
}
