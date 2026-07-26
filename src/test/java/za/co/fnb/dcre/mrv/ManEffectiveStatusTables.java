package za.co.fnb.dcre.mrv;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Stands up the MRG-owned derived view stack that MRV's 1:1-live admission check
 * reads: the three response-leg tables, {@code mandate_override}, the four views
 * ({@code mnd_*_pick}, {@code mnd_ext_status}, {@code mandate_override_pick},
 * {@code mandate_effective_status}). MRV READS these relations and owns none of
 * them, so they are absent from MRV's changelog, exactly as the MRR-owned spine
 * is (see {@link ManTestTables}). This is the same test-harness pattern, applied
 * to the cross-service read the response-leg refactor introduced.
 *
 * <p>SOURCE OF TRUTH is MRG's changelog:
 * {@code mrg/src/main/resources/db/changelog/2026/07/004-man-views.xml} and
 * {@code 005-man-effective.xml}. The view bodies below are copied verbatim from
 * those changesets; when MRG's definitions change this copy must follow.
 */
public final class ManEffectiveStatusTables {

    private ManEffectiveStatusTables() {
    }

    /** One response-leg table per leg; only the columns the pick views read. */
    private static final String RESP_DDL = """
            CREATE TABLE IF NOT EXISTS man_%s_resp (
                id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                response_file VARCHAR(128) NOT NULL,
                orgnl_msg_id VARCHAR(35) NOT NULL,
                mndt_id VARCHAR(35) NOT NULL,
                mndt_req_id VARCHAR(35) NOT NULL,
                e2e VARCHAR(35),
                status VARCHAR(8) NOT NULL,
                reason VARCHAR(8),
                version BIGINT NOT NULL DEFAULT 0,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                CONSTRAINT uq_man_%s_resp_file_mndt_req UNIQUE (response_file, mndt_req_id))""";

    private static final String PICK_DDL = """
            CREATE OR REPLACE VIEW mnd_%s_pick AS
            SELECT u.mndt_req_id, u.mndt_id, u.status, u.reason
            FROM (SELECT r.mndt_req_id, r.mndt_id, r.status, r.reason,
                         row_number() OVER (PARTITION BY r.mndt_req_id
                                            ORDER BY r.created_at DESC,
                                                     r.response_file DESC) AS pick_rank
                  FROM man_%s_resp r) AS u
            WHERE u.pick_rank = 1""";

    public static void createViewStack(final JdbcTemplate jdbc) {
        for (final String leg : new String[]{"isr", "sbsr", "pbsr"}) {
            jdbc.execute(RESP_DDL.formatted(leg, leg));
            jdbc.execute(PICK_DDL.formatted(leg, leg));
        }
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS mandate_override (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    mandate_ref VARCHAR(35) NOT NULL,
                    state VARCHAR(16) NOT NULL,
                    reason VARCHAR(8),
                    source VARCHAR(32) NOT NULL,
                    effective_from TIMESTAMPTZ NOT NULL DEFAULT now(),
                    version BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    CONSTRAINT uq_mandate_override_ref_source UNIQUE (mandate_ref, source))""");
        jdbc.execute("""
                CREATE OR REPLACE VIEW mandate_override_pick AS
                SELECT u.mandate_ref, u.state, u.reason
                FROM (SELECT o.mandate_ref, o.state, o.reason,
                             row_number() OVER (PARTITION BY o.mandate_ref
                                                ORDER BY o.effective_from DESC,
                                                         o.source ASC) AS pick_rank
                      FROM mandate_override o) AS u
                WHERE u.pick_rank = 1""");
        jdbc.execute("""
                CREATE OR REPLACE VIEW mnd_ext_status AS
                SELECT h.client_token AS client, e.mandate_ref, e.mndt_req_id, e.contract_ref,
                       e.debtor_account, e.creditor_account, e.max_collection_amount,
                       e.start_date, e.expiry_date,
                       COALESCE(pp.status, sp.status, ip.status,
                                CASE WHEN v.outcome = 'PASS' THEN 'MRV_PASS'
                                     ELSE v.outcome END, 'PDNG')         AS raw_status,
                       COALESCE(pp.reason, sp.reason, ip.reason)         AS reason,
                       CASE WHEN pp.status IS NOT NULL THEN 4
                            WHEN sp.status IS NOT NULL THEN 3
                            WHEN ip.status IS NOT NULL THEN 2
                            ELSE 1 END                                   AS stage_rank
                FROM mandate_request_entry e
                JOIN mandate_request_header h ON h.arrival_id = e.arrival_id
                LEFT JOIN man_validation_log v ON v.arrival_id = e.arrival_id
                                              AND v.sequence = e.sequence
                LEFT JOIN mnd_isr_pick  ip ON ip.mndt_req_id = e.mndt_req_id
                LEFT JOIN mnd_sbsr_pick sp ON sp.mndt_req_id = e.mndt_req_id
                LEFT JOIN mnd_pbsr_pick pp ON pp.mndt_req_id = e.mndt_req_id""");
        jdbc.execute("""
                CREATE OR REPLACE VIEW mandate_effective_status AS
                SELECT x.client, x.mandate_ref, x.mndt_req_id, x.contract_ref, x.debtor_account,
                       x.creditor_account, x.max_collection_amount, x.start_date, x.expiry_date,
                       x.raw_status, x.stage_rank,
                       CASE
                         WHEN o.state IS NOT NULL                        THEN o.state
                         WHEN x.raw_status = 'RJCT'                      THEN 'RJCT'
                         WHEN x.raw_status = 'CANC'                      THEN 'CANC'
                         WHEN x.stage_rank = 1
                              AND x.raw_status NOT IN ('MRV_PASS', 'PDNG')
                                                                         THEN 'RJCT'
                         WHEN x.stage_rank = 4 AND x.raw_status = 'ACCP'
                              AND x.expiry_date IS NOT NULL AND x.expiry_date <> ''
                              AND to_date(x.expiry_date, 'YYYYMMDD') < current_date
                                                                         THEN 'EXPIRED'
                         WHEN x.stage_rank = 4 AND x.raw_status = 'ACCP' THEN 'ACCP'
                         ELSE 'PDNG'
                       END AS state,
                       COALESCE(o.reason, x.reason) AS reason,
                       COALESCE(rc.system_action IN ('NO_RETRY', 'PERMANENT_FAIL'), false) AS no_retry
                FROM mnd_ext_status x
                LEFT JOIN mandate_override_pick o ON o.mandate_ref = x.mandate_ref
                LEFT JOIN mandate_reason_code rc ON rc.code = COALESCE(o.reason, x.reason)""");
    }

    /**
     * A PRIOR arrival carrying one mandate registration, the row source of the view stack.
     * {@code mndtReqId} may be null: MRR's B1a rule lands an intra-file duplicate loser with a
     * NULL {@code mndt_req_id} and dup_in_file true, and such a row must still surface in the
     * view, so nothing downstream may key or filter on that column.
     */
    public static void seedPriorRegistration(final JdbcTemplate jdbc, final java.util.UUID arrival,
                                             final String client, final String mandateRef,
                                             final String contractRef, final String debtorAccount,
                                             final String creditorAccount, final String expiryDate,
                                             final String mndtReqId, final String spineState) {
        ManTestTables.insertHeader(jdbc, arrival, client, 1);
        jdbc.update("""
                INSERT INTO mandate_request_entry (arrival_id, sequence, record_type, action_code,
                    mandate_ref, contract_ref, debtor_account, creditor_account, currency,
                    expiry_date, mndt_req_id, dup_in_file, spine_state)
                VALUES (?, 1, 'MD', 'CREATE', ?,?,?,?, 'ZAR', ?,?,?,?)""",
                arrival, mandateRef, contractRef, debtorAccount, creditorAccount,
                expiryDate, mndtReqId, mndtReqId == null, spineState);
    }

    /** A PBSR reply for a mandate request: the only leg whose ACCP activates a mandate. */
    public static void seedPbsr(final JdbcTemplate jdbc, final String mndtReqId, final String mandateRef,
                                final String status, final String reason) {
        jdbc.update("""
                INSERT INTO man_pbsr_resp (response_file, orgnl_msg_id, mndt_id, mndt_req_id, status, reason)
                VALUES (?, 'OUT-MSG', ?, ?, ?, ?)""",
                "REPLY-" + mndtReqId + "_PBSR.xml", mandateRef, mndtReqId, status, reason);
    }

    /** The cross-database suspension signal MRG's override sink writes (dcre_col lives elsewhere). */
    public static void seedOverride(final JdbcTemplate jdbc, final String mandateRef, final String state,
                                    final String reason, final String source) {
        jdbc.update("""
                INSERT INTO mandate_override (mandate_ref, state, reason, source)
                VALUES (?,?,?,?)""", mandateRef, state, reason, source);
    }

    /** A durable MRV verdict for a PRIOR arrival's row, the request-leg arm of mnd_ext_status. */
    public static void seedVerdict(final JdbcTemplate jdbc, final java.util.UUID arrival, final int sequence,
                                   final String outcome) {
        jdbc.update("""
                INSERT INTO man_validation_log (arrival_id, sequence, outcome)
                VALUES (?,?,?)
                ON CONFLICT (arrival_id, sequence) DO NOTHING""", arrival, sequence, outcome);
    }
}
