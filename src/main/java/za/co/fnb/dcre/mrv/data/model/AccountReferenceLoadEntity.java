package za.co.fnb.dcre.mrv.data.model;

import org.springframework.data.relational.core.mapping.Table;
import za.co.fnb.dcre.mrv.domain.AccountReferenceManifest;
import za.co.fnb.dcre.platform.persistence.BaseEntity;

import java.time.Instant;

/**
 * What a load run consumed, written in the SAME transaction as the rows it materialised.
 * Without that, a table and its provenance record can disagree, and the record is then
 * worse than none: it is a confident statement about a state that never existed.
 *
 * <p>{@code rowCount} is the manifest's count over the WHOLE artifact; {@code appliedRowCount}
 * is what THIS context materialised. They differ by design (110 against 100 for the mandates
 * projection), and collapsing them would hide the projection.
 */
@Table("account_reference_load")
public class AccountReferenceLoadEntity extends BaseEntity {

    private String datasetVersion;
    private Integer schemaVersion;
    private String sourceId;
    private Instant effectiveTs;
    private Instant publicationTs;
    private Integer rowCount;
    private String checksum;
    private Integer appliedRowCount;
    private Long jobExecutionId;

    public static AccountReferenceLoadEntity of(final AccountReferenceManifest manifest,
                                                final int appliedRowCount, final Long jobExecutionId) {
        final AccountReferenceLoadEntity entity = new AccountReferenceLoadEntity();
        entity.datasetVersion = manifest.datasetVersion();
        entity.schemaVersion = manifest.schemaVersion();
        entity.sourceId = manifest.sourceId();
        entity.effectiveTs = manifest.effectiveTs();
        entity.publicationTs = manifest.publicationTs();
        entity.rowCount = manifest.rowCount();
        entity.checksum = manifest.checksum();
        entity.appliedRowCount = appliedRowCount;
        entity.jobExecutionId = jobExecutionId;
        return entity;
    }

    public String getDatasetVersion() {
        return datasetVersion;
    }

    public Integer getSchemaVersion() {
        return schemaVersion;
    }

    public String getSourceId() {
        return sourceId;
    }

    public Instant getEffectiveTs() {
        return effectiveTs;
    }

    public Instant getPublicationTs() {
        return publicationTs;
    }

    public Integer getRowCount() {
        return rowCount;
    }

    public String getChecksum() {
        return checksum;
    }

    public Integer getAppliedRowCount() {
        return appliedRowCount;
    }

    public Long getJobExecutionId() {
        return jobExecutionId;
    }
}
