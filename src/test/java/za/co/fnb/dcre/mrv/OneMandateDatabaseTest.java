package za.co.fnb.dcre.mrv;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import za.co.fnb.dcre.mrv.data.repo.ManReferenceSnapshotDao;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-107 repair 2: the mandates account master lives in dcre_man, MRV's ONE database,
 * and MRV reads it there.
 *
 * <p>This is a guard against a half-completed migration, which is a worse state than
 * either end of it. MIT, the mandates account WRITER, mints into {@code dcre_man.account}.
 * A partial move of the READER to a second database made MIT report a successful mint and
 * MRV answer {@code FAIL_ACCOUNT_NOT_FOUND} for that same account seconds later. Nothing
 * errored, no suite went red, and the two halves were each internally consistent: the
 * writer wrote where it always had, the reader read where it had just been pointed, and
 * only the pair was wrong. Whether the account master eventually moves out of dcre_man is
 * an open decision; moving one END of a writer/reader pair is not a step toward it.
 *
 * <p>Modelled on PTV's PayFlowOnlyTest. A literal scan alone can be satisfied by deleting
 * literals rather than the coupling, so the scans below sit beside STRUCTURAL assertions
 * read off the classes themselves: the application's {@code @Import} list and the reference
 * DAO's own declared methods. Every assertion carries a positive control, because an empty
 * result from a walk that found no files is indistinguishable from a clean tree.
 */
class OneMandateDatabaseTest {

    /**
     * Names any second account store would have to use to be reachable at all.
     *
     * <p>TWO KINDS OF TOKEN, AND BOTH EARN THEIR PLACE.
     *
     * <p>The SHAPE tokens name the way a second store would have to arrive whatever it was
     * called: a second set of datasource keys, a second datasource config, a second reference
     * DAO. That shape is what recurs, and a literal scan alone can always be satisfied by
     * renaming rather than by removing the coupling, which is why the structural assertions
     * below sit beside these.
     *
     * <p>The RETIRED-NAME tokens are the {@code dcre_acs} database and its two published
     * account views. They name a store that exists in no environment, and the tempting
     * argument is that guarding a name nothing can reach guards nothing. It is the wrong
     * argument HERE, and the distinction is worth stating because this project applies the
     * opposite rule to Liquibase guards a few files away. A {@code MARK_RAN} precondition
     * that cannot fire is not free: it silently SKIPS the change on the day its assumption
     * stops holding, converting a loud failure into a quietly missing table. A forbidden
     * LITERAL has no such failure mode. It costs one string and it can only ever do one
     * thing, which is fail the build the moment somebody reintroduces the name.
     *
     * <p>And reintroduction is the live risk, not a hypothetical one: this exact design was
     * built on 2026-08-08 and reversed on 2026-08-09, so the shape is one revert away and
     * it is documented at length in several READMEs where a reader could mistake the
     * description for the design. The same reasoning keeps {@code acs} out of
     * {@code ALLOWED_shared} in dcre-infra's verify-topology.sh, where a resurrected
     * directory is reported UNDECLARED, and keeps a stale-{@code dcre_acs} case in that
     * repo's database-roster harness. A tripwire is only worth having before the thing it
     * catches happens.</p>
     */
    private static final String[] SECOND_STORE_TOKENS = {
            "accounts-db-url", "accounts-db-user", "accounts-db-password",
            "DCRE_MRV_ACCOUNTS_DB_URL", "AccountsDatasourceConfig", "AccountReferenceDao",
            "dcre_acs", "acc_mrv_view", "acc_type_mrv_view"};

    @Test
    void noShippedSourceReachesASecondAccountStore() throws Exception {
        assertThat(offendingSources(SECOND_STORE_TOKENS))
                .as("the mandates account master is read where the mandates writer writes it")
                .isEmpty();
        // Control: the walk really reads these sources, so the emptiness above is a fact
        // about the tree and not about a walk that visited nothing.
        assertThat(offendingSources("ManReferenceSnapshotDao"))
                .as("control: the source walk finds a token that IS present")
                .isNotEmpty();
    }

    @Test
    void noShippedResourceWiresASecondDatasourceOrView() throws Exception {
        assertThat(offendingResources(SECOND_STORE_TOKENS))
                .as("no yml key and no changeset may point any account read at a second database")
                .isEmpty();
        assertThat(offendingResources("dcre_man"))
                .as("control: the resource walk finds a token that IS present")
                .isNotEmpty();
    }

    /**
     * The APPLICATION's imported configuration, read off the annotation rather than the
     * file text. An extra datasource config reintroduced by {@code @Import} is a live
     * second connection in every Spring context, whatever the comments around it say.
     */
    @Test
    void theApplicationImportsExactlyThePlatformDatasourceConfigs() {
        List<String> imported = Arrays.stream(MrvApplication.class.getAnnotation(Import.class).value())
                .map(Class::getSimpleName)
                .toList();
        assertThat(imported)
                .as("one business datasource: dcre_man")
                .containsExactlyInAnyOrder("JdbcConfig", "BatchJdbcConfig", "HeartbeatDatasourceConfig");
    }

    /**
     * The reference DAO still OWNS the account reads. Moving them to a sibling class is
     * how the split was performed, so their absence from this type is the signature of it,
     * and it survives any amount of comment rewording.
     */
    @Test
    void theReferenceDaoStillReadsTheAccountMasterItself() {
        List<String> methods = Arrays.stream(ManReferenceSnapshotDao.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();
        assertThat(methods)
                .as("the account master and the mandate spine are read from ONE snapshot"
                        + " of ONE database, by one DAO")
                .contains("snapshotTimestamp", "accountsByNumber", "mandatesAllowedByType", "knownRefs");
    }

    /**
     * The account read must name the bare relation, not a published view of another
     * database. The DAO's SQL is assembled in Java, so this reads the source.
     */
    @Test
    void theAccountReadNamesTheDcreManRelation() throws Exception {
        String dao = Files.readString(
                Path.of("src/main/java/za/co/fnb/dcre/mrv/data/repo/ManReferenceSnapshotDao.java"));
        assertThat(dao)
                .as("MRV reads dcre_man.account and dcre_man.account_type directly")
                .contains("FROM account AS OF SYSTEM TIME")
                .contains("FROM account_type AS OF SYSTEM TIME");
    }

    /**
     * The shared-core changelog still stands the account master up. MRV is one of the ten
     * M-services that migrate the single dcre_man, and this file is byte-identical across
     * all ten by design: dropping the account changesets HERE alone would leave nine
     * services creating a relation this one had decided did not belong.
     */
    @Test
    void theSharedCoreChangelogStillCreatesTheAccountMaster() throws Exception {
        String bootstrap = Files.readString(
                Path.of("src/main/resources/db/changelog/2026/07/000-man-core-bootstrap.xml"));
        assertThat(bootstrap)
                .as("dcre_man holds the mandates account master, so this changelog creates it")
                .contains("<createTable tableName=\"account\">")
                .contains("<createTable tableName=\"account_type\">");
    }

    private static List<Path> offendingSources(String... tokens) throws Exception {
        return offendersUnder("src/main/java", path -> path.toString().endsWith(".java"), tokens);
    }

    private static List<Path> offendingResources(String... tokens) throws Exception {
        return offendersUnder("src/main/resources", path -> true, tokens);
    }

    private static List<Path> offendersUnder(final String root, final Predicate<Path> selector,
                                             final String... tokens) throws Exception {
        try (var paths = Files.walk(Path.of(root))) {
            return paths.filter(Files::isRegularFile)
                    .filter(selector)
                    .filter(path -> containsAny(path, tokens))
                    .toList();
        }
    }

    private static boolean containsAny(final Path path, final String... tokens) {
        try {
            String text = stripComments(path, Files.readString(path));
            return Arrays.stream(tokens).anyMatch(text::contains);
        } catch (Exception e) {
            throw new IllegalStateException(path.toString(), e);
        }
    }

    /**
     * Comments out, by FILE TYPE. Prose that EXPLAINS why a second store was rejected is
     * worth keeping, and a scan that counted those mentions would push the next author to
     * delete the explanation instead of the coupling. The structural assertions above are
     * what make that safe: no comment can satisfy an {@code @Import} list.
     *
     * <p>The type check is not tidiness. Applying Java's {@code //} rule to a yml file
     * truncates every line at the {@code //} of a JDBC URL, so the primary datasource
     * line vanishes from the scan. That is exactly what happened while writing this test,
     * and the only reason it was caught is that the control assertion for "dcre_man IS
     * present" went red. A scanner that silently reads less than it claims turns every
     * absence assertion around it into a pass for the wrong reason.
     */
    private static String stripComments(final Path path, final String text) {
        String name = path.getFileName().toString();
        if (name.endsWith(".java")) {
            return text.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
        }
        if (name.endsWith(".xml")) {
            return text.replaceAll("(?s)<!--.*?-->", " ");
        }
        if (name.endsWith(".yml")) {
            return text.replaceAll("(?m)^\\s*#.*$", " ");
        }
        return text;
    }
}
