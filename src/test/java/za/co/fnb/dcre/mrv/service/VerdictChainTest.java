package za.co.fnb.dcre.mrv.service;

import org.junit.jupiter.api.Test;
import za.co.fnb.dcre.mrv.service.VerdictChain.Account;
import za.co.fnb.dcre.mrv.service.VerdictChain.Entry;
import za.co.fnb.dcre.mrv.service.VerdictChain.LiveMandateLookup;
import za.co.fnb.dcre.platform.model.MandateOutcome;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Per-stage unit coverage of the MRV precedence chain (plan T4). Pure and DB-free:
 * every stage gets a positive and a negative case, plus the precedence guarantees
 * (an earlier stage wins over a later one). Structure, duplicate, account existence,
 * account-type allowance, contract format, and the action-specific rules.
 */
class VerdictChainTest {

    private static final Map<String, Account> ACCOUNTS = Map.of(
            "6200000021", new Account("6200000021", "CHQ"),
            "6200000099", new Account("6200000099", "SAV"));
    private static final Map<String, Boolean> ALLOWED = Map.of(
            "CHQ", true, "SAV", false, "TRN", true);
    private static final Set<String> KNOWN = Set.of("MREF-EXISTING-1");

    /** No contract anywhere holds a live mandate: the admissibility stage is a no-op. */
    private static final LiveMandateLookup NO_LIVE_TWIN = e -> Optional.empty();

    private static Entry entry(final String action, final String ref, final String contract,
                               final String debtor, final boolean dup) {
        return new Entry(1, "MD", action, ref, contract, debtor, "7300000001", "ZAR", dup);
    }

    private static MandateOutcome classify(final Entry entry) {
        return VerdictChain.classify(entry, ACCOUNTS, ALLOWED, KNOWN, NO_LIVE_TWIN);
    }

    private static MandateOutcome classify(final Entry entry, final String liveRef) {
        return VerdictChain.classify(entry, ACCOUNTS, ALLOWED, KNOWN, e -> Optional.of(liveRef));
    }

    @Test
    void cleanCreatePasses() {
        assertEquals(MandateOutcome.PASS,
                classify(entry("CREATE", "MREF-NEW-1", "CTR1", "6200000021", false)));
    }

    @Test
    void unknownActionIsStructural() {
        assertEquals(MandateOutcome.FAIL_STRUCTURE,
                classify(entry("BOGUS", "MREF-NEW-1", "CTR1", "6200000021", false)));
    }

    @Test
    void blankMandateRefIsStructural() {
        assertEquals(MandateOutcome.FAIL_STRUCTURE,
                classify(entry("CREATE", "   ", "CTR1", "6200000021", false)));
    }

    @Test
    void blankCurrencyIsStructural() {
        assertEquals(MandateOutcome.FAIL_STRUCTURE,
                classify(new Entry(1, "MD", "CREATE", "MREF-NEW-1", "CTR1", "6200000021",
                        "7300000001", "  ", false)));
    }

    @Test
    void duplicateInFileRejects() {
        assertEquals(MandateOutcome.FAIL_DUPLICATE_REF,
                classify(entry("CREATE", "MREF-NEW-1", "CTR1", "6200000021", true)));
    }

    @Test
    void unknownAccountRejects() {
        assertEquals(MandateOutcome.FAIL_ACCOUNT_NOT_FOUND,
                classify(entry("CREATE", "MREF-NEW-1", "CTR1", "6200000000", false)));
    }

    @Test
    void disallowedAccountTypeRejects() {
        assertEquals(MandateOutcome.FAIL_ACCOUNT_TYPE_DISALLOWED,
                classify(entry("CREATE", "MREF-NEW-1", "CTR1", "6200000099", false)), "SAV disallows mandates (AG01)");
    }

    @Test
    void badContractFormatRejects() {
        assertEquals(MandateOutcome.FAIL_CONTRACT_FORMAT,
                classify(entry("CREATE", "MREF-NEW-1", "CTR/BAD*", "6200000021", false)));
    }

    @Test
    void blankContractRejects() {
        assertEquals(MandateOutcome.FAIL_CONTRACT_FORMAT,
                classify(entry("CREATE", "MREF-NEW-1", "   ", "6200000021", false)));
    }

    @Test
    void amendUnknownRefRejects() {
        assertEquals(MandateOutcome.FAIL_AMEND_UNKNOWN_REF,
                classify(entry("AMEND", "MREF-NEW-1", "CTR1", "6200000021", false)));
    }

    @Test
    void cancelUnknownRefRejects() {
        assertEquals(MandateOutcome.FAIL_CANCEL_UNKNOWN_REF,
                classify(entry("CANCEL", "MREF-NEW-1", "CTR1", "6200000021", false)));
    }

    @Test
    void amendKnownRefPasses() {
        assertEquals(MandateOutcome.PASS,
                classify(entry("AMEND", "MREF-EXISTING-1", "CTR1", "6200000021", false)));
    }

    @Test
    void cancelKnownRefPasses() {
        assertEquals(MandateOutcome.PASS,
                classify(entry("CANCEL", "MREF-EXISTING-1", "CTR1", "6200000021", false)));
    }

    @Test
    void createExistingRefIsDuplicate() {
        assertEquals(MandateOutcome.FAIL_DUPLICATE_REF,
                classify(entry("CREATE", "MREF-EXISTING-1", "CTR1", "6200000021", false)),
                "CREATE requires absence: an existing ref is a cross-file duplicate");
    }

    @Test
    void structurePrecedesAccountCheck() {
        assertEquals(MandateOutcome.FAIL_STRUCTURE,
                classify(entry("BOGUS", "MREF-NEW-1", "CTR1", "6200000000", false)),
                "structure wins over account-not-found");
    }

    @Test
    void duplicatePrecedesAccountCheck() {
        assertEquals(MandateOutcome.FAIL_DUPLICATE_REF,
                classify(entry("CREATE", "MREF-NEW-1", "CTR1", "6200000000", true)),
                "in-file duplicate wins over account-not-found");
    }

    @Test
    void aLiveMandateOnTheContractIsInadmissible() {
        assertEquals(MandateOutcome.CONTRACT_HAS_LIVE_MANDATE,
                classify(entry("CREATE", "MREF-NEW-1", "CTR1", "6200000021", false), "MREF-LIVE-9"));
    }

    @Test
    void theLiveMandateBeingThisSameRefIsNotATwin() {
        assertEquals(MandateOutcome.PASS,
                classify(entry("AMEND", "MREF-EXISTING-1", "CTR1", "6200000021", false), "MREF-EXISTING-1"),
                "an amendment of the live mandate itself is not a second mandate on the contract");
    }

    @Test
    void anEarlierStageWinsOverTheAdmissibilityStage() {
        assertEquals(MandateOutcome.FAIL_ACCOUNT_NOT_FOUND,
                classify(entry("CREATE", "MREF-NEW-1", "CTR1", "6200000000", false), "MREF-LIVE-9"),
                "admissibility judges an otherwise-valid instruction, so it runs last");
    }
}
