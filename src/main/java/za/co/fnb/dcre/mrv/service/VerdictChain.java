package za.co.fnb.dcre.mrv.service;

import za.co.fnb.dcre.platform.model.MandateOutcome;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The MRV item-tier precedence chain (plan T4), the pure-static verdict function
 * mirroring CTV's {@code VerdictChain.classify}. Stages in strict order:
 * <ol>
 *   <li>structure: record_type / action_code / mandate_ref / currency well-formed
 *       (FAIL_STRUCTURE; whole-file fatal, rolls up BUSINESS_FILE_REJECTED),</li>
 *   <li>duplicate mandate_ref in-file: the MRR-flagged {@code dup_in_file} later
 *       occurrence (FAIL_DUPLICATE_REF),</li>
 *   <li>account exists in the dcre_man {@code account} master (FAIL_ACCOUNT_NOT_FOUND),</li>
 *   <li>account_type allows mandates: {@code account_type.mandates_allowed} (AG01 class,
 *       FAIL_ACCOUNT_TYPE_DISALLOWED),</li>
 *   <li>contract format: contract_ref matches the SYNTHETIC format rule
 *       (A-61 class, FAIL_CONTRACT_FORMAT),</li>
 *   <li>action-specific: AMEND/CANCEL require the mandate_ref to be a KNOWN prior
 *       registration on the spine or the derived status view (FAIL_AMEND_UNKNOWN_REF /
 *       FAIL_CANCEL_UNKNOWN_REF); CREATE requires its ABSENCE, a collision with a
 *       prior registration reusing FAIL_DUPLICATE_REF (the only ref-clash code in the
 *       fixed MandateOutcome vocabulary),</li>
 *   <li>contract admissibility: the 1:1-live invariant
 *       (CONTRACT_HAS_LIVE_MANDATE), last because it judges an otherwise
 *       well-formed, otherwise-admissible instruction.</li>
 * </ol>
 *
 * <p>The reference maps/sets are read AS OF a single snapshot by
 * {@link za.co.fnb.dcre.mrv.data.repo.ManReferenceSnapshotDao} so every row of an
 * arrival is judged against one consistent MVCC view (CTV F51 pattern). The chain
 * itself is side-effect free and DB-free, so it is unit-tested per stage.
 */
public final class VerdictChain {

    /** SYNTHETIC-CONTRACT (A-61): contract_ref is un-attested; require non-blank alphanumeric up to the DDL width 14. */
    private static final Pattern CONTRACT_REF = Pattern.compile("[A-Za-z0-9]{1,14}");

    private static final Set<String> ACTIONS = Set.of("CREATE", "AMEND", "CANCEL");

    /** dcre_man account master row (only the fields the chain reads). */
    public record Account(String accountNumber, String accountTypeCode) {
    }

    /** One instruction record from the spine (mandate_request_entry) under validation. */
    public record Entry(int sequence, String recordType, String actionCode, String mandateRef,
                        String contractRef, String debtorAccount, String creditorAccount,
                        String currency, boolean dupInFile) {
    }

    /**
     * Resolves the mandate_ref of the mandate already effectively active on this entry's
     * contract identity, or empty when the contract is free. Kept as a function so the
     * chain stays pure and DB-free: the R-23/A-29 key selection and the AS OF snapshot
     * read live in the service tier ({@code LiveMandateGuard}).
     */
    @FunctionalInterface
    public interface LiveMandateLookup {
        Optional<String> find(Entry entry);
    }

    private VerdictChain() {
    }

    public static MandateOutcome classify(final Entry entry, final Map<String, Account> accounts,
                                          final Map<String, Boolean> mandatesAllowedByType,
                                          final Set<String> knownRefs,
                                          final LiveMandateLookup liveMandates) {
        if (isBlank(entry.recordType()) || !ACTIONS.contains(entry.actionCode())
                || isBlank(entry.mandateRef()) || isBlank(entry.currency())) {
            return MandateOutcome.FAIL_STRUCTURE;
        }
        if (entry.dupInFile()) {
            return MandateOutcome.FAIL_DUPLICATE_REF;
        }
        final Account account = accounts.get(entry.debtorAccount());
        if (account == null) {
            return MandateOutcome.FAIL_ACCOUNT_NOT_FOUND;
        }
        if (!Boolean.TRUE.equals(mandatesAllowedByType.get(account.accountTypeCode()))) {
            return MandateOutcome.FAIL_ACCOUNT_TYPE_DISALLOWED; // AG01 class
        }
        if (entry.contractRef() == null || !CONTRACT_REF.matcher(entry.contractRef().strip()).matches()) {
            return MandateOutcome.FAIL_CONTRACT_FORMAT;
        }
        final MandateOutcome action = actionRule(entry, knownRefs);
        return action == MandateOutcome.PASS ? contractAdmissible(entry, liveMandates) : action;
    }

    /**
     * SCRUM-91 (refines R-20): at most one effectively-active mandate per contract
     * identity. A view cannot reject a write, so the invariant is enforced here at the
     * sole admission point rather than on the response leg, and there is no DB backstop
     * (A-73). An amendment of the SAME mandate_ref is the live mandate itself, not a
     * twin, so it is filtered out rather than special-cased per action code.
     */
    private static MandateOutcome contractAdmissible(final Entry entry, final LiveMandateLookup liveMandates) {
        return liveMandates.find(entry)
                .filter(live -> !live.equals(entry.mandateRef()))
                .map(live -> MandateOutcome.CONTRACT_HAS_LIVE_MANDATE)
                .orElse(MandateOutcome.PASS);
    }

    /**
     * CREATE requires the ref to be absent from prior registrations (a collision is a
     * cross-file duplicate ref); AMEND/CANCEL require it present. "Known" excludes the
     * current arrival's own rows (intra-file duplicates are stage 2's job), so the same
     * knownRefs set drives both the presence and the absence check symmetrically.
     */
    private static MandateOutcome actionRule(final Entry entry, final Set<String> knownRefs) {
        final boolean known = knownRefs.contains(entry.mandateRef());
        return switch (entry.actionCode()) {
            case "CREATE" -> known ? MandateOutcome.FAIL_DUPLICATE_REF : MandateOutcome.PASS;
            case "AMEND" -> known ? MandateOutcome.PASS : MandateOutcome.FAIL_AMEND_UNKNOWN_REF;
            case "CANCEL" -> known ? MandateOutcome.PASS : MandateOutcome.FAIL_CANCEL_UNKNOWN_REF;
            default -> MandateOutcome.FAIL_STRUCTURE; // unreachable: structure stage already gated ACTIONS
        };
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }
}
