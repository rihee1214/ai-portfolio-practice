-- Current SUPPORT commands preserve the immutable first consent and chain the actual financial operation.
CREATE TABLE claim_exchange_refund_support_command (
 id uuid PRIMARY KEY,exchange_id uuid NOT NULL REFERENCES claim_exchange_request(id),assessment_id uuid NOT NULL REFERENCES claim_fee_assessment(id),
 previous_attempt_id uuid NOT NULL REFERENCES payment_refund_attempt(id),attempt_id uuid NOT NULL REFERENCES payment_refund_attempt(id),
 purpose varchar(10) NOT NULL CHECK(purpose='ITEM'),action varchar(20) NOT NULL CHECK(action IN ('RETRY_FAILED','QUERY_UNKNOWN')),
 request_key varchar(100) NOT NULL,fingerprint varchar(64) NOT NULL,actor_id uuid NOT NULL REFERENCES identity_user(id),actor_version bigint NOT NULL,
 reason varchar(500) NOT NULL CHECK(length(btrim(reason))>0),original_revision bigint NOT NULL CHECK(original_revision>=0),revision bigint NOT NULL CHECK(revision=original_revision+1),
 original_result_version bigint NOT NULL CHECK(original_result_version>0),fee_command_id uuid UNIQUE REFERENCES claim_fee_support_command(id),query_cause_id uuid,
 receipt jsonb NOT NULL,accepted_at timestamptz NOT NULL,created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
 UNIQUE(exchange_id,request_key),UNIQUE(exchange_id,revision),
 FOREIGN KEY(exchange_id,request_key) REFERENCES claim_exchange_command(exchange_id,command_key) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(exchange_id,revision) REFERENCES claim_exchange_history(exchange_id,version) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(query_cause_id,attempt_id) REFERENCES payment_refund_query_request(cause_id,attempt_id),
 CHECK((action='RETRY_FAILED' AND fee_command_id=id AND fee_command_id IS NOT NULL AND query_cause_id IS NULL AND previous_attempt_id<>attempt_id)
  OR(action='QUERY_UNKNOWN' AND fee_command_id IS NULL AND query_cause_id=id AND query_cause_id IS NOT NULL AND previous_attempt_id=attempt_id))
);
CREATE TRIGGER exchange_refund_support_command_immutable BEFORE UPDATE OR DELETE ON claim_exchange_refund_support_command FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE FUNCTION exchange_refund_support_effect_guard() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE v_valid boolean;
BEGIN
 SELECT EXISTS(SELECT 1 FROM marketplace.claim_exchange_request r
  JOIN marketplace.claim_exchange_refund_consent consent ON consent.exchange_id=r.id
  JOIN marketplace.claim_fee_assessment a ON a.id=consent.assessment_id AND a.id=NEW.assessment_id
  JOIN marketplace.claim_fee_binding b ON b.assessment_id=a.id AND b.attempt_id=NEW.attempt_id
  JOIN marketplace.payment_refund_attempt old_attempt ON old_attempt.id=NEW.previous_attempt_id
  JOIN marketplace.payment_refund_attempt next_attempt ON next_attempt.id=NEW.attempt_id
  JOIN marketplace.claim_exchange_history previous ON previous.exchange_id=r.id AND previous.version=NEW.original_revision
  JOIN marketplace.claim_exchange_history h ON h.exchange_id=r.id AND h.version=NEW.revision
  JOIN marketplace.claim_exchange_command cmd ON cmd.exchange_id=r.id AND cmd.command_key=NEW.request_key
  WHERE r.id=NEW.exchange_id AND r.version>=NEW.revision AND a.intent_kind='EXCHANGE_REFUND' AND a.intent_id=r.id AND a.order_id=r.order_id AND a.seller_order_id=r.seller_order_id AND a.state='BOUND'
   AND previous.action IN ('REFUND_PENDING','REVIEW_REQUIRED') AND cmd.version=NEW.revision AND cmd.fingerprint=NEW.fingerprint AND cmd.receipt=NEW.receipt->'progress'
   AND h.actor='SUPPORT:'||NEW.actor_id::text AND h.reason=NEW.reason AND h.occurred_at=NEW.accepted_at
   AND h.action=CASE WHEN NEW.action='RETRY_FAILED' THEN 'REFUND_PENDING' ELSE 'REVIEW_REQUIRED' END AND cmd.action=h.action
   AND NEW.receipt->>'commandId'=NEW.id::text AND NEW.receipt->>'exchangeId'=r.id::text AND NEW.receipt->>'refundAttemptId'=next_attempt.id::text
   AND NEW.receipt->>'purpose'=NEW.purpose AND NEW.receipt->>'action'=NEW.action AND (NEW.receipt->>'revision')::bigint=NEW.revision
   AND old_attempt.claim_id=r.id AND old_attempt.order_id=r.order_id AND old_attempt.seller_order_id=r.seller_order_id AND old_attempt.purpose=NEW.purpose AND old_attempt.version=NEW.original_result_version
   AND next_attempt.claim_id=r.id AND next_attempt.order_id=r.order_id AND next_attempt.seller_order_id=r.seller_order_id AND next_attempt.purpose=NEW.purpose
   AND next_attempt.amount=old_attempt.amount AND next_attempt.amount=a.refund AND next_attempt.payment_id=old_attempt.payment_id
   AND next_attempt.original_reference=old_attempt.original_reference AND next_attempt.original_reference=a.quote_fingerprint
   AND ((NEW.action='RETRY_FAILED' AND old_attempt.state='FAILED' AND next_attempt.state='RESERVED' AND next_attempt.cause_id=NEW.id AND next_attempt.sequence=old_attempt.sequence+1
     AND EXISTS(SELECT 1 FROM marketplace.claim_fee_support_command fee WHERE fee.id=NEW.fee_command_id AND fee.id=NEW.id AND fee.assessment_id=a.id AND fee.previous_attempt_id=old_attempt.id AND fee.attempt_id=next_attempt.id AND fee.actor_id=NEW.actor_id AND fee.reason=NEW.reason AND fee.revision=a.version))
    OR(NEW.action='QUERY_UNKNOWN' AND old_attempt.state IN ('UNKNOWN','PROCESSING') AND old_attempt.id=next_attempt.id
     AND EXISTS(SELECT 1 FROM marketplace.payment_refund_query_request q WHERE q.cause_id=NEW.query_cause_id AND q.cause_id=NEW.id AND q.attempt_id=old_attempt.id AND q.original_version=old_attempt.version AND q.original_state=old_attempt.state AND q.reason=NEW.reason)))
 ) INTO v_valid;
 IF NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='EXCHANGE_RECORDED.v1' AND j.dedupe_key='exchange-refund-support-ITEM-'||NEW.revision::text||':'||NEW.exchange_id::text AND j.cause_key=NEW.exchange_id::text AND j.payload=NEW.exchange_id::text)
 THEN RAISE EXCEPTION 'EXACT_EXCHANGE_REFUND_SUPPORT_OUTBOX_REQUIRED' USING ERRCODE='23514';END IF;
 IF NOT v_valid THEN RAISE EXCEPTION 'EXCHANGE_REFUND_SUPPORT_CURRENT_OPERATION_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER exchange_refund_support_effect_receipt AFTER INSERT ON claim_exchange_refund_support_command DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_refund_support_effect_guard();
CREATE FUNCTION exchange_refund_support_primitive_guard() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE v_return uuid;v_command uuid;
BEGIN
 IF TG_TABLE_NAME='claim_fee_support_command' THEN
  SELECT c.exchange_id INTO v_return FROM marketplace.claim_exchange_refund_consent c WHERE c.assessment_id=NEW.assessment_id;v_command:=NEW.id;
 ELSE
  SELECT c.exchange_id INTO v_return FROM marketplace.claim_exchange_refund_consent c JOIN marketplace.claim_fee_binding_history b ON b.assessment_id=c.assessment_id WHERE b.attempt_id=NEW.attempt_id;v_command:=NEW.cause_id;
 END IF;
 IF v_return IS NOT NULL AND NOT EXISTS(SELECT 1 FROM marketplace.claim_exchange_refund_support_command s WHERE s.id=v_command AND s.exchange_id=v_return) THEN RAISE EXCEPTION 'EXCHANGE_REFUND_SUPPORT_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER exchange_refund_support_retry_primitive AFTER INSERT ON claim_fee_support_command DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_refund_support_primitive_guard();
CREATE CONSTRAINT TRIGGER exchange_refund_support_query_primitive AFTER INSERT ON payment_refund_query_request DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_refund_support_primitive_guard();

CREATE FUNCTION exact_exchange_refund_financial_revision(p_id uuid,p_revision bigint,p_state text) RETURNS boolean LANGUAGE sql STABLE SET search_path TO marketplace,public AS $$
 SELECT EXISTS(SELECT 1 FROM claim_exchange_refund_consent c WHERE c.exchange_id=p_id)
 AND (EXISTS(SELECT 1 FROM claim_exchange_refund_effect e WHERE e.exchange_id=p_id AND e.workflow_revision=p_revision AND e.mode=p_state AND e.mode IN('REVIEW_REQUIRED','SETTLEMENT_PENDING'))
 OR EXISTS(SELECT 1 FROM claim_exchange_refund_support_command s JOIN claim_exchange_history h ON h.exchange_id=s.exchange_id AND h.version=s.revision
  WHERE s.exchange_id=p_id AND s.revision=p_revision AND s.action='QUERY_UNKNOWN' AND h.action=p_state AND p_state='REVIEW_REQUIRED'))
$$;

-- Latest applied physical guard V85 is retained in full; only exact financial revision classification changes.
CREATE OR REPLACE FUNCTION exchange_physical_graph_guard() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE v_id uuid;a order_exchange_application;c claim_exchange_request;n integer;v_lost boolean;v_ready boolean;v_inspection uuid;h claim_exchange_inspection;v_saleable integer;
BEGIN
 IF TG_TABLE_NAME IN ('order_exchange_application','claim_exchange_request') THEN v_id=NEW.id;
 ELSIF TG_TABLE_NAME='inventory_exchange_reservation_physical_source' THEN SELECT exchange_id INTO v_id FROM inventory_exchange_reservation WHERE id=NEW.reservation_id;
 ELSIF TG_TABLE_NAME IN ('inventory_restitution','inventory_restitution_unit','inventory_restitution_movement','inventory_source_restitution') THEN
  SELECT * INTO h FROM claim_exchange_inspection WHERE id=NEW.cause_id;
  IF h.id IS NULL THEN
   IF EXISTS(SELECT 1 FROM inventory_restitution WHERE cause_id=NEW.cause_id AND kind='EXCHANGE') THEN RAISE EXCEPTION 'ACTUAL_EXCHANGE_PHYSICAL_INSPECTION_REQUIRED' USING ERRCODE='23514';END IF;
   RETURN NULL;
  END IF;v_id=h.exchange_id;
 ELSIF TG_TABLE_NAME IN ('inventory_physical_inbound','inventory_physical_inbound_unit') THEN
  IF EXISTS(SELECT 1 FROM inventory_physical_inbound r WHERE r.inspection_id=NEW.inspection_id AND r.kind='RETURN') THEN RETURN NULL;END IF;
  SELECT * INTO h FROM claim_exchange_inspection WHERE id=NEW.inspection_id;
  IF h.id IS NULL THEN RAISE EXCEPTION 'ACTUAL_EXCHANGE_PHYSICAL_INSPECTION_REQUIRED' USING ERRCODE='23514';END IF;v_id=h.exchange_id;
 ELSE v_id=NEW.exchange_id;END IF;
 SELECT * INTO a FROM order_exchange_application WHERE id=v_id;SELECT * INTO c FROM claim_exchange_request WHERE id=v_id;
 IF a.id IS NULL OR c.id IS NULL THEN RAISE EXCEPTION 'ACCEPTED_EXCHANGE_PHYSICAL_GRAPH_REQUIRED' USING ERRCODE='23514';END IF;
 SELECT count(*) INTO n FROM claim_exchange_unit WHERE exchange_id=v_id;
 IF n=0 OR n<>(SELECT count(*) FROM order_exchange_physical_unit WHERE exchange_id=v_id)
 OR a.store_id IS DISTINCT FROM (SELECT store_id FROM seller_order WHERE id=a.seller_order_id)
 OR EXISTS(SELECT 1 FROM order_exchange_physical_unit u JOIN order_physical_source p ON p.id=u.physical_id JOIN inventory_committed_unit s ON s.id=p.committed_source_id LEFT JOIN order_physical_delivery d ON d.physical_id=p.id WHERE u.exchange_id=v_id AND(
  (p.order_id,p.seller_order_id,p.item_id,p.ordinal) IS DISTINCT FROM (a.order_id,a.seller_order_id,u.item_id,u.ordinal) OR d.physical_id IS NULL OR a.requested_at>=d.delivered_at+make_interval(secs=>(a.source_snapshot->>'windowSeconds')::double precision)
  OR u.source_snapshot->>'id'<>p.id::text OR u.source_snapshot->>'orderId'<>p.order_id::text OR u.source_snapshot->>'sellerOrderId'<>p.seller_order_id::text OR u.source_snapshot->>'itemId'<>p.item_id::text OR (u.source_snapshot->>'ordinal')::integer<>p.ordinal OR (u.source_snapshot->>'round')::integer<>p.round OR u.source_snapshot->'stock'->>'id'<>s.id::text OR u.source_snapshot->'stock'->>'skuId'<>s.sku_id::text OR u.source_snapshot->'delivery'->>'bucketId'<>d.bucket_id::text OR u.source_snapshot->'delivery'->>'eventId'<>d.event_id::text
 )) THEN RAISE EXCEPTION 'EXACT_FIXED_ACCEPTED_PHYSICAL_SOURCE_REQUIRED' USING ERRCODE='23514';END IF;
 IF c.approved_at IS NOT NULL THEN
  IF EXISTS(SELECT 1 FROM inventory_exchange_reservation r LEFT JOIN inventory_exchange_reservation_physical_source x ON x.reservation_id=r.id LEFT JOIN order_exchange_physical_unit u ON u.exchange_id=r.exchange_id AND u.item_id=r.item_id AND u.ordinal=r.ordinal LEFT JOIN order_physical_source p ON p.id=u.physical_id WHERE r.exchange_id=v_id AND(x.reservation_id IS NULL OR x.physical_id IS DISTINCT FROM p.id OR x.source_id IS DISTINCT FROM p.committed_source_id))
  OR jsonb_array_length(c.approved_offers)<>(SELECT count(DISTINCT target_sku_id) FROM claim_exchange_unit WHERE exchange_id=v_id)
  OR EXISTS(SELECT 1 FROM claim_exchange_unit u JOIN order_item i ON i.id=u.item_id WHERE u.exchange_id=v_id AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(c.approved_offers) o WHERE o->>'skuId'=u.target_sku_id::text AND jsonb_typeof(o->'price')='string' AND o->>'price'=i.unit_price::text AND o->>'storeId'=a.store_id::text))
  THEN RAISE EXCEPTION 'EXACT_EXCHANGE_APPROVAL_SOURCE_AND_PRICE_REQUIRED' USING ERRCODE='23514';END IF;
 END IF;
 SELECT COALESCE(bool_or(x.status='LOST_REPORTED'),false),COALESCE(bool_and(x.status='RECEIVED' AND i.inspection_id IS NOT NULL),false) INTO v_lost,v_ready FROM claim_exchange_unit u LEFT JOIN LATERAL(SELECT status FROM claim_exchange_collection x WHERE x.exchange_id=u.exchange_id AND x.item_id=u.item_id AND x.ordinal=u.ordinal ORDER BY version DESC LIMIT 1)x ON true LEFT JOIN claim_exchange_inspection_unit i ON i.exchange_id=u.exchange_id AND i.item_id=u.item_id AND i.ordinal=u.ordinal WHERE u.exchange_id=v_id;
 IF EXISTS(SELECT 1 FROM claim_exchange_collection WHERE exchange_id=v_id) THEN
  IF c.state NOT IN ('DISPATCH_PENDING','DISPATCHED','COMPLETED','REFUND_PENDING','REFUNDED','EXPIRED') AND NOT exact_exchange_refund_financial_revision(c.id,c.version,c.state) AND c.state IS DISTINCT FROM (CASE WHEN v_lost THEN 'REVIEW_REQUIRED' WHEN v_ready AND c.approved_at IS NOT NULL THEN 'READY' ELSE 'COLLECTING' END) THEN RAISE EXCEPTION 'CURRENT_EXCHANGE_PHYSICAL_PROGRESS_REQUIRED' USING ERRCODE='23514';END IF;
 END IF;
 IF EXISTS(SELECT 1 FROM claim_exchange_collection x LEFT JOIN claim_exchange_command z ON z.exchange_id=x.exchange_id AND z.version=x.version LEFT JOIN claim_exchange_history t ON t.exchange_id=x.exchange_id AND t.version=x.version WHERE x.exchange_id=v_id AND(z.exchange_id IS NULL OR t.id IS NULL OR t.action<>z.action OR t.actor<>x.actor OR t.reason<>x.reason OR t.occurred_at<>x.occurred_at)) THEN RAISE EXCEPTION 'EXCHANGE_COLLECTION_OPERATION_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;
 FOR h IN SELECT * FROM claim_exchange_inspection WHERE exchange_id=v_id LOOP
  SELECT count(*) FILTER(WHERE condition='SALEABLE') INTO v_saleable FROM claim_exchange_inspection_unit WHERE inspection_id=h.id;
  IF NOT EXISTS(SELECT 1 FROM claim_exchange_command z JOIN claim_exchange_history t ON t.exchange_id=z.exchange_id AND t.version=z.version WHERE z.exchange_id=v_id AND z.version=h.version AND t.action=z.action AND t.actor=h.actor AND t.reason=h.reason AND t.occurred_at=h.occurred_at)
  OR NOT EXISTS(SELECT 1 FROM claim_exchange_inspection_unit WHERE inspection_id=h.id)
  OR EXISTS(SELECT 1 FROM claim_exchange_inspection_unit i JOIN claim_exchange_collection x ON x.id=i.collection_id WHERE i.inspection_id=h.id AND(i.physical_id<>x.physical_id OR x.status<>'RECEIVED' OR x.version>=h.version))
  THEN RAISE EXCEPTION 'ACTUAL_RECEIVED_EXCHANGE_INSPECTION_REQUIRED' USING ERRCODE='23514';END IF;
  IF v_saleable=0 THEN
   IF EXISTS(SELECT 1 FROM inventory_physical_inbound WHERE inspection_id=h.id) OR EXISTS(SELECT 1 FROM inventory_restitution WHERE cause_id=h.id) THEN RAISE EXCEPTION 'DEFECTIVE_EXCHANGE_CANNOT_RESTOCK' USING ERRCODE='23514';END IF;
  ELSE
   IF NOT EXISTS(SELECT 1 FROM inventory_physical_inbound r JOIN order_exchange_physical_unit u ON u.exchange_id=h.exchange_id JOIN order_physical_source p ON p.id=u.physical_id JOIN inventory_committed_unit s ON s.id=p.committed_source_id WHERE r.inspection_id=h.id AND r.claim_id=h.exchange_id AND r.kind='EXCHANGE' AND r.checkout_id=s.checkout_id)
   OR v_saleable<>(SELECT count(*) FROM inventory_physical_inbound_unit WHERE inspection_id=h.id)
   OR EXISTS(SELECT 1 FROM claim_exchange_inspection_unit i JOIN order_physical_source p ON p.id=i.physical_id WHERE i.inspection_id=h.id AND i.condition='SALEABLE' AND NOT EXISTS(SELECT 1 FROM inventory_physical_inbound_unit u JOIN inventory_source_restitution x ON x.source_id=u.source_id AND x.cause_id=u.inspection_id WHERE u.inspection_id=h.id AND u.physical_id=i.physical_id AND u.source_id=p.committed_source_id))
   OR EXISTS(SELECT 1 FROM inventory_physical_inbound_unit u WHERE u.inspection_id=h.id AND NOT EXISTS(SELECT 1 FROM claim_exchange_inspection_unit i WHERE i.inspection_id=h.id AND i.physical_id=u.physical_id AND i.condition='SALEABLE'))
   OR (SELECT count(*) FROM inventory_restitution_movement WHERE cause_id=h.id)<>(SELECT count(DISTINCT s.sku_id) FROM inventory_physical_inbound_unit u JOIN inventory_committed_unit s ON s.id=u.source_id WHERE u.inspection_id=h.id)
   OR EXISTS(SELECT 1 FROM inventory_restitution_movement m WHERE m.cause_id=h.id AND m.quantity<>(SELECT count(*) FROM inventory_physical_inbound_unit u JOIN inventory_committed_unit s ON s.id=u.source_id WHERE u.inspection_id=h.id AND s.sku_id=m.sku_id))
   THEN RAISE EXCEPTION 'EXACT_EXCHANGE_SALEABLE_INBOUND_REQUIRED' USING ERRCODE='23514';END IF;
  END IF;
 END LOOP;RETURN NULL;
END $$;
