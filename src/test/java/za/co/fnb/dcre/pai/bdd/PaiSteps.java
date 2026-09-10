package za.co.fnb.dcre.pai.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.HashMap;
import java.util.List;
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

    /**
     * The ownership assertion, on EVERY scenario. PAI reads the account master and
     * writes nothing to it, so the row count it saw before the run is the row count
     * afterwards, whether the run found everything, nothing, or a mixture.
     */
    @Then("the account master is unchanged")
    public void theAccountMasterIsUnchanged() {
        assertEquals(accountsBeforeRun, (long) jdbc.queryForObject(
                "SELECT count(*) FROM account", Long.class),
                "PAI does not own the account master and writes no row into it (SCRUM-107)");
    }

    @Then("no creditor was recorded as unknown")
    public void noCreditorRecordedAsUnknown() {
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT count(*) FROM unknown_creditor WHERE arrival_id=?", Integer.class, arrival),
                "an all-exist run records no sighting (A-7)");
    }

    @Then("exactly one unknown-creditor record exists for each of {string}")
    public void exactlyOneUnknownCreditorRecordEach(String labels) {
        for (String label : labels.split(",\\s*")) {
            assertEquals(1, (int) jdbc.queryForObject(
                    "SELECT count(*) FROM unknown_creditor WHERE account_number=?",
                    Integer.class, account(label)),
                    "exactly one sighting for " + label + ", insert-once on the number (R-05/R-11)");
            // Control for the assertion above and for "the account master is unchanged":
            // the label really is absent from the master, so the sighting is the right
            // branch having run rather than a coincidence of counting.
            assertEquals(0, (int) jdbc.queryForObject(
                    "SELECT count(*) FROM account WHERE account_number=?", Integer.class, account(label)),
                    "control: " + label + " is genuinely absent from the master");
        }
    }

    @Then("the unknown-creditor record for {string} names this arrival")
    public void theUnknownCreditorRecordNamesThisArrival(String label) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT account_number, arrival_id FROM unknown_creditor WHERE account_number=?",
                account(label));
        assertEquals(account(label), row.get("account_number"), "account_number");
        assertEquals(arrival, row.get("arrival_id"), "the arrival that first observed the absence");
        // The row carries the sighting and NOTHING invented: no product, no balance, no
        // branch. The hardcoded toolkit-sample values PAI used to mint were what let a
        // downstream stage disarm PTV's cap tier one account at a time.
        assertEquals(List.of(), jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name='unknown_creditor'"
                        + " AND column_name IN ('balance','product_code','max_credit_limit','status')",
                String.class),
                "the sighting relation carries no invented account attributes");
        // Control: the same query DOES see this table's real columns.
        assertEquals(List.of("account_number"), jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name='unknown_creditor'"
                        + " AND column_name = 'account_number'", String.class),
                "control: the schema read reaches unknown_creditor");
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
