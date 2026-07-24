package za.co.fnb.dcre.mrv.service;

import org.springframework.stereotype.Service;
import za.co.fnb.dcre.mrv.config.AcceptanceModeProperties;
import za.co.fnb.dcre.mrv.config.AcceptanceModeProperties.Mode;
import za.co.fnb.dcre.mrv.data.repo.ManSpineTransitionRepo;
import za.co.fnb.dcre.mrv.data.repo.ManValidationLogRepo;

import java.util.UUID;

/**
 * Business tier: the R-41 outcome rollup + the spine_state transition derived from
 * the durable man_validation_log. A structural fatal (any FAIL_STRUCTURE) or any
 * item fail under ALL_OR_NOTHING rejects the whole file (BUSINESS_FILE_REJECTED),
 * so every RECEIVED row goes REJECTED and nothing proceeds to MAF; PARTIAL lets the
 * passing rows advance to VALIDATED while failing rows go REJECTED; a clean arrival
 * is BUSINESS_ACCEPTED. All transitions are guarded on spine_state='RECEIVED', so
 * the rollup is idempotent + resumable.
 */
@Service
public class ManRollupService {

    public static final String BUSINESS_FILE_REJECTED = "BUSINESS_FILE_REJECTED";
    public static final String BUSINESS_PARTIAL = "BUSINESS_PARTIAL";
    public static final String BUSINESS_ACCEPTED = "BUSINESS_ACCEPTED";

    private final ManValidationLogRepo verdicts;
    private final ManSpineTransitionRepo spine;
    private final AcceptanceModeProperties acceptanceMode;

    public ManRollupService(final ManValidationLogRepo verdicts, final ManSpineTransitionRepo spine,
                            final AcceptanceModeProperties acceptanceMode) {
        this.verdicts = verdicts;
        this.spine = spine;
        this.acceptanceMode = acceptanceMode;
    }

    /** Computes the seam verdict and transitions the spine accordingly. Returns the verdict string. */
    public String rollupAndTransition(final UUID arrivalId, final String clientToken) {
        final int structural = verdicts.countStructuralForArrival(arrivalId);
        final int fails = verdicts.countFailsForArrival(arrivalId);
        final Mode mode = acceptanceMode.modeFor(clientToken);
        final boolean fileRejected = structural > 0 || (fails > 0 && mode == Mode.ALL_OR_NOTHING);
        if (fileRejected) {
            spine.rejectAllReceived(arrivalId);
            return BUSINESS_FILE_REJECTED;
        }
        spine.validatePassedReceived(arrivalId);
        spine.rejectFailedReceived(arrivalId);
        return fails > 0 ? BUSINESS_PARTIAL : BUSINESS_ACCEPTED;
    }
}
