package com.example.marketplace.dbtest;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
/** Test-only claim predicate. The production JdbcJobs transactions, lease/fencing SQL and WorkerEngine remain unchanged. */
final class OneJobJdbc extends NamedParameterJdbcTemplate {
 private final UUID id;
 OneJobJdbc(DataSource data,UUID id) { super(data);this.id=id; }
 private String scoped(String sql) { return sql.startsWith("WITH candidate AS")||sql.startsWith("WITH exhausted AS")?sql.replace("WHERE type IN (:types)","WHERE id='"+id+"' AND type IN (:types)"):sql; }
 @Override public <T> List<T> query(String sql,SqlParameterSource parameters,RowMapper<T> mapper) { return super.query(scoped(sql),parameters,mapper); }
 @Override public int update(String sql,SqlParameterSource parameters) { return super.update(scoped(sql),parameters); }
}
