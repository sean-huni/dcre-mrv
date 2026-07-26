package za.co.fnb.dcre.mrv.service;

import org.springframework.stereotype.Service;
import za.co.fnb.dcre.mrv.data.repo.ManReferenceSnapshotDao;
import za.co.fnb.dcre.mrv.service.VerdictChain.Entry;
import za.co.fnb.dcre.mrv.service.VerdictChain.LiveMandateLookup;

import java.util.Map;
import java.util.Optional;

/**
 * Business tier: picks the contract identity the 1:1-live invariant is keyed on and
 * binds it to the arrival's client token and its single CTV F51 as-of snapshot, so the
 * pure {@link VerdictChain} receives a plain function.
 *
 * <p>The key is {@code (client, contract_ref)}, falling back to
 * {@code (client, debtor_account, creditor_account)} when contract_ref is blank
 * (R-23/A-29). The client dimension is per-arrival, not per-row: two clients may hold
 * mandates on the same contract reference without colliding.
 *
 * <p>The lookups pin to {@code asOf} like every other reference read, so all rows of one
 * arrival are judged against the same MVCC view. A mandate registered by a concurrent
 * arrival after the snapshot must not make row 1 and row 500 of this file disagree.
 *
 * <p>The snapshot cannot see the arrival being validated (its rows carry no verdict yet,
 * which is exactly how the DAO excludes them), so two rows of ONE file claiming the same
 * contract would both be admitted. {@code admitted} closes that: the caller records each
 * contract identity as its row passes, so the first occurrence claims the contract and a
 * later one is a twin, the same later-occurrence-loses shape MRR's B1a rule uses.
 */
@Service
public class LiveMandateGuard {

    private final ManReferenceSnapshotDao referenceSnapshot;

    public LiveMandateGuard(final ManReferenceSnapshotDao referenceSnapshot) {
        this.referenceSnapshot = referenceSnapshot;
    }

    /**
     * The per-entry live-twin lookup for one arrival, pinned to its snapshot and client.
     * {@code admitted} is the caller's live map of contract identity to the mandate_ref
     * that already claimed it within THIS arrival; it is consulted before the snapshot.
     */
    public LiveMandateLookup forArrival(final String asOf, final String clientToken,
                                        final Map<String, String> admitted) {
        return entry -> Optional.ofNullable(admitted.get(contractKey(entry)))
                .or(() -> isBlank(entry.contractRef())
                        ? referenceSnapshot.findLiveByAccounts(asOf, clientToken,
                                entry.debtorAccount(), entry.creditorAccount())
                        : referenceSnapshot.findLiveByContract(asOf, clientToken,
                                entry.contractRef().strip()));
    }

    /** The contract identity the invariant is keyed on: contract_ref, else the account pair. */
    public static String contractKey(final Entry entry) {
        return isBlank(entry.contractRef())
                ? "%s/%s".formatted(entry.debtorAccount(), entry.creditorAccount())
                : entry.contractRef().strip();
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }
}
