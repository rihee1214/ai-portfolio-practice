package com.example.marketplace.jdbcfixture;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public class AccountRepository {
    private static final RowMapper<Account> ACCOUNT = (row, number) -> new Account(
            row.getLong("id"), row.getString("label"), row.getBigDecimal("balance"), row.getString("note"));
    private final NamedParameterJdbcTemplate jdbc;

    public AccountRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Account> findByLabel(String label) {
        return jdbc.query("SELECT id, label, balance, note FROM c3_fixture.account WHERE label = :label",
                Map.of("label", label), ACCOUNT).stream().findFirst();
    }

    public int changeBalance(long id, BigDecimal delta) {
        return jdbc.update("UPDATE c3_fixture.account SET balance = balance + :delta WHERE id = :id",
                Map.of("id", id, "delta", delta));
    }

    public void recordTransfer(long from, long to, BigDecimal amount, String reference) {
        jdbc.update("""
                INSERT INTO c3_fixture.transfer_entry (reference, from_id, to_id, amount)
                VALUES (:reference, :from, :to, :amount)
                """, Map.of("reference", reference, "from", from, "to", to, "amount", amount));
    }
}
