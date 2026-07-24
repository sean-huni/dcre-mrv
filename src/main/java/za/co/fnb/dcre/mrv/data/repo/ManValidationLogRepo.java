package za.co.fnb.dcre.mrv.data.repo;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.mrv.data.model.ManValidationLogEntity;

import java.util.UUID;

/**
 * Read side of man_validation_log; writes go through {@link ManValidationLogBatchDao}.
 * The rollup reads two counts off the durable verdicts: any non-PASS row (item fail)
 * and any FAIL_STRUCTURE row (whole-file structural fatal, plan T4).
 */
public interface ManValidationLogRepo extends CrudRepository<ManValidationLogEntity, UUID> {

    @Query("SELECT count(*) FROM man_validation_log WHERE arrival_id = :arrivalId AND outcome <> 'PASS'")
    int countFailsForArrival(@Param("arrivalId") UUID arrivalId);

    @Query("SELECT count(*) FROM man_validation_log WHERE arrival_id = :arrivalId AND outcome = 'FAIL_STRUCTURE'")
    int countStructuralForArrival(@Param("arrivalId") UUID arrivalId);
}
