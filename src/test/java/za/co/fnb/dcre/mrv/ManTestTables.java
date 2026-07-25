package za.co.fnb.dcre.mrv;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * BDD seeding helpers. account / account_type come from MRV's own Liquibase core
 * bootstrap (000-man-core-bootstrap.xml), so this helper only stands up the
 * MRR-owned spine tables (mandate_request_header / mandate_request_entry, NOT in
 * MRV's changelog, exactly as CtvTestTables stands up the crr-owned tx spine) and
 * seeds account + spine rows. spine_state defaults to RECEIVED, the state MRR
 * leaves and MRV transitions. The derived view stack MRV's 1:1-live admission
 * check reads is {@link ManEffectiveStatusTables}.
 */
public final class ManTestTables {

    private ManTestTables() {
    }

    public static void createSpine(final JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS mandate_request_header (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    version BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    arrival_id UUID NOT NULL UNIQUE,
                    client_token VARCHAR(16),
                    destination_id VARCHAR(16) NOT NULL,
                    entry_count INT NOT NULL)""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS mandate_request_entry (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    version BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    arrival_id UUID NOT NULL,
                    sequence INT NOT NULL,
                    record_type VARCHAR(2) NOT NULL,
                    action_code VARCHAR(16) NOT NULL,
                    mandate_ref VARCHAR(35) NOT NULL,
                    contract_ref VARCHAR(14),
                    debtor_account VARCHAR(32),
                    creditor_account VARCHAR(32),
                    currency VARCHAR(3) NOT NULL,
                    max_collection_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
                    start_date VARCHAR(8),
                    expiry_date VARCHAR(8),
                    mndt_req_id VARCHAR(35) UNIQUE,
                    dup_in_file BOOLEAN NOT NULL DEFAULT false,
                    spine_state VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
                    UNIQUE (arrival_id, sequence))""");
    }

    public static void insertHeader(final JdbcTemplate jdbc, final UUID arrival, final String client,
                                    final int entryCount) {
        jdbc.update("""
                INSERT INTO mandate_request_header (arrival_id, client_token, destination_id, entry_count)
                VALUES (?,?,?,?)""", arrival, client, client, entryCount);
    }

    public static void insertEntry(final JdbcTemplate jdbc, final UUID arrival, final int sequence,
                                   final String action, final String ref, final String contract,
                                   final String debtorAccount, final boolean dupInFile) {
        insertEntry(jdbc, arrival, sequence, action, ref, contract, debtorAccount, "7300000001", dupInFile);
    }

    /** As above, naming the creditor account too: the R-23/A-29 fallback key when contract_ref is blank. */
    public static void insertEntry(final JdbcTemplate jdbc, final UUID arrival, final int sequence,
                                   final String action, final String ref, final String contract,
                                   final String debtorAccount, final String creditorAccount,
                                   final boolean dupInFile) {
        jdbc.update("""
                INSERT INTO mandate_request_entry (arrival_id, sequence, record_type, action_code,
                    mandate_ref, contract_ref, debtor_account, creditor_account, currency, dup_in_file)
                VALUES (?,?, 'MD', ?,?,?,?,?, 'ZAR', ?)""",
                arrival, sequence, action, ref, contract, debtorAccount, creditorAccount, dupInFile);
    }

    /** Prior-arrival spine row already at a chosen state (for the CREATE-absence / AMEND-known checks). */
    public static void insertPriorEntry(final JdbcTemplate jdbc, final UUID arrival, final int sequence,
                                        final String action, final String ref, final String spineState) {
        jdbc.update("""
                INSERT INTO mandate_request_entry (arrival_id, sequence, record_type, action_code,
                    mandate_ref, contract_ref, debtor_account, currency, spine_state)
                VALUES (?,?, 'MD', ?,?, 'CTR0', '6200000021', 'ZAR', ?)""",
                arrival, sequence, action, ref, spineState);
    }

    public static void seedAccount(final JdbcTemplate jdbc, final String number, final String typeCode) {
        jdbc.update("""
                INSERT INTO account (account_number, account_type_code, status)
                VALUES (?,?, 'ACTIVE')
                ON CONFLICT (account_number) DO NOTHING""", number, typeCode);
    }
}
