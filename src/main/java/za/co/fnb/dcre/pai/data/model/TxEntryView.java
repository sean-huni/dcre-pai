package za.co.fnb.dcre.pai.data.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/** Read model over CRR's tx_entry (repository anchor only; PAI never writes it). */
@Table("tx_entry")
public class TxEntryView {

    @Id
    private UUID id;
}
