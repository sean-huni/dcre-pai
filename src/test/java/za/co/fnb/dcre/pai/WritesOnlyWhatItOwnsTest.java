package za.co.fnb.dcre.pai;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-107 repair 3: PAI writes only relations PAI owns.
 *
 * <p>It used to write {@code account}, a relation no payments service owns, with values
 * hardcoded from a toolkit sample including a balance of 999999999.99 chosen so that
 * downstream cap checks would pass. That is a write-ownership violation and no published
 * view fixes it: a context that cannot control writes to a relation does not own it. It
 * also closed a loop with PTV's now fail-closed account tier, where a rejection on one
 * arrival became a permanent pass on every later one.
 *
 * <p>The test is written against the SQL rather than against behaviour on purpose. A
 * behavioural test proves that today's code path does not write the master; this proves
 * that no code path can, which is the property that has to survive the next change. The
 * open account-model decision may move where PAI READS from, and nothing here constrains
 * that: only writes are asserted.
 */
class WritesOnlyWhatItOwnsTest {

    /** Relations PAI creates in its own shipped changelog, and may therefore write. */
    private static final List<String> OWNED = List.of("pai_verdict", "unknown_creditor");

    private static final Pattern WRITE_TARGET = Pattern.compile(
            "(?i)\\b(?:INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+([a-z_][a-z0-9_]*)");

    @Test
    void noShippedStatementWritesARelationPaiDoesNotOwn() throws Exception {
        List<String> written = writeTargetsUnder("src/main/java");
        assertThat(written)
                .as("every write PAI issues must name a relation its own changelog creates")
                .isSubsetOf(OWNED);
        // Control: the scan really finds writes, so the assertion above is a fact about
        // the targets and not about a regex that matched nothing.
        assertThat(written).as("control: PAI does issue writes").isNotEmpty();
    }

    @Test
    void theAccountMasterIsReadAndOnlyRead() throws Exception {
        String accountRepo = Files.readString(
                Path.of("src/main/java/za/co/fnb/dcre/pai/data/repo/AccountRepo.java"));
        assertThat(accountRepo)
                .as("the probe stays: PAI must still ask whether a creditor is known")
                .contains("SELECT count(*) > 0 FROM account");
        assertThat(accountRepo)
                .as("and it asks nothing else of a relation it does not own")
                .doesNotContain("INSERT INTO account");
    }

    /**
     * The relations PAI's own changelog creates. If a future changeset mints a relation
     * belonging to another service, this list grows and the write assertion above starts
     * permitting writes to it, so the two are checked against each other rather than one
     * being derived from the other.
     */
    @Test
    void theShippedChangelogCreatesExactlyTheOwnedRelations() throws Exception {
        List<String> created = createdTablesUnder("src/main/resources/db/changelog");
        assertThat(created)
                .as("PAI creates its verdict store and its own sighting relation, and no"
                        + " relation another service owns. Batch metadata is a raw-SQL"
                        + " platform artefact and is deliberately not in this walk's reach")
                .containsExactlyInAnyOrderElementsOf(OWNED);
    }

    private static List<String> writeTargetsUnder(final String root) throws Exception {
        try (var paths = Files.walk(Path.of(root))) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> matches(WRITE_TARGET, stripComments(read(path))).stream())
                    .distinct()
                    .sorted()
                    .toList();
        }
    }

    private static List<String> createdTablesUnder(final String root) throws Exception {
        Pattern createTable = Pattern.compile("<createTable\\s+tableName=\"([a-z_][a-z0-9_]*)\"");
        try (var paths = Files.walk(Path.of(root))) {
            return paths.filter(path -> path.toString().endsWith(".xml"))
                    .flatMap(path -> matches(createTable, read(path)).stream())
                    .distinct()
                    .sorted()
                    .toList();
        }
    }

    private static List<String> matches(final Pattern pattern, final String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.results().map(result -> result.group(1).toLowerCase()).toList();
    }

    private static String read(final Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            throw new IllegalStateException(path.toString(), e);
        }
    }

    /** Java comments only: this walk visits .java files exclusively. */
    private static String stripComments(final String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }
}
