package za.co.fnb.dcre.pai.data.repo;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.pai.data.model.CreditorRef;
import za.co.fnb.dcre.pai.data.model.TxEntryView;

import java.util.List;
import java.util.UUID;

/** Read-only over CRR's spine (PAI never writes tx_entry). */
public interface TxEntryRepo extends Repository<TxEntryView, UUID> {

    /**
     * Bounded keyset page of an arrival's creditor refs (SCRUM-42): whole-
     * arrival reads at 300k tx blew CRDB's sql memory budget on CRW live;
     * each verdict slice reads only its own page inside its own transaction.
     */
    @Query(value = """
            SELECT sequence, creditor_account
            FROM tx_entry
            WHERE arrival_id = :arrivalId AND sequence > :afterSequence
            ORDER BY sequence
            LIMIT :limit""", rowMapperClass = CreditorRefMapper.class)
    List<CreditorRef> findCreditorRefsSlice(@Param("arrivalId") UUID arrivalId,
                                            @Param("afterSequence") int afterSequence,
                                            @Param("limit") int limit);
}
