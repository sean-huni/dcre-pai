package za.co.fnb.dcre.pai.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Glue for the account init feature; scenario-scoped (fresh instance per scenario). */
public class PaiSteps {

    @Autowired
    Job paiJob;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JdbcTemplate jdbc;

    private UUID arrival;
    private String prefix;
    private int nextSequence;
    private final Map<String, String> accountByLabel = new HashMap<>();
    private long accountsBeforeRun;
    private JobExecution lastRun;

    @Before
    public void newScenario() {
        arrival = UUID.randomUUID();
        prefix = "62" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    @Given("creditor accounts {string} already exist on file")
    public void creditorAccountsExist(String labels) {
        for (String label : labels.split(",\\s*")) {
            jdbc.update("INSERT INTO account (product_code, account_number, app_no, acc_type,"
                    + " branch_code, balance, process_status, status, ucn, client_id)"
                    + " VALUES ('FNBRF', ?, ?, 'CACC', '250205', 19317.67, 'ACTIVE', 'AAUT',"
                    + " '100000000001', 2)", account(label), account(label));
        }
    }

    @Given("an arrival with entries crediting accounts {string}")
    @Given("an arrival with entries crediting unknown accounts {string}")
    public void arrivalCreditingAccounts(String labels) {
        jdbc.update("INSERT INTO tx_header (arrival_id, msg_id_raw, msg_id, created_ts, tx_count,"
                + " initg_pty, business_date, layout_version)"
                + " VALUES (?, ?, ?, '20260712120000', 9, 'FNB', '20260712', 2)",
                arrival, "MSG" + prefix, "MSG" + prefix);
        for (String label : labels.split(",\\s*")) {
            int seq = ++nextSequence;
            jdbc.update("INSERT INTO tx_entry (arrival_id, sequence, record_type, e2e_raw, e2e,"
                    + " creditor_account, currency, amount_raw, amount)"
                    + " VALUES (?, ?, '02', ?, ?, ?, 'ZAR', '000000010000', 100.00)",
                    arrival, seq, "E2E" + prefix + seq, "E2E" + prefix + seq, account(label));
        }
        accountsBeforeRun = jdbc.queryForObject("SELECT count(*) FROM account", Long.class);
    }

    @Given("the PAI job has already run for the arrival")
    public void jobAlreadyRan() throws Exception {
        run(null);
        assertEquals(BatchStatus.COMPLETED, lastRun.getStatus(), "the priming run must complete");
    }

    @When("the PAI job runs for the arrival")
    public void jobRuns() throws Exception {
        run(null);
    }

    @When("the PAI job reruns for the arrival")
    public void jobReruns() throws Exception {
        run("2");
    }

    @Then("the PAI job completes")
    public void jobCompletes() {
        assertEquals(BatchStatus.COMPLETED, lastRun.getStatus());
    }

    @Then("the arrival has exactly {int} {string} and {int} {string} verdicts")
    public void verdictCounts(int firstCount, String firstAction, int secondCount, String secondAction) {
        assertEquals(firstCount, verdictCount(firstAction), firstAction + " verdict count");
        assertEquals(secondCount, verdictCount(secondAction), secondAction + " verdict count");
        assertEquals(firstCount + secondCount, jdbc.queryForObject(
                "SELECT count(*) FROM pai_verdict WHERE arrival_id=?", Integer.class, arrival),
                "one verdict per spine transaction, nothing else");
    }

    @Then("no new accounts were minted")
    public void noNewAccountsMinted() {
        assertEquals(accountsBeforeRun, (long) jdbc.queryForObject(
                "SELECT count(*) FROM account", Long.class), "all-exist run mints nothing (A-7)");
    }

    @Then("exactly one account row exists for each of {string}")
    public void exactlyOneAccountRowEach(String labels) {
        for (String label : labels.split(",\\s*")) {
            assertEquals(1, (int) jdbc.queryForObject(
                    "SELECT count(*) FROM account WHERE account_number=?", Integer.class, account(label)),
                    "exactly one account row for " + label + " (R-05/R-11)");
        }
    }

    @Then("the minted account {string} carries the synthetic ENDO defaults")
    public void mintedAccountCarriesDefaults(String label) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT product_code, acc_type, balance, process_status, status, branch_code,"
                        + " ucn, client_id, app_no, max_credit_limit FROM account WHERE account_number=?",
                account(label));
        assertEquals("FNBRF", row.get("product_code"), "product_code");
        assertEquals("CACC", row.get("acc_type"), "acc_type");
        assertEquals(0, new BigDecimal("999999999.99").compareTo(new BigDecimal(String.valueOf(row.get("balance")))),
                () -> "balance (generous so cap checks pass post-init), actual=" + row.get("balance"));
        assertEquals("ACTIVE", row.get("process_status"), "process_status");
        assertEquals("AAUT", row.get("status"), "status");
        assertEquals("250205", row.get("branch_code"), "branch_code");
        assertEquals("100000000000", String.valueOf(row.get("ucn")), "ucn");
        assertEquals(2L, ((Number) row.get("client_id")).longValue(), "client_id");
        assertEquals(account(label), row.get("app_no"), "app_no mirrors account_number");
        assertNull(row.get("max_credit_limit"), "max_credit_limit stays NULL (FNBRF caps via balance)");
    }

    private String account(String label) {
        return accountByLabel.computeIfAbsent(label, l -> prefix + l);
    }

    private int verdictCount(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM pai_verdict WHERE arrival_id=? AND action=?",
                Integer.class, arrival, action);
    }

    private void run(String rerun) throws Exception {
        JobParametersBuilder builder = new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true);
        if (rerun != null) {
            builder.addString("rerun", rerun, true);
        }
        lastRun = jobOperator.start(paiJob, builder.toJobParameters());
    }
}
