package za.co.fnb.dcre.pai.data.repo;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.pai.data.model.UnknownCreditorEntity;

import java.util.UUID;

/**
 * PAI's own writer over its own relation. Extends Repository, not CrudRepository: the
 * derived save() path would expose an update this table has no meaning for, and the one
 * insert below is the only write PAI performs anywhere.
 */
public interface UnknownCreditorRepo extends Repository<UnknownCreditorEntity, UUID> {

    /**
     * Records that {@code accountNumber} was absent from the account master when
     * {@code arrivalId} was initialised. Insert-once on the business identity, the
     * account number: a later arrival crediting the same account does not overwrite the
     * first sighting, and a restarted slice no-ops over its own committed rows rather
     * than duplicating them.
     *
     * <p>ON CONFLICT (account_number) DO NOTHING, never UPSERT: CockroachDB resolves
     * UPSERT on the PRIMARY KEY only, and the PK here is a generated UUID, so an UPSERT
     * would behave as a plain INSERT and violate the unique constraint on re-run.
     */
    @Modifying
    @Query("""
            INSERT INTO unknown_creditor (id, account_number, arrival_id)
            VALUES (gen_random_uuid(), :accountNumber, :arrivalId)
            ON CONFLICT (account_number) DO NOTHING""")
    void recordIfAbsent(@Param("arrivalId") UUID arrivalId,
                        @Param("accountNumber") String accountNumber);
}
