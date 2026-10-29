package com.example.marketplace.payment.infrastructure;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.payment.api.PaymentQueries;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Service @Profile("db")
public final class JdbcPaymentQueries implements PaymentQueries {
    private final NamedParameterJdbcTemplate jdbc;private final OrderQueries orders;private final com.example.marketplace.platform.api.Jobs jobs;private final TransactionTemplate tx;
    public JdbcPaymentQueries(NamedParameterJdbcTemplate jdbc,OrderQueries orders,com.example.marketplace.platform.api.Jobs jobs,PlatformTransactionManager manager) { this.jdbc=jdbc;this.orders=orders;this.jobs=jobs;this.tx=new TransactionTemplate(manager); }
    @Override public History history(String number,OrderQueries.Authority authority) {
        return tx.execute(s->{var owned=orders.requireOwned(number,authority);var p=Map.of("id",owned.id());
            var rows=jdbc.query("SELECT id,state,compensation,review_required,query_count,round_started_at,next_job FROM marketplace.payment_attempt WHERE order_id=:id",p,(r,n)->{
                List<Event> events=jdbc.query("SELECT action,occurred_at FROM marketplace.payment_event WHERE attempt_id=:id ORDER BY occurred_at,id",Map.of("id",r.getObject("id")),(e,i)->new Event(e.getString("action"),e.getTimestamp("occurred_at").toInstant()));
                boolean pending=com.example.marketplace.payment.domain.PaymentRules.uncertain(r.getString("state"))||List.of("PENDING","UNKNOWN","FAILED").contains(r.getString("compensation"));
                var job=r.getObject("next_job",java.util.UUID.class);
                boolean review=r.getBoolean("review_required")||pending&&(com.example.marketplace.payment.domain.PaymentRules.review(r.getInt("query_count"),r.getTimestamp("round_started_at").toInstant(),java.time.Instant.now())||job!=null&&jobs.find(job).map(j->j.status().equals("FAILED")).orElse(false));
                return new History(number,owned.state(),r.getString("state"),r.getString("compensation"),review,owned.amount(),owned.mode(),events);
            });return rows.isEmpty()?new History(number,owned.state(),"NONE","NONE",false,owned.amount(),owned.mode(),List.of()):rows.getFirst();});
    }
}
