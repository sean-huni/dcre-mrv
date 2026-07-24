package za.co.fnb.dcre.mrv.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import za.co.fnb.dcre.mrv.data.model.ManRequestEntryView;
import za.co.fnb.dcre.mrv.data.model.ManRequestHeaderView;
import za.co.fnb.dcre.mrv.data.model.ManValidationLogEntity;
import za.co.fnb.dcre.mrv.data.repo.ManReferenceSnapshotDao;
import za.co.fnb.dcre.mrv.data.repo.ManRequestEntryRepo;
import za.co.fnb.dcre.mrv.data.repo.ManRequestHeaderRepo;
import za.co.fnb.dcre.mrv.data.repo.ManValidationLogBatchDao;
import za.co.fnb.dcre.mrv.service.VerdictChain.Account;
import za.co.fnb.dcre.mrv.service.VerdictChain.Entry;
import za.co.fnb.dcre.platform.model.MandateOutcome;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Business tier (configuration.md point 21): the MRV item-tier verdict pass over
 * one arrival. Tier 1 ({@link #checkHeader}) resolves the acceptance-mode client
 * token and captures the CTV F51 as-of snapshot HLC; tier 2 ({@link #validate})
 * classifies every spine row against the account/account_type/known-ref stores
 * read AS OF that one HLC and writes the durable man_validation_log. The
 * spine_state transition + rollup is {@link ManRollupService}.
 */
@Service
public class ManValidationService {

    private static final Logger log = LoggerFactory.getLogger(ManValidationService.class);

    /** The acceptance-mode client token plus the single as-of HLC for this arrival's reference reads. */
    public record HeaderSnapshot(String clientToken, String asOfTimestamp) {
    }

    private final ManRequestHeaderRepo headers;
    private final ManRequestEntryRepo entries;
    private final ManReferenceSnapshotDao referenceSnapshot;
    private final ManValidationLogBatchDao verdictBatch;

    public ManValidationService(final ManRequestHeaderRepo headers, final ManRequestEntryRepo entries,
                                final ManReferenceSnapshotDao referenceSnapshot,
                                final ManValidationLogBatchDao verdictBatch) {
        this.headers = headers;
        this.entries = entries;
        this.referenceSnapshot = referenceSnapshot;
        this.verdictBatch = verdictBatch;
    }

    /** Tier 1: client identity (for the R-41 acceptance mode) + F51 snapshot capture. */
    public HeaderSnapshot checkHeader(final UUID arrivalId) {
        final ManRequestHeaderView header = headers.findByArrivalId(arrivalId).orElseThrow(
                () -> new IllegalStateException("no mandate_request_header for arrival " + arrivalId
                        + ": MRR must ingest before MRV validates"));
        final String clientToken = header.getClientToken() != null && !header.getClientToken().isBlank()
                ? header.getClientToken().strip()
                : String.valueOf(header.getDestinationId()).strip();
        return new HeaderSnapshot(clientToken, referenceSnapshot.snapshotTimestamp());
    }

    /**
     * Tier 2: classify every spine row of the arrival against the reference stores
     * AS OF {@code asOfTimestamp} and write the durable verdicts. Reprocessing the
     * whole arrival each run is idempotent (the log DAO is first-write-wins on the
     * business identity), so a resume never duplicates a verdict.
     */
    public void validate(final UUID arrivalId, final String asOfTimestamp) {
        final List<ManRequestEntryView> rows = entries.findByArrivalIdOrderBySequence(arrivalId);
        if (rows.isEmpty()) {
            return;
        }
        final Set<String> debtorAccounts = rows.stream()
                .map(ManRequestEntryView::getDebtorAccount).collect(Collectors.toSet());
        final Set<String> refs = rows.stream()
                .map(ManRequestEntryView::getMandateRef).collect(Collectors.toSet());
        final Map<String, Account> accounts = referenceSnapshot.accountsByNumber(asOfTimestamp, debtorAccounts);
        final Map<String, Boolean> mandatesAllowed = referenceSnapshot.mandatesAllowedByType(asOfTimestamp);
        final Set<String> knownRefs = referenceSnapshot.knownRefs(asOfTimestamp, refs, arrivalId);

        final List<ManValidationLogEntity> batch = new ArrayList<>(rows.size());
        for (final ManRequestEntryView row : rows) {
            final Entry entry = new Entry(row.getSequence(), row.getRecordType(), row.getActionCode(),
                    row.getMandateRef(), row.getContractRef(), row.getDebtorAccount(), row.getCurrency(),
                    row.getDupInFile());
            final MandateOutcome outcome = VerdictChain.classify(entry, accounts, mandatesAllowed, knownRefs);
            if (outcome != MandateOutcome.PASS) {
                // R-38 exclusion visibility: WARN at decision time; man_validation_log is the durable record.
                log.warn("excluded stage=MRV arrival={} seq={} action={} ref={} reason={}",
                        arrivalId, row.getSequence(), row.getActionCode(), row.getMandateRef(), outcome.name());
            }
            batch.add(ManValidationLogEntity.of(arrivalId, row.getSequence(), outcome.name(),
                    ManVerdictDetail.of(outcome, entry)));
        }
        verdictBatch.insertAll(batch);
    }
}
