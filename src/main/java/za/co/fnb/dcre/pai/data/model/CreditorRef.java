package za.co.fnb.dcre.pai.data.model;

/** Read projection of one tx_entry row: the creditor account to init. */
public record CreditorRef(int sequence, String creditorAccount) {
}
