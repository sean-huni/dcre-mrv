package za.co.fnb.dcre.mrv.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import za.co.fnb.dcre.mrv.data.repo.ManReferenceSnapshotDao;
import za.co.fnb.dcre.mrv.data.repo.ManReferenceSnapshotDao.AccountReferenceLoad;
import za.co.fnb.dcre.mrv.data.repo.AccountReferenceNotMaterialisedException;

/**
 * Separates "the account master was never materialised" from "this account is not on the
 * account master". Without it MRV answers FAIL_ACCOUNT_NOT_FOUND for every row of every file
 * whenever the loader has not run, and reports a missed deployment step as a client data
 * problem.
 *
 * <p>Runs ONCE PER RUN at the snapshot-capture point, never per range and never per row: it
 * is a statement about the database, and the F51 snapshot means it cannot change under the
 * run that just asked.
 */
@Component
public class AccountReferenceGuard {

    private static final Logger LOG = LoggerFactory.getLogger(AccountReferenceGuard.class);

    private final ManReferenceSnapshotDao snapshot;

    public AccountReferenceGuard(final ManReferenceSnapshotDao snapshot) {
        this.snapshot = snapshot;
    }

    /**
     * @param asOf the run's captured HLC, so the ledger is read from the same MVCC view the
     *             account reads will use
     * @return the dataset version this run is judging against, for the record
     * @throws AccountReferenceNotMaterialisedException when nothing ever loaded, or the newest
     *                                                  load applied no rows
     */
    public String requireMaterialised(final String asOf) {
        final AccountReferenceLoad load = snapshot.latestAccountReferenceLoad(asOf)
                .orElseThrow(AccountReferenceNotMaterialisedException::neverLoaded);
        if (load.appliedRowCount() == 0) {
            throw AccountReferenceNotMaterialisedException.loadedEmpty(load.datasetVersion());
        }
        LOG.info("account-reference-materialised stage=MRV dataset={} appliedRows={}",
                load.datasetVersion(), load.appliedRowCount());
        return load.datasetVersion();
    }
}
