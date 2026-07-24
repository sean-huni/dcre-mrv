package za.co.fnb.dcre.mrv.data.repo;

import org.springframework.data.repository.CrudRepository;
import za.co.fnb.dcre.mrv.data.model.ManRequestEntryView;

import java.util.List;
import java.util.UUID;

/**
 * Read side of the MRR-owned mandate_request_entry spine. All spine_state WRITES
 * go through {@link ManSpineTransitionRepo} (single-column single-writer, R-04).
 */
public interface ManRequestEntryRepo extends CrudRepository<ManRequestEntryView, UUID> {

    List<ManRequestEntryView> findByArrivalIdOrderBySequence(UUID arrivalId);

    long countByArrivalId(UUID arrivalId);
}
