package za.co.fnb.dcre.mrv.data.repo;

import org.springframework.data.repository.CrudRepository;
import za.co.fnb.dcre.mrv.data.model.AccountReferenceLoadEntity;

import java.util.UUID;

/**
 * The provenance ledger of account reference loads. Append-only in practice: a load record
 * is never updated, because it states what a past run consumed and a past run cannot change
 * its mind.
 */
public interface AccountReferenceLoadRepo extends CrudRepository<AccountReferenceLoadEntity, UUID> {
}
