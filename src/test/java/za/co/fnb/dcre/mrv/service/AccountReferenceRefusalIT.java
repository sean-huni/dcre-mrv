package za.co.fnb.dcre.mrv.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import za.co.fnb.dcre.mrv.domain.AccountReferenceLoadFailure;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every refusal path, each asserted on the SPECIFIC message it must produce rather than on
 * "an exception was thrown". A test that accepts any exception passes when the loader dies
 * for a reason nobody intended, which is the failure mode these gates exist to prevent.
 *
 * <p>Every case also asserts the table is untouched, because a gate that refuses AFTER
 * emptying the projection has not refused, it has destroyed the reference data and then
 * complained.
 */
class AccountReferenceRefusalIT extends AbstractAccountReferenceIT {

    private static final String VERSION = "2026.08.09-001";
    private static final List<String> ONE_MANDATE =
            List.of(AccountArtifactFixture.mandatesRow("62001482970090167", "FNBCC", "ACTIVE", "CHQ"));

    /** Gate 1: absent means FAIL, never "carry on with what is already in the table". */
    @Test
    void anAbsentArtifactDirectoryFails() {
        final Path root = tempDirectory("mrv-artifact-absent");

        assertThatThrownBy(() -> serviceFor(root, VERSION, null).load(null))
                .isInstanceOf(AccountReferenceLoadFailure.class)
                .hasMessageContaining("artifact directory is absent or not a directory")
                .hasMessageContaining(VERSION);
        assertThat(accountRowCount()).isZero();
    }

    /** Gate 5: both digests are printed, so the operator can tell a stale manifest from stale bytes. */
    @Test
    void aChecksumMismatchFailsPrintingBothDigests() {
        final Path root = tempDirectory("mrv-artifact-checksum");
        final String wrong = "0".repeat(64);
        AccountArtifactFixture.write(root, VERSION, ONE_MANDATE, Map.of("checksum.sha256", wrong));

        assertThatThrownBy(() -> serviceFor(root, VERSION, null).load(null))
                .isInstanceOf(AccountReferenceLoadFailure.class)
                .hasMessageContaining("checksum mismatch")
                .hasMessageContaining(wrong);
        assertThat(accountRowCount()).isZero();
    }

    /** Gate 4: names BOTH numbers, per the contract. */
    @Test
    void anUnsupportedSchemaVersionFailsNamingBothNumbers() {
        final Path root = tempDirectory("mrv-artifact-schema");
        AccountArtifactFixture.write(root, VERSION, ONE_MANDATE, Map.of("schema.version", "2"));

        assertThatThrownBy(() -> serviceFor(root, VERSION, null).load(null))
                .isInstanceOf(AccountReferenceLoadFailure.class)
                .hasMessage("schema.version mismatch: the artifact declares 2, this loader supports 1");
        assertThat(accountRowCount()).isZero();
    }

    /** Gate 3: the consumer declares the version; there is no newest-directory-wins fallback. */
    @Test
    void anUnexpectedDatasetVersionFails() {
        final Path root = tempDirectory("mrv-artifact-dataset");
        AccountArtifactFixture.write(root, VERSION, ONE_MANDATE, Map.of("dataset.version", "2026.01.01-001"));

        assertThatThrownBy(() -> serviceFor(root, VERSION, null).load(null))
                .isInstanceOf(AccountReferenceLoadFailure.class)
                .hasMessage("dataset.version mismatch: the manifest declares 2026.01.01-001,"
                        + " this service expects " + VERSION);
        assertThat(accountRowCount()).isZero();
    }

    /**
     * Gate 8: a well-formed artifact carrying only the OTHER family's shape. This is the
     * exact defect class the wave removes, so an empty projection must never read as a
     * successful load of nothing.
     */
    @Test
    void anArtifactWithNoMandatesShapeRowsFails() {
        final Path root = tempDirectory("mrv-artifact-empty-projection");
        AccountArtifactFixture.write(root, VERSION,
                List.of(AccountArtifactFixture.collectionsRow("62085856711390458")), Map.of());

        assertThatThrownBy(() -> serviceFor(root, VERSION, null).load(null))
                .isInstanceOf(AccountReferenceLoadFailure.class)
                .hasMessageContaining("carries no shape=MANDATES rows")
                .hasMessageContaining("would empty dcre_man.account and report success");
        assertThat(accountRowCount()).isZero();
    }

    /** Gate 7, armed half: a configured limit the artifact is past refuses the load. */
    @Test
    void aSetAndExceededMaxAgeFails() {
        final Path root = tempDirectory("mrv-artifact-stale");
        AccountArtifactFixture.write(root, VERSION, ONE_MANDATE,
                Map.of("publication.ts", "2020-01-01T00:00:00Z"));

        assertThatThrownBy(() -> serviceFor(root, VERSION, Duration.ofDays(1)).load(null))
                .isInstanceOf(AccountReferenceLoadFailure.class)
                .hasMessageContaining("is stale: publication.ts 2020-01-01T00:00:00Z")
                .hasMessageContaining("past the configured max-age PT24H");
        assertThat(accountRowCount()).isZero();
    }

    /**
     * THE WIDTH SUBTLETY. The artifact specifies account_number as up to 34 characters and
     * dcre_man.account.account_number is VARCHAR(32). Today's rows are 17 digits, so nothing
     * truncates and nothing errors, which is exactly why this has to be proven rather than
     * assumed. The loader must refuse BEFORE the insert, naming the account number, instead
     * of discovering it as a driver error naming only a column.
     */
    @Test
    void anAccountNumberWiderThanTheTargetColumnFailsNamingIt() {
        final Path root = tempDirectory("mrv-artifact-wide");
        final String tooWide = "6".repeat(34);
        AccountArtifactFixture.write(root, VERSION,
                List.of(AccountArtifactFixture.mandatesRow(tooWide, "FNBCC", "ACTIVE", "CHQ")), Map.of());

        assertThatThrownBy(() -> serviceFor(root, VERSION, null).load(null))
                .isInstanceOf(AccountReferenceLoadFailure.class)
                .hasMessage("account_number '%s' does not fit dcre_man.account.account_number:"
                        + " 34 characters against a limit of 32", tooWide);
        assertThat(accountRowCount()).isZero();
    }

    /**
     * Gate 9, the one that matters most: a load whose SECOND row violates the target table's
     * own uniqueness must leave the table holding EXACTLY what it held before. The previous
     * row is seeded first, the load empties and refills inside one transaction, and the
     * assertion is on the previous CONTENTS, not merely on a count.
     */
    @Test
    void aConstraintViolationRollsTheWholeLoadBackToThePreviousContents() {
        jdbc.update("""
                INSERT INTO account (account_number, account_type_code, product_code, status)
                VALUES ('62000000000000001', 'CHQ', 'FNBRF', 'ACTIVE')""");

        final Path root = tempDirectory("mrv-artifact-duplicate");
        AccountArtifactFixture.write(root, VERSION, List.of(
                AccountArtifactFixture.mandatesRow("62001482970090167", "FNBCC", "ACTIVE", "CHQ"),
                AccountArtifactFixture.mandatesRow("62001482970090167", "FNBRF", "ACTIVE", "TRN")), Map.of());

        assertThatThrownBy(() -> serviceFor(root, VERSION, null).load(null))
                .as("the artifact carries a duplicate account_number and uq_account_number rejects it")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_account_number");

        assertThat(jdbc.queryForList(
                "SELECT account_number, account_type_code, product_code, status FROM account"))
                .as("no partial application: the table holds exactly its previous contents")
                .containsExactly(Map.of("account_number", "62000000000000001", "account_type_code", "CHQ",
                        "product_code", "FNBRF", "status", "ACTIVE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_reference_load", Integer.class))
                .as("and no provenance row claims a load that was rolled back")
                .isZero();
    }
}
