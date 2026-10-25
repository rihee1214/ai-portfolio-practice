import com.example.marketplace.inventory.api.Reservations;
import com.example.marketplace.inventory.api.InventoryException;
import com.example.marketplace.inventory.infrastructure.JdbcReservations;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

// Review-only probe: production class files are unchanged. Uses this review's new database.
class ReservationProbe {
    public static void main(String[] args) throws Exception {
        var ds = new DriverManagerDataSource(System.getenv("REVIEW_DB_URL"), "marketplace_test", System.getenv("REVIEW_DB_PASSWORD"));
        var plain = new NamedParameterJdbcTemplate(ds);
        UUID product = plain.queryForObject("SELECT id FROM marketplace.catalog_product LIMIT 1", Map.of(), UUID.class);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        for (UUID sku : List.of(a, b)) {
            plain.update("INSERT INTO marketplace.catalog_sku(id,product_id,code,options) VALUES(:id,:product,:code,CAST(:options AS jsonb))",
                Map.of("id", sku, "product", product, "code", "sku-" + sku, "options", "[{\"name\":\"review\",\"value\":\"" + sku + "\"}]"));
            plain.update("INSERT INTO marketplace.inventory_balance(sku_id,on_hand) VALUES(:id,10)", Map.of("id", sku));
        }
        var barrier = new CyclicBarrier(2);
        var first = ThreadLocal.withInitial(() -> true);
        var jdbc = new NamedParameterJdbcTemplate(ds) {
            @Override public int update(String sql, SqlParameterSource parameters) {
                int count = super.update(sql, parameters);
                if (sql.startsWith("INSERT INTO marketplace.inventory_reservation(") && first.get()) {
                    first.set(false);
                    try { barrier.await(5, TimeUnit.SECONDS); } catch (Exception failure) { throw new IllegalStateException(failure); }
                }
                return count;
            }
        };
        var api = new JdbcReservations(jdbc, new DataSourceTransactionManager(ds));
        UUID x = UUID.randomUUID(), y = UUID.randomUUID(), checkout = UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(900);
        var left = List.of(new Reservations.Request(x, checkout, UUID.randomUUID(), a, 1, expiry),
                           new Reservations.Request(y, checkout, UUID.randomUUID(), a, 1, expiry));
        var right = List.of(new Reservations.Request(y, checkout, UUID.randomUUID(), b, 1, expiry),
                            new Reservations.Request(x, checkout, UUID.randomUUID(), b, 1, expiry));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> attempt(api, left));
            var two = pool.submit(() -> attempt(api, right));
            System.out.println("LEFT=" + one.get(15, TimeUnit.SECONDS));
            System.out.println("RIGHT=" + two.get(15, TimeUnit.SECONDS));
        }
        System.out.println("PROBE_BALANCES=" + plain.queryForList("SELECT sku_id,on_hand,reserved,version FROM marketplace.inventory_balance WHERE sku_id IN (:a,:b)", Map.of("a", a, "b", b)));
        System.out.println("PROBE_ROWS=" + plain.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation WHERE id IN (:x,:y)", Map.of("x", x, "y", y), Integer.class));
    }
    private static String attempt(Reservations api, List<Reservations.Request> requests) {
        try { api.reserve(requests); return "ok"; }
        catch (InventoryException conflict) { return "InventoryException:" + conflict.status(); }
        catch (RuntimeException failure) {
            Throwable root = failure;
            while (root.getCause() != null) root = root.getCause();
            return failure.getClass().getSimpleName() + ":" + (root instanceof java.sql.SQLException sql ? sql.getSQLState() : root.getClass().getSimpleName());
        }
    }
}
