package za.co.fnb.dcre.pai.data.repo;

import org.springframework.jdbc.core.RowMapper;
import za.co.fnb.dcre.pai.data.model.CreditorRef;

import java.sql.ResultSet;
import java.sql.SQLException;

public class CreditorRefMapper implements RowMapper<CreditorRef> {

    @Override
    public CreditorRef mapRow(ResultSet r, int rowNum) throws SQLException {
        return new CreditorRef(r.getInt("sequence"), r.getString("creditor_account"));
    }
}
