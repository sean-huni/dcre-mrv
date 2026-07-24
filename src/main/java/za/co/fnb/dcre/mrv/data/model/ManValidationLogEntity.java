package za.co.fnb.dcre.mrv.data.model;

import org.springframework.data.relational.core.mapping.Table;
import za.co.fnb.dcre.platform.persistence.BaseEntity;

import java.util.UUID;

/**
 * One durable MRV verdict row per instruction record (arrival_id, sequence).
 * Written only through {@link za.co.fnb.dcre.mrv.data.repo.ManValidationLogBatchDao}
 * (guarded INSERT ... ON CONFLICT DO NOTHING); the CrudRepository side is read-only
 * projections, so the table carries no version/audit columns (CTV ValidationLog
 * pattern).
 */
@Table("man_validation_log")
public class ManValidationLogEntity extends BaseEntity {

    private UUID arrivalId;
    private Integer sequence;
    private String outcome;
    private String detail;

    public static ManValidationLogEntity of(final UUID arrivalId, final int sequence,
                                            final String outcome, final String detail) {
        final ManValidationLogEntity e = new ManValidationLogEntity();
        e.arrivalId = arrivalId;
        e.sequence = sequence;
        e.outcome = outcome;
        e.detail = detail;
        return e;
    }

    public UUID getArrivalId() {
        return arrivalId;
    }

    public Integer getSequence() {
        return sequence;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getDetail() {
        return detail;
    }
}
