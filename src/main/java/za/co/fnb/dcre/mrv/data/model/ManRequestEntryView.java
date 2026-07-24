package za.co.fnb.dcre.mrv.data.model;

import org.springframework.data.relational.core.mapping.Table;
import za.co.fnb.dcre.platform.persistence.BaseEntity;

import java.util.UUID;

/**
 * MRV read model over the MRR-owned {@code mandate_request_entry} (shared
 * dcre_man): the fields the VerdictChain classifies. MRV writes only the
 * {@code spine_state} column it owns (ruling note 2), via the guarded
 * transitions on {@code ManSpineTransitionRepo} rather than through this entity.
 */
@Table("mandate_request_entry")
public class ManRequestEntryView extends BaseEntity {

    private UUID arrivalId;
    private Integer sequence;
    private String recordType;
    private String actionCode;
    private String mandateRef;
    private String contractRef;
    private String debtorAccount;
    private String currency;
    private boolean dupInFile;
    private String spineState;

    public UUID getArrivalId() {
        return arrivalId;
    }

    public Integer getSequence() {
        return sequence;
    }

    public String getRecordType() {
        return recordType;
    }

    public String getActionCode() {
        return actionCode;
    }

    public String getMandateRef() {
        return mandateRef;
    }

    public String getContractRef() {
        return contractRef;
    }

    public String getDebtorAccount() {
        return debtorAccount;
    }

    public String getCurrency() {
        return currency;
    }

    public boolean getDupInFile() {
        return dupInFile;
    }

    public String getSpineState() {
        return spineState;
    }
}
