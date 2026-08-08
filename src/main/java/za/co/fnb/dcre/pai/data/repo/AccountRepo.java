package za.co.fnb.dcre.pai.data.repo;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.pai.data.model.AccountEntity;

import java.util.UUID;

/**
 * PAI writer over the shared account table (single writer, R-04; CTV holds
 * SELECT only). Extends Repository, not CrudRepository: the derived save()
 * path cannot satisfy the table's NOT NULL contract from the minimal
 * AccountEntity, so only these targeted queries are exposed.
 */
public interface AccountRepo extends Repository<AccountEntity, UUID> {

    @Query("SELECT count(*) > 0 FROM account WHERE account_number = :accountNumber")
    boolean existsByAccountNumber(@Param("accountNumber") String accountNumber);

    /**
     * [SYNTHETIC-CONTRACT R-35] A-4/A-20 draft: idempotent creditor account
     * mint (R-11) with synthetic dev defaults per the toolkit fixture DDL
     * (dcre_accounts_sample.sql): product_code FNBRF, acc_type CACC,
     * balance 999999999.99 (generous so caps pass post-init; FNBRF requires
     * balance NOT NULL and max_credit_limit NULL), process_status ACTIVE,
     * status AAUT, branch_code 250205, ucn 100000000000, client_id 2;
     * app_no mirrors the account number. ON CONFLICT DO NOTHING keeps an
     * existing account untouched.
     */
    @Modifying
    @Query("""
            INSERT INTO account (id, product_code, account_number, app_no, acc_type, branch_code,
                                 balance, process_status, status, ucn, client_id)
            VALUES (gen_random_uuid(), 'FNBRF', :accountNumber, :accountNumber, 'CACC', '250205',
                    999999999.99, 'ACTIVE', 'AAUT', '100000000000', 2)
            ON CONFLICT (account_number) DO NOTHING""")
    void createIfAbsent(@Param("accountNumber") String accountNumber);
}
