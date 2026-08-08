package za.co.fnb.dcre.pai.config;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.PlatformTransactionManager;
import za.co.fnb.dcre.pai.service.AccountInitTasklet;
import za.co.fnb.dcre.platform.batch.CrdbRetryExceptionHandler;
import za.co.fnb.dcre.platform.batch.HeartbeatWriter;
import za.co.fnb.dcre.platform.batch.OutcomeSeamListener;
import za.co.fnb.dcre.platform.batch.StaleExecutionSweeper;

import javax.sql.DataSource;

/**
 * Single-step job mirrored from CdeJobConfig. PAI is DB-only for BUSINESS
 * file I/O (R-30), but the outcome seam is AGT orchestration plumbing, not a
 * business file: without it the OutcomeWatcher/Reconciler can never observe
 * a business verdict and reaps the stage as TECH_FAILED (VanishedNoSeam).
 * CDE, equally DB-only, writes the same seam.
 */
@Configuration
public class PaiJobConfig {

    @Bean
    public Job paiJob(final JobRepository repo, final PlatformTransactionManager tx,
                      final AccountInitTasklet tasklet, final HeartbeatWriter heartbeatWriter,
                      @Value("${dcre.exchange-root}") final String exchangeRoot) {
        // SCRUM-42: the verdict step WRITES pai_verdict + account rows concurrently with the
        // fleet's heavy writers; CRDB 40001 commit-time aborts are normal under contention and
        // are retried in a fresh tx by the shared handler (retry, never skip). The step tx is
        // THIN: all reads/writes commit in per-slice REQUIRES_NEW transactions inside
        // AccountInitService, so a step-level re-run no-ops over committed slices.
        Step accountInitStep = new StepBuilder("accountInitStep", repo)
                .tasklet(tasklet, tx)
                .exceptionHandler(new CrdbRetryExceptionHandler("PAI"))
                .build();
        // SCRUM-58: shared seam listener; verdict semantics unchanged (COMPLETED gate,
        // constant BUSINESS_ACCEPTED), dev fallback name now self-describing (local-pai-<id>).
        return new JobBuilder("paiJob", repo)
                .listener(new OutcomeSeamListener("pai", exchangeRoot, execution -> "BUSINESS_ACCEPTED"))
                .listener(heartbeatWriter)
                .start(accountInitStep)
                .build();
    }

    @Bean
    @Order(-10)
    public ApplicationRunner staleExecutionSweep(DataSource dataSource) {
        return args -> StaleExecutionSweeper.abandonStale(dataSource, "PAI_BATCH_", 60);
    }
}
