package za.co.fnb.dcre.pai.data.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * Minimal aggregate over the shared account table (PAI does not own it; the
 * toolkit seeds it in dev). Deliberately NOT a BaseEntity: the table carries
 * no version column, and PAI only ever touches it through AccountRepo's
 * targeted queries.
 */
@Table("account")
public class AccountEntity {

    @Id
    private UUID id;
    private String accountNumber;

    public UUID getId() { return id; }
    public String getAccountNumber() { return accountNumber; }
}
