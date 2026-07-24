package za.co.fnb.dcre.mrv.data.repo;

import org.springframework.data.repository.CrudRepository;
import za.co.fnb.dcre.mrv.data.model.ManRequestHeaderView;

import java.util.Optional;
import java.util.UUID;

/** Read side of the MRR-owned mandate_request_header; MRV never writes it. */
public interface ManRequestHeaderRepo extends CrudRepository<ManRequestHeaderView, UUID> {

    Optional<ManRequestHeaderView> findByArrivalId(UUID arrivalId);
}
