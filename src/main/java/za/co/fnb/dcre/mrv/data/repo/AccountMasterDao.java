package za.co.fnb.dcre.mrv.data.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import za.co.fnb.dcre.mrv.domain.AccountReferenceRow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes to the EXISTING {@code dcre_man.account} relation created by
 * {@code 000-man-core-bootstrap.xml}, and reads that relation's declared column widths.
 *
 * <p>THIS DAO DOES NOT TOUCH {@code account_type}. That table is a CLOSED VOCABULARY of five
 * codes seeded by Liquibase, with SAV the only code disallowing mandates, and it is not part
 * of the reference artifact at all. The artifact's {@code account_type_code} column names a
 * member of that vocabulary; it does not define one. Saying so here is not padding: "the
 * loader owns account reference data" is the natural reading of this class's name, and it is
 * the wrong one.
 */
@Component
public class AccountMasterDao {

    private final JdbcTemplate jdbc;

    public AccountMasterDao(final JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Declared character limits of {@code account}, read from the catalogue so the width
     * guard measures the column that will actually receive the value. Columns with no
     * character limit (uuid, timestamptz) are absent from the map rather than zero.
     */
    public Map<String, Integer> columnLimits() {
        final Map<String, Integer> limits = new HashMap<>();
        jdbc.query("""
                SELECT column_name, character_maximum_length
                FROM information_schema.columns
                WHERE table_catalog = current_database()
                  AND table_name = 'account'
                  AND character_maximum_length IS NOT NULL""",
                rs -> {
                    limits.put(rs.getString("column_name"), rs.getInt("character_maximum_length"));
                });
        return limits;
    }

    /** Clears the materialised projection; the caller holds the transaction. */
    public int deleteAll() {
        return jdbc.update("DELETE FROM account WHERE 1 = 1");
    }

    /**
     * Inserts the whole projection. A plain INSERT on purpose: no ON CONFLICT, no per-row
     * skip. The table was emptied in this same transaction, so a unique violation here means
     * the ARTIFACT carries a duplicate account number, and the correct answer to that is to
     * refuse the whole load rather than to quietly keep whichever row arrived first.
     */
    public void insertAll(final List<AccountReferenceRow> rows) {
        jdbc.batchUpdate("""
                INSERT INTO account (account_number, account_type_code, product_code, status)
                VALUES (?, ?, ?, ?)""",
                rows.stream()
                        .map(row -> new Object[]{row.accountNumber(), row.accountTypeCode(),
                                row.productCode(), row.status()})
                        .toList());
    }
}
