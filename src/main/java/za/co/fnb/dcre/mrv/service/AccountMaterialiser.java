package za.co.fnb.dcre.mrv.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.fnb.dcre.mrv.data.model.AccountReferenceLoadEntity;
import za.co.fnb.dcre.mrv.data.repo.AccountMasterDao;
import za.co.fnb.dcre.mrv.data.repo.AccountReferenceLoadRepo;
import za.co.fnb.dcre.mrv.domain.AccountReferenceManifest;
import za.co.fnb.dcre.mrv.domain.AccountReferenceRow;
import za.co.fnb.dcre.mrv.domain.AccountWidthRule;

import java.util.List;

/**
 * The atomic half of a load: width guard, clear, insert, record, all in ONE transaction.
 *
 * <p>There is no partial application and no per-row skip. A constraint violation on the
 * hundredth row rolls back the ninety-nine before it AND the load record, so the table keeps
 * its PREVIOUS contents and no provenance row claims a state that never existed.
 *
 * <p>The transaction is taken from a {@link TransactionTemplate} rather than an
 * {@code @Transactional} annotation deliberately: annotation-driven transactions need the
 * Spring proxy, so a directly constructed instance would silently run every statement in
 * autocommit and the rollback test would pass while proving nothing.
 */
@Component
public class AccountMaterialiser {

    private final AccountMasterDao accounts;
    private final AccountReferenceLoadRepo loads;
    private final TransactionTemplate transactions;

    public AccountMaterialiser(final AccountMasterDao accounts, final AccountReferenceLoadRepo loads,
                               final PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.loads = loads;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void apply(final AccountReferenceManifest manifest, final List<AccountReferenceRow> rows,
                      final Long jobExecutionId) {
        transactions.executeWithoutResult(status -> {
            AccountWidthRule.check(rows, accounts.columnLimits());
            accounts.deleteAll();
            accounts.insertAll(rows);
            loads.save(AccountReferenceLoadEntity.of(manifest, rows.size(), jobExecutionId));
        });
    }
}
