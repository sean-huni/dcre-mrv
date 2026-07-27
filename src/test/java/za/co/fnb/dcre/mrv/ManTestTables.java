package za.co.fnb.dcre.mrv;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * BDD seeding helpers for the MRR-owned request spine (mandate_request_header /
 * mandate_request_entry). account / account_type come from MRV's own Liquibase core
 * bootstrap (000-man-core-bootstrap.xml).
 *
 * <p>The spine DDL is NOT hand-written here any more: {@link ManReadSideSchema} runs the
 * owner's own changesets, so these inserts face the REAL column set. That is why they now
 * supply msg_id / created_ts / business_date / layout_version and the money pair
 * (max_collection_amount_raw + max_collection_amount), all NOT NULL on the real spine and
 * all absent from the hand copy this replaces. spine_state defaults to RECEIVED, the state
 * MRR leaves and MRV transitions. The response-leg seeding is {@link ManEffectiveStatusTables}.
 */
public final class ManTestTables {

    private ManTestTables() {
    }

    /**
     * A file header. msg_id is VARCHAR(35), so the arrival UUID is abbreviated rather than
     * inlined whole; created_ts / business_date / layout_version are fixed because MRV reads
     * none of them and a fixture must stay deterministic.
     */
    public static void insertHeader(final JdbcTemplate jdbc, final UUID arrival, final String client,
                                    final int entryCount) {
        final String msgId = "MSG-" + arrival.toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO mandate_request_header (arrival_id, msg_id_raw, msg_id, created_ts,
                    entry_count, destination_id, business_date, client_token, layout_version)
                VALUES (?,?,?, '20260726120000', ?,?, '20260726', ?, 1)""",
                arrival, msgId, msgId, entryCount, client, client);
    }

    public static void insertEntry(final JdbcTemplate jdbc, final UUID arrival, final int sequence,
                                   final String action, final String ref, final String contract,
                                   final String debtorAccount, final boolean dupInFile) {
        insertEntry(jdbc, arrival, sequence, action, ref, contract, debtorAccount, "7300000001", dupInFile);
    }

    /**
     * As above, naming the creditor account too: the R-23/A-29 fallback key when contract_ref
     * is blank. The money pair is a fixed R100.00 in both its raw VARCHAR(15) layout form and
     * the DECIMAL(18,2) column: a decimal literal at the declared scale, never a Java double.
     */
    public static void insertEntry(final JdbcTemplate jdbc, final UUID arrival, final int sequence,
                                   final String action, final String ref, final String contract,
                                   final String debtorAccount, final String creditorAccount,
                                   final boolean dupInFile) {
        jdbc.update("""
                INSERT INTO mandate_request_entry (arrival_id, sequence, record_type, action_code,
                    mandate_ref, contract_ref, debtor_account, creditor_account, currency,
                    max_collection_amount_raw, max_collection_amount, dup_in_file)
                VALUES (?,?, 'MD', ?,?,?,?,?, 'ZAR', '000000000010000', 100.00, ?)""",
                arrival, sequence, action, ref, contract, debtorAccount, creditorAccount, dupInFile);
    }

    /** Prior-arrival spine row already at a chosen state (for the CREATE-absence / AMEND-known checks). */
    public static void insertPriorEntry(final JdbcTemplate jdbc, final UUID arrival, final int sequence,
                                        final String action, final String ref, final String spineState) {
        jdbc.update("""
                INSERT INTO mandate_request_entry (arrival_id, sequence, record_type, action_code,
                    mandate_ref, contract_ref, debtor_account, currency,
                    max_collection_amount_raw, max_collection_amount, spine_state)
                VALUES (?,?, 'MD', ?,?, 'CTR0', '6200000021', 'ZAR', '000000000010000', 100.00, ?)""",
                arrival, sequence, action, ref, spineState);
    }

    public static void seedAccount(final JdbcTemplate jdbc, final String number, final String typeCode) {
        jdbc.update("""
                INSERT INTO account (account_number, account_type_code, status)
                VALUES (?,?, 'ACTIVE')
                ON CONFLICT (account_number) DO NOTHING""", number, typeCode);
    }
}
