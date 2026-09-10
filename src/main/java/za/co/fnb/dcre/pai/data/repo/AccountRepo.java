package za.co.fnb.dcre.pai.data.repo;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.pai.data.model.AccountEntity;

import java.util.UUID;

/**
 * READ-ONLY probe of the account master. PAI does not own this relation and, since
 * SCRUM-107, does not write it: the only method here is a question.
 *
 * <p>The mint that used to live beside this probe inserted a hardcoded row taken from a
 * toolkit sample, including a balance of 999999999.99 picked so that downstream cap
 * checks would pass. Writing that into the relation PTV validates against let a
 * downstream stage disarm an upstream control one account at a time. The observation PAI
 * actually makes now goes to {@code UnknownCreditorRepo}, over a relation PAI owns.
 *
 * <p>Where this probe should point is the open account-model question and is NOT decided
 * here. Today it resolves against PAI's primary datasource, where nothing creates the
 * relation, so a run against a real dcre_pay fails loudly and names it. That is the
 * correct failure: the alternative is a mint, and a mint is what put PAI's schema in the
 * wrong database in the first place.
 */
public interface AccountRepo extends Repository<AccountEntity, UUID> {

    @Query("SELECT count(*) > 0 FROM account WHERE account_number = :accountNumber")
    boolean existsByAccountNumber(@Param("accountNumber") String accountNumber);
}
