package za.co.fnb.dcre.pai;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest(properties = {
        // SCRUM-107: PAI no longer mints tx_header, tx_entry or account. The test
        // master stands them up first, then runs the production master unchanged.
        "spring.liquibase.change-log=classpath:db/changelog/db.changelog-test-master.xml",
        "spring.batch.job.enabled=false",
        "dcre.exchange-root=build/test-exchange"})
class PaiJobTest {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static {
        CRDB.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
    }

    @Autowired
    Job paiJob;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void initsCreditorAccountsIdempotently() throws Exception {
        UUID arrival = UUID.randomUUID();
        // account + tx_header + tx_entry exist via the TEST fixture
        // (src/test/resources/db/changelog/test/001-read-sources.xml), standing in for
        // their owners. PAI no longer mints them; see db.changelog-master.xml.
        String[] known = {"62000000000000001", "62000000000000002", "62000000000000003"};
        for (String acc : known) {
            jdbc.update("INSERT INTO account (product_code, account_number, app_no, acc_type,"
                    + " branch_code, balance, process_status, status, ucn, client_id)"
                    + " VALUES ('FNBRF', ?, ?, 'CACC', '250205', 19317.67, 'ACTIVE', 'AAUT',"
                    + " '100000000001', 2) ON CONFLICT (account_number) DO NOTHING", acc, acc);
        }
        jdbc.update("INSERT INTO tx_header (arrival_id, msg_id_raw, msg_id, created_ts, tx_count,"
                + " initg_pty, business_date, layout_version)"
                + " VALUES (?, 'MSG-1', 'MSG-1', '20260711120000', 5, 'FNB', '20260711', 2)", arrival);
        String[] creditors = {known[0], known[1], "62999999999999901", known[2], "62999999999999902"};
        for (int i = 0; i < creditors.length; i++) {
            jdbc.update("INSERT INTO tx_entry (arrival_id, sequence, record_type, e2e_raw, e2e,"
                    + " creditor_account, currency, amount_raw, amount)"
                    + " VALUES (?, ?, '02', ?, ?, ?, 'ZAR', '000000010000', 100.00)",
                    arrival, i + 1, "E2E-" + (i + 1), "E2E-" + (i + 1), creditors[i]);
        }
        int accountsBefore = jdbc.queryForObject("SELECT count(*) FROM account", Integer.class);

        JobExecution run = jobOperator.start(paiJob, new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true).toJobParameters());
        assertEquals(BatchStatus.COMPLETED, run.getStatus());
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM pai_verdict WHERE arrival_id=?"
                + " AND action='EXISTS'", Integer.class, arrival));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM pai_verdict WHERE arrival_id=?"
                + " AND action='CREATED'", Integer.class, arrival));
        // SCRUM-107: the account master is READ, never written. The two absent creditors
        // are recorded in PAI's own relation instead.
        assertEquals(accountsBefore, jdbc.queryForObject(
                "SELECT count(*) FROM account", Integer.class), "PAI writes no row into the account master");
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM unknown_creditor WHERE arrival_id=?",
                        Integer.class, arrival),
                "exactly the 2 absent creditors recorded, in the relation PAI owns");

        JobExecution rerun = jobOperator.start(paiJob, new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true)
                .addString("rerun", "2", true).toJobParameters());
        assertEquals(BatchStatus.COMPLETED, rerun.getStatus());
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM pai_verdict WHERE arrival_id=?"
                + " AND action='EXISTS'", Integer.class, arrival), "verdicts immutable: no CREATED->EXISTS flip");
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM pai_verdict WHERE arrival_id=?"
                + " AND action='CREATED'", Integer.class, arrival), "verdicts immutable: rerun keeps CREATED");
        assertEquals(accountsBefore, jdbc.queryForObject(
                "SELECT count(*) FROM account", Integer.class), "a rerun writes no row into the master either");
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM unknown_creditor WHERE arrival_id=?",
                        Integer.class, arrival),
                "rerun records no duplicate sighting: insert-once on the account number (R-05)");
    }

    @Test
    void seamFallbackNameIsSelfDescribingWithoutJobNameEnv() throws Exception {
        assumeTrue(System.getenv("JOB_NAME") == null, "requires no JOB_NAME in the test environment");
        // No fixtures: an arrival with zero tx entries is a valid all-exist no-op run (A-7).
        JobExecution run = jobOperator.start(paiJob, new JobParametersBuilder()
                .addString("arrival.id", UUID.randomUUID().toString(), true).toJobParameters());
        assertEquals(BatchStatus.COMPLETED, run.getStatus());

        Path seam = Path.of("build/test-exchange", "outcomes", "local-pai-" + run.getId());
        assertTrue(Files.exists(seam),
                "SCRUM-58: without JOB_NAME env the seam file must carry the self-describing"
                        + " fleet-wide fallback name, expected " + seam);
    }
}
