package za.co.fnb.dcre.mrv.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The projected row must fit the TARGET COLUMN, checked against that column's own declared
 * limit before a single INSERT is attempted.
 *
 * <p>Why this exists at all: the artifact's {@code account_number} is specified as up to 34
 * characters and {@code dcre_man.account.account_number} is {@code VARCHAR(32)}. Today's
 * fixture values are 17 digits so nothing is near the limit, and that is precisely the
 * condition under which the defect ships unnoticed. A loader that lets the database decide
 * is wrong in both directions: PostgreSQL rejects with a driver error naming a column and
 * no account, and a database that pads or truncates would corrupt an identifier silently,
 * which for an account number is unrecoverable.
 *
 * <p>The limits are READ FROM THE TARGET TABLE, never hardcoded here. Hardcoding 32 would
 * create a second home for one fact (the column definition being the first), and the copy
 * would go stale the day the fleet-wide widening lands without anything failing to say so.
 *
 * <p>Widening the column is deliberately NOT the fix available here: {@code account} is
 * created by the changelog that is byte-identical across ten M-services, so its width is a
 * coordinated fleet change and out of scope for this loader.
 */
public final class AccountWidthRule {

    private AccountWidthRule() {
    }

    /**
     * @param rows   the projection about to be inserted
     * @param limits column name to declared character limit, read off the target table
     * @throws AccountReferenceLoadFailure naming the offending account number, the column,
     *                                     the actual width and the limit it exceeds
     */
    public static void check(final List<AccountReferenceRow> rows, final Map<String, Integer> limits) {
        for (final AccountReferenceRow row : rows) {
            for (final Map.Entry<String, String> column : columnsOf(row).entrySet()) {
                checkColumn(row.accountNumber(), column.getKey(), column.getValue(), limits);
            }
        }
    }

    private static Map<String, String> columnsOf(final AccountReferenceRow row) {
        final Map<String, String> columns = new LinkedHashMap<>();
        columns.put("account_number", row.accountNumber());
        columns.put("account_type_code", row.accountTypeCode());
        columns.put("product_code", row.productCode());
        columns.put("status", row.status());
        return columns;
    }

    /**
     * A column whose limit could not be read is a HARD FAILURE, never a skip: an unreadable
     * limit is indistinguishable from a wide-enough one only if you decide not to look.
     */
    private static void checkColumn(final String accountNumber, final String column,
                                    final String value, final Map<String, Integer> limits) {
        final Integer limit = limits.get(column);
        if (limit == null) {
            throw new AccountReferenceLoadFailure(
                    "cannot read the declared width of dcre_man.account.%s, so the projection cannot be width-checked"
                            .formatted(column));
        }
        if (value != null && value.length() > limit) {
            throw new AccountReferenceLoadFailure(
                    "account_number '%s' does not fit dcre_man.account.%s: %d characters against a limit of %d"
                            .formatted(accountNumber, column, value.length(), limit));
        }
    }
}
