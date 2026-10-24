package com.example.marketplace.identity.infrastructure;

import com.example.marketplace.identity.api.AccountException;
import com.example.marketplace.identity.api.AccountOperations;
import com.example.marketplace.identity.domain.AccountRules;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Member row locks serialize every address mutation, including the first insert and the capacity check. */
public final class JdbcAccountOperations implements AccountOperations {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private static final RowMapper<Address> ADDRESS = (row, index) -> new Address(
            row.getObject("id", UUID.class), row.getString("label"), row.getString("recipient"),
            row.getString("phone"), row.getString("postal_code"), row.getString("address_line1"),
            row.getString("address_line2"), row.getBoolean("is_default"));

    public JdbcAccountOperations(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
    }

    @Override public Profile profile(UUID member) {
        var result = jdbc.query("SELECT email,display_name,phone FROM marketplace.identity_user WHERE id=:member",
                Map.of("member", member), (row, index) -> new Profile(row.getString(1), row.getString(2), row.getString(3)));
        if (result.isEmpty()) { throw new AccountException("ACCOUNT_NOT_FOUND"); }
        return result.getFirst();
    }

    @Override public Profile updateProfile(UUID member, String displayName, String phone) {
        String name = AccountRules.text(displayName, 1, 80);
        String contact = AccountRules.phone(phone, false);
        return transaction.execute(status -> {
            lock(member);
            jdbc.update("UPDATE marketplace.identity_user SET display_name=:name,phone=:phone WHERE id=:member",
                    new MapSqlParameterSource("member", member).addValue("name", name).addValue("phone", contact));
            return profile(member);
        });
    }

    @Override public List<Address> addresses(UUID member) {
        return jdbc.query("SELECT * FROM marketplace.member_address WHERE user_id=:member ORDER BY created_at,id",
                Map.of("member", member), ADDRESS);
    }

    @Override public Address saveAddress(UUID member, UUID id, AddressInput input) {
        AddressInput clean = AccountRules.address(input);
        return transaction.execute(status -> {
            lock(member);
            var existing = addresses(member);
            Address previous = id == null ? null : existing.stream().filter(item -> item.id().equals(id))
                    .findFirst().orElseThrow(() -> new AccountException("ADDRESS_NOT_FOUND"));
            if (id == null && existing.size() >= 20) { throw new AccountException("ADDRESS_LIMIT"); }
            boolean makeDefault = clean.isDefault() || existing.isEmpty() || (previous != null && previous.isDefault());
            if (makeDefault) {
                jdbc.update("UPDATE marketplace.member_address SET is_default=false WHERE user_id=:member AND is_default",
                        Map.of("member", member));
            }
            UUID savedId = id == null ? UUID.randomUUID() : id;
            var parameters = new MapSqlParameterSource("member", member).addValue("id", savedId)
                    .addValue("label", clean.label()).addValue("recipient", clean.recipient())
                    .addValue("phone", clean.phone()).addValue("postal", clean.postalCode())
                    .addValue("line1", clean.addressLine1()).addValue("line2", clean.addressLine2())
                    .addValue("default", makeDefault);
            if (id == null) {
                jdbc.update("""
                        INSERT INTO marketplace.member_address
                        (id,user_id,label,recipient,phone,postal_code,address_line1,address_line2,is_default)
                        VALUES(:id,:member,:label,:recipient,:phone,:postal,:line1,:line2,:default)
                        """, parameters);
            } else {
                jdbc.update("""
                        UPDATE marketplace.member_address SET label=:label,recipient=:recipient,phone=:phone,
                        postal_code=:postal,address_line1=:line1,address_line2=:line2,is_default=:default
                        WHERE id=:id AND user_id=:member
                        """, parameters);
            }
            return new Address(savedId, clean.label(), clean.recipient(), clean.phone(), clean.postalCode(),
                    clean.addressLine1(), clean.addressLine2(), makeDefault);
        });
    }

    @Override public void deleteAddress(UUID member, UUID id) {
        transaction.executeWithoutResult(status -> {
            lock(member);
            if (jdbc.update("DELETE FROM marketplace.member_address WHERE id=:id AND user_id=:member",
                    Map.of("member", member, "id", id)) == 0) { throw new AccountException("ADDRESS_NOT_FOUND"); }
            var remaining = addresses(member);
            if (!remaining.isEmpty() && remaining.stream().noneMatch(Address::isDefault)) {
                jdbc.update("UPDATE marketplace.member_address SET is_default=true WHERE id=:id AND user_id=:member",
                        Map.of("member", member, "id", remaining.getFirst().id()));
            }
        });
    }

    private void lock(UUID member) {
        if (jdbc.query("SELECT id FROM marketplace.identity_user WHERE id=:member AND state='ACTIVE' FOR UPDATE",
                Map.of("member", member), (row, index) -> row.getObject(1)).isEmpty()) {
            throw new AccountException("ACCOUNT_NOT_FOUND");
        }
    }
}
