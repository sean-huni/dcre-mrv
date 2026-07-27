package za.co.fnb.dcre.mrv;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Seeds the MRG-owned response-leg stores that MRV's 1:1-live admission check reads through
 * ({@code mandate_current_status}, the per-MANDATE collapse) and that MRG reports on
 * ({@code mandate_effective_status}, per INSTRUCTION).
 *
 * <p>The DDL used to be hand-copied into this class and had already drifted from MRG three
 * ways: mnd_ext_status had lost {@code action_code}, mnd_pbsr_pick had lost its PDNG-first
 * pick ordering, and {@code mandate_current_status} was never created at all, so no MRV test
 * could see that the 1:1-live check was reading the wrong grain. The DDL now comes from MRG's
 * own changesets via {@link ManReadSideSchema}; this class is seeding only.
 */
public final class ManEffectiveStatusTables {

    private ManEffectiveStatusTables() {
    }

    /**
     * A PRIOR arrival carrying the CREATE of one mandate, the row source of the view stack.
     * {@code mndtReqId} may be null: MRR's B1a rule lands an intra-file duplicate loser with a
     * NULL {@code mndt_req_id} and dup_in_file true, and such a row must still surface in the
     * view, so nothing downstream may key or filter on that column.
     */
    public static void seedPriorRegistration(final JdbcTemplate jdbc, final UUID arrival,
                                             final String client, final String mandateRef,
                                             final String contractRef, final String debtorAccount,
                                             final String creditorAccount, final String expiryDate,
                                             final String mndtReqId, final String spineState) {
        seedInstruction(jdbc, arrival, client, "CREATE", mandateRef, contractRef, debtorAccount,
                creditorAccount, expiryDate, mndtReqId, spineState);
    }

    /**
     * Any instruction on a mandate, in its OWN arrival with its OWN mndt_req_id: the D5b
     * fan-out, since one mandate accumulates a CREATE, then AMENDs, then a CANCEL, each its own
     * spine entry. The action_code was hardcoded to CREATE before SCRUM-91's grain fix, which
     * is exactly why the accepted-CANCEL shape was unseedable and the defect survived.
     */
    public static void seedInstruction(final JdbcTemplate jdbc, final UUID arrival, final String client,
                                       final String actionCode, final String mandateRef,
                                       final String contractRef, final String debtorAccount,
                                       final String creditorAccount, final String expiryDate,
                                       final String mndtReqId, final String spineState) {
        ManTestTables.insertHeader(jdbc, arrival, client, 1);
        jdbc.update("""
                INSERT INTO mandate_request_entry (arrival_id, sequence, record_type, action_code,
                    mandate_ref, contract_ref, debtor_account, creditor_account, currency,
                    max_collection_amount_raw, max_collection_amount, expiry_date, mndt_req_id,
                    dup_in_file, spine_state)
                VALUES (?, 1, 'MD', ?,?,?,?,?, 'ZAR', '000000000010000', 100.00, ?,?,?,?)""",
                arrival, actionCode, mandateRef, contractRef, debtorAccount, creditorAccount,
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
    public static void seedVerdict(final JdbcTemplate jdbc, final UUID arrival, final int sequence,
                                   final String outcome) {
        jdbc.update("""
                INSERT INTO man_validation_log (arrival_id, sequence, outcome)
                VALUES (?,?,?)
                ON CONFLICT (arrival_id, sequence) DO NOTHING""", arrival, sequence, outcome);
    }
}
