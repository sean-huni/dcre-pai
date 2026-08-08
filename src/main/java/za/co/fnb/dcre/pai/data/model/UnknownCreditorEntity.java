package za.co.fnb.dcre.pai.data.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * A creditor account PAI found ABSENT from the account master while initialising an
 * arrival. PAI owns this relation (003-unknown-creditor.xml), which is the whole point
 * of it: the sighting is PAI's own observation and stays PAI's whatever the account
 * master turns out to be.
 *
 * <p>Deliberately not a BaseEntity: rows are written once and never updated, so there
 * is no version column and no updated_at to carry. PAI only ever touches the table
 * through {@code UnknownCreditorRepo}'s one targeted insert.
 */
@Table("unknown_creditor")
public class UnknownCreditorEntity {

    @Id
    private UUID id;
    private String accountNumber;
    private UUID arrivalId;

    public UUID getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public UUID getArrivalId() {
        return arrivalId;
    }
}
