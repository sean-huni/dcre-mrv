package za.co.fnb.dcre.mrv.data.model;

import org.springframework.data.relational.core.mapping.Table;
import za.co.fnb.dcre.platform.persistence.BaseEntity;

import java.util.UUID;

/**
 * MRV read model over the MRR-owned {@code mandate_request_header} (shared
 * dcre_man): only the client identity MRV needs for the R-41 acceptance-mode
 * lookup. MRV never writes the header.
 */
@Table("mandate_request_header")
public class ManRequestHeaderView extends BaseEntity {

    private UUID arrivalId;
    private String clientToken;
    private String destinationId;
    private Integer entryCount;

    public UUID getArrivalId() {
        return arrivalId;
    }

    public String getClientToken() {
        return clientToken;
    }

    public String getDestinationId() {
        return destinationId;
    }

    public Integer getEntryCount() {
        return entryCount;
    }
}
