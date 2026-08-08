package za.co.fnb.dcre.pai.data.model;

import org.springframework.data.relational.core.mapping.Table;
import za.co.fnb.dcre.platform.persistence.BaseEntity;

import java.util.UUID;

/** Per-transaction account-init verdict; action is EXISTS or CREATED. */
@Table("pai_verdict")
public class PaiVerdictEntity extends BaseEntity {

    private UUID arrivalId;
    private Integer sequence;
    private String action;

    public UUID getArrivalId() { return arrivalId; }
    public Integer getSequence() { return sequence; }
    public String getAction() { return action; }
}
