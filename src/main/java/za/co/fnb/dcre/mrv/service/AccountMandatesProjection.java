package za.co.fnb.dcre.mrv.service;

import za.co.fnb.dcre.mrv.domain.AccountReferenceLoadFailure;
import za.co.fnb.dcre.mrv.domain.AccountReferenceRow;

import java.util.List;
import java.util.Map;

/**
 * Artifact rows to {@code dcre_man.account} rows. Mapping lives in the service layer, and
 * this is that mapping.
 *
 * <p>Only {@code shape=MANDATES} is taken. The ten {@code shape=COLLECTIONS} rows are a
 * DIFFERENT projection of a different family and are not loaded here, not even with nulls:
 * a collections row has no account type, and admitting one would mean relaxing the NOT NULL
 * that makes this table worth materialising.
 *
 * <p>The three columns {@code dcre_man.account} declares NOT NULL are required HERE, before
 * the insert, so an absent value is reported as an absent value naming the account rather
 * than surfacing as a driver constraint error naming only a column.
 */
final class AccountMandatesProjection {

    static final String SHAPE = "MANDATES";

    private AccountMandatesProjection() {
    }

    static List<AccountReferenceRow> project(final List<Map<String, String>> artifactRows,
                                             final String datasetVersion) {
        final List<AccountReferenceRow> projected = artifactRows.stream()
                .filter(row -> SHAPE.equals(row.get("shape")))
                .map(AccountMandatesProjection::toAccount)
                .toList();
        if (projected.isEmpty()) {
            throw new AccountReferenceLoadFailure(
                    ("account reference dataset %s carries no shape=%s rows, so this load would empty"
                            + " dcre_man.account and report success").formatted(datasetVersion, SHAPE));
        }
        return projected;
    }

    private static AccountReferenceRow toAccount(final Map<String, String> row) {
        final String accountNumber = required(row, "account_number", row.get("account_number"));
        return new AccountReferenceRow(accountNumber,
                required(row, "account_type_code", accountNumber),
                blankToNull(row.get("product_code")),
                required(row, "status", accountNumber));
    }

    private static String required(final Map<String, String> row, final String column, final String account) {
        final String value = row.get(column);
        if (value == null || value.isBlank()) {
            throw new AccountReferenceLoadFailure(
                    "shape=%s row for account_number '%s' has no %s, which dcre_man.account declares NOT NULL"
                            .formatted(SHAPE, account, column));
        }
        return value;
    }

    /** Empty cell means ABSENT; {@code product_code} is the one nullable target column. */
    private static String blankToNull(final String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
