package za.co.fnb.dcre.pai.config;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.StepLocator;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import za.co.fnb.dcre.pai.service.AccountInitTasklet;
import za.co.fnb.dcre.platform.batch.HeartbeatWriter;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Registration proof for the shared CRDB 40001 retry (SCRUM-42): the REAL
 * accountInitStep built by PaiJobConfig must swallow a commit-time
 * TransientDataAccessException (how CockroachDB serialization aborts surface)
 * and re-run the verdict tasklet in a fresh transaction. Handler semantics
 * (budget, backoff, non-transient pass-through) are proven in platform-batch.
 */
class PaiJobConfigRetryTest {

    /** Fails the first {@code failures} COMMITS the way JdbcTransactionManager surfaces a CRDB 40001. */
    static final class CommitFailingTxManager extends ResourcelessTransactionManager {

        private final int failures;
        private int commits;

        CommitFailingTxManager(final int failures) {
            this.failures = failures;
        }

        @Override
        protected void doCommit(final DefaultTransactionStatus status) {
            if (++commits <= failures) {
                throw new CannotAcquireLockException(
                        "JDBC commit; ERROR: restart transaction: TransactionRetryWithProtoRefreshError:"
                                + " RETRY_SERIALIZABLE");
            }
            super.doCommit(status);
        }
    }

    @Test
    void verdictStepRetriesCommitTime40001Aborts() throws Exception {
        final var executions = new AtomicInteger();
        final AccountInitTasklet tasklet = new AccountInitTasklet(null) {
            @Override
            public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
                executions.incrementAndGet();
                return RepeatStatus.FINISHED;
            }
        };
        final var repo = new ResourcelessJobRepository();
        // Step-only test: execute() runs the STEP directly, never the Job, so the heartbeat
        // listener's beforeJob/afterJob/tick never fire; a no-op writer (null jobName => tick
        // no-ops) satisfies the new paiJob HeartbeatWriter param without touching agt_ops.
        final HeartbeatWriter heartbeat = new HeartbeatWriter(null, null, "test");
        final Job job = new PaiJobConfig()
                .paiJob(repo, new CommitFailingTxManager(2), tasklet, heartbeat, "build/test-exchange");
        final Step step = ((StepLocator) job).getStep("accountInitStep");

        final JobInstance instance = repo.createJobInstance("retryWiringJob", new JobParameters());
        final JobExecution jobExecution = repo.createJobExecution(instance, new JobParameters(), new ExecutionContext());
        final StepExecution stepExecution = repo.createStepExecution("accountInitStep", jobExecution);
        step.execute(stepExecution);

        assertEquals(BatchStatus.COMPLETED, stepExecution.getStatus(),
                "commit-time 40001 aborts must be retried on the verdict step, not fail it");
        assertEquals(3, executions.get(), "verdict tasklet re-runs in a fresh tx per aborted commit");
    }
}
