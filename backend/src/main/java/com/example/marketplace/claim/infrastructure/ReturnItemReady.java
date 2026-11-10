package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.Outbox;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Immutable ITEM readiness, deliberately distinct from completing a return or its hold. */
final class ReturnItemReady {
 private ReturnItemReady() { }
 static void record(NamedParameterJdbcTemplate jdbc,Outbox outbox,UUID id,UUID attempt,long resultVersion) {
  var rows=jdbc.queryForList("SELECT r.order_id,r.seller_order_id,x.assessment_id,x.workflow_revision,x.recorded_at FROM marketplace.claim_return_request r JOIN marketplace.claim_return_financial_result x ON x.return_id=r.id JOIN marketplace.claim_fee_assessment a ON a.id=x.assessment_id JOIN marketplace.claim_fee_application app ON app.assessment_id=a.id AND app.attempt_id=x.attempt_id AND app.result_version=x.result_version WHERE r.id=:id AND x.attempt_id=:attempt AND x.result_version=:version AND x.result_state='SUCCEEDED' AND a.state='APPLIED' AND a.additional_due=0",Map.of("id",id,"attempt",attempt,"version",resultVersion));
  if(rows.isEmpty()) { return; }
  if(rows.size()!=1) { throw new OrderRejected(409); }var row=rows.getFirst();
  var p=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id",id).addValue("order",row.get("order_id")).addValue("seller",row.get("seller_order_id")).addValue("attempt",attempt).addValue("version",resultVersion).addValue("assessment",row.get("assessment_id")).addValue("revision",row.get("workflow_revision")).addValue("at",row.get("recorded_at"));
  jdbc.update("INSERT INTO marketplace.claim_return_item_ready(return_id,order_id,seller_order_id,attempt_id,result_version,assessment_id,workflow_revision,occurred_at) VALUES(:id,:order,:seller,:attempt,:version,:assessment,:revision,:at) ON CONFLICT(return_id) DO NOTHING",p);
  jdbc.update("INSERT INTO marketplace.claim_return_ready_unit(return_id,item_id,ordinal,inspection_id,collection_id,responsibility) SELECT u.return_id,u.item_id,u.ordinal,i.inspection_id,i.collection_id,i.responsibility FROM marketplace.claim_return_unit u JOIN marketplace.claim_return_inspection_unit i ON i.return_id=u.return_id AND i.item_id=u.item_id AND i.ordinal=u.ordinal WHERE u.return_id=:id ON CONFLICT(return_id,item_id,ordinal) DO NOTHING",p);
  String key="seller-aftercare:"+row.get("seller_order_id")+":return-ready:"+id;
  outbox.append(new Outbox.Event(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),new Jobs.Command(SellerAftercareWorker.TYPE,key,row.get("seller_order_id").toString(),"RETURN_READY|"+id,((Timestamp)row.get("recorded_at")).toInstant())));
 }
}
