package za.co.fnb.dcre.pai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;
import za.co.fnb.dcre.pai.data.repo.AccountRepo;
import za.co.fnb.dcre.pai.data.repo.PaiVerdictRepo;
import za.co.fnb.dcre.pai.data.repo.TxEntryRepo;
import za.co.fnb.dcre.pai.service.AccountInitService.InitCounts;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCRUM-42 chunked-verdict proofs against a real CRDB. The 300k-tx sweep
 * showed one giant serializable tx is unrefreshable (RETRY_SERIALIZABLE
 * "can't refresh txn spans"), so verdicts must commit in bounded slices:
 * (1) a slice that exhausts its retry budget fails the run WITHOUT rolling
 * back slices already committed; (2) a transient 40001 abort on a slice is
 * retried in a fresh tx and succeeds; (3) a re-run over committed slices
 * no-ops (row identity preserved, no CREATED->EXISTS flip, no dup accounts).
 * init() runs inside an outer REQUIRED tx exactly like the Batch step tx.
 */
@SpringBootTest(properties = {
        "spring.liquibase.change-log=classpath:db/changelog/db.changelog-test-master.xml",
        "spring.batch.job.enabled=false"})
class AccountInitServiceSliceTest {

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

    static final int SLICE_SIZE = 2;

    @Autowired
    TxEntryRepo entries;

    @Autowired
    AccountRepo accounts;

    @Autowired
    PaiVerdictRepo verdicts;

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    JdbcTemplate jdbc;

    UUID arrival;
    String prefix;
    TransactionTemplate stepTx;

    @BeforeEach
    void newArrival() {
        arrival = UUID.randomUUID();
        prefix = "62" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        stepTx = new TransactionTemplate(txManager);
    }

    @Test
    void sliceFailureDoesNotRollBackCommittedSlices() {
        seedUnknownCreditors(6);
        AccountInitService service = service(failingVerdicts(seq -> seq == 5, Integer.MAX_VALUE));

        assertThrows(TransientDataAccessException.class,
                () -> stepTx.executeWithoutResult(status -> service.init(arrival)),
                "a slice that exhausts its retry budget must fail the run");

        assertEquals(4, verdictCount(),
                "slices (1,2) and (3,4) committed before the failing slice must stand");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pai_verdict WHERE arrival_id=?"
                        + " AND sequence >= 5", Integer.class, arrival),
                "nothing from the failed slice may leak");
    }

    @Test
    void transientAbortOnSliceRetriesInFreshTxThenSucceeds() {
        seedUnknownCreditors(6);
        AccountInitService service = service(failingVerdicts(seq -> seq == 3, 2));

        InitCounts counts = stepTx.execute(status -> service.init(arrival));

        assertEquals(new InitCounts(0, 6), counts, "all six unknown creditors initialised");
        assertEquals(6, verdictCount(), "retried slice committed after transient 40001 aborts");
        assertEquals(6, verdictCount("CREATED"), "every verdict is CREATED");
    }

    @Test
    void sliceRerunNoOpsOverCommittedSlices() {
        seedUnknownCreditors(6);
        assertThrows(TransientDataAccessException.class, () -> stepTx.executeWithoutResult(
                status -> service(failingVerdicts(seq -> seq == 5, Integer.MAX_VALUE)).init(arrival)));
        assertEquals(4, verdictCount(), "restart precondition: four verdicts committed");
        List<UUID> committedIds = jdbc.queryForList(
                "SELECT id FROM pai_verdict WHERE arrival_id=? ORDER BY sequence", UUID.class, arrival);

        InitCounts rerun = stepTx.execute(status -> service(verdicts).init(arrival));

        assertEquals(new InitCounts(4, 2), rerun,
                "restart sees slices 1-2 as existing accounts and resumes the failed slice");
        assertEquals(6, verdictCount(), "one verdict per spine transaction, nothing else");
        assertEquals(6, verdictCount("CREATED"), "verdicts immutable: no CREATED->EXISTS flip on re-run");
        List<UUID> after = jdbc.queryForList(
                "SELECT id FROM pai_verdict WHERE arrival_id=? ORDER BY sequence", UUID.class, arrival);
        assertTrue(after.containsAll(committedIds), "committed rows keep their identity (no delete+reinsert)");
        for (int seq = 1; seq <= 6; seq++) {
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM account WHERE account_number=?",
                    Integer.class, prefix + seq), "exactly one account row per creditor (R-05/R-11)");
        }
    }

    private AccountInitService service(final PaiVerdictRepo verdictRepo) {
        return new AccountInitService(entries, accounts, verdictRepo, txManager, SLICE_SIZE);
    }

    /** Delegates to the real repo; throws a CRDB-shaped 40001 for matching sequences, {@code failures} times. */
    private PaiVerdictRepo failingVerdicts(final IntPredicate failSequence, final int failures) {
        AtomicInteger thrown = new AtomicInteger();
        return (PaiVerdictRepo) Proxy.newProxyInstance(PaiVerdictRepo.class.getClassLoader(),
                new Class<?>[]{PaiVerdictRepo.class}, (proxy, method, args) -> {
                    if ("insertIfAbsent".equals(method.getName()) && failSequence.test((Integer) args[1])
                            && thrown.getAndIncrement() < failures) {
                        throw new CannotAcquireLockException("ERROR: restart transaction:"
                                + " TransactionRetryWithProtoRefreshError: RETRY_SERIALIZABLE"
                                + " - failed preemptive refresh: can't refresh txn spans; not valid");
                    }
                    try {
                        return method.invoke(verdicts, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private void seedUnknownCreditors(final int txCount) {
        jdbc.update("INSERT INTO tx_header (arrival_id, msg_id_raw, msg_id, created_ts, tx_count,"
                + " initg_pty, business_date, layout_version)"
                + " VALUES (?, ?, ?, '20260714120000', ?, 'FNB', '20260714', 2)",
                arrival, "MSG" + prefix, "MSG" + prefix, txCount);
        for (int seq = 1; seq <= txCount; seq++) {
            jdbc.update("INSERT INTO tx_entry (arrival_id, sequence, record_type, e2e_raw, e2e,"
                    + " creditor_account, currency, amount_raw, amount)"
                    + " VALUES (?, ?, '02', ?, ?, ?, 'ZAR', '000000010000', 100.00)",
                    arrival, seq, "E2E" + prefix + seq, "E2E" + prefix + seq, prefix + seq);
        }
    }

    private int verdictCount() {
        return jdbc.queryForObject("SELECT count(*) FROM pai_verdict WHERE arrival_id=?",
                Integer.class, arrival);
    }

    private int verdictCount(final String action) {
        return jdbc.queryForObject("SELECT count(*) FROM pai_verdict WHERE arrival_id=? AND action=?",
                Integer.class, arrival, action);
    }
}
