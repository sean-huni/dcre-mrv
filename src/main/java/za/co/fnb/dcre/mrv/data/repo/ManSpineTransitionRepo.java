package za.co.fnb.dcre.mrv.data.repo;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.mrv.data.model.ManRequestEntryView;

import java.util.UUID;

/**
 * Single writer of the {@code spine_state} column MRV owns (ruling note 2): the
 * RECEIVED -> VALIDATED|REJECTED transition, derived from the durable
 * man_validation_log. Every mutation is a GUARDED atomic UPDATE
 * ({@code WHERE spine_state = 'RECEIVED'}, persistence.md) so it is:
 * <ul>
 *   <li>idempotent + resumable: a re-run touches zero already-transitioned rows,</li>
 *   <li>non-clobbering: a downstream stage's advancement (MAF SCORE_*, MIS
 *       INITIALIZED) is never RECEIVED, so MRV cannot overwrite it.</li>
 * </ul>
 * Native @Query per the guarded-mutation canon (QueryDSL cannot express these).
 */
public interface ManSpineTransitionRepo extends Repository<ManRequestEntryView, UUID> {

    /**
     * File-rejected path (structural fatal, or any fail under ALL_OR_NOTHING): the
     * whole file is rejected as a unit, so even individually-passing rows go REJECTED
     * and nothing proceeds to MAF.
     */
    @Modifying
    @Query("""
            UPDATE mandate_request_entry SET spine_state = 'REJECTED', updated_at = now()
            WHERE arrival_id = :arrivalId AND spine_state = 'RECEIVED'""")
    int rejectAllReceived(@Param("arrivalId") UUID arrivalId);

    /** PARTIAL/ACCEPTED path: rows the durable log verdicted PASS advance to VALIDATED. */
    @Modifying
    @Query("""
            UPDATE mandate_request_entry e SET spine_state = 'VALIDATED', updated_at = now()
            WHERE e.arrival_id = :arrivalId AND e.spine_state = 'RECEIVED'
              AND EXISTS (SELECT 1 FROM man_validation_log l
                          WHERE l.arrival_id = e.arrival_id AND l.sequence = e.sequence
                            AND l.outcome = 'PASS')""")
    int validatePassedReceived(@Param("arrivalId") UUID arrivalId);

    /** PARTIAL/ACCEPTED path: rows the durable log verdicted any FAIL go REJECTED. */
    @Modifying
    @Query("""
            UPDATE mandate_request_entry e SET spine_state = 'REJECTED', updated_at = now()
            WHERE e.arrival_id = :arrivalId AND e.spine_state = 'RECEIVED'
              AND EXISTS (SELECT 1 FROM man_validation_log l
                          WHERE l.arrival_id = e.arrival_id AND l.sequence = e.sequence
                            AND l.outcome <> 'PASS')""")
    int rejectFailedReceived(@Param("arrivalId") UUID arrivalId);
}
