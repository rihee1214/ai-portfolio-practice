-- A physical application is accepted only as a complete, attributed transaction graph.
-- Common C RETURN financial intents without a D application remain valid and unchanged.
CREATE FUNCTION return_application_graph_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
 v_id uuid;v_app marketplace.order_return_application%ROWTYPE;
 v_request marketplace.claim_return_request%ROWTYPE;v_hold marketplace.order_unit_hold%ROWTYPE;
 v_units integer;v_history_version bigint;v_snapshot jsonb;
BEGIN
 IF TG_TABLE_NAME IN ('order_return_application','claim_return_request') THEN v_id:=NEW.id;
 ELSIF TG_TABLE_NAME='order_unit_hold' THEN
  SELECT a.id INTO v_id FROM marketplace.order_return_application a WHERE a.hold_id=NEW.id;
 ELSIF TG_TABLE_NAME='payment_refund_attempt' THEN v_id:=NEW.claim_id;
 ELSIF TG_TABLE_NAME='claim_fee_assessment' THEN v_id:=NEW.intent_id;
 ELSE v_id:=NEW.return_id;
 END IF;
 SELECT a.* INTO v_app FROM marketplace.order_return_application a WHERE a.id=v_id;
 IF NOT FOUND THEN
  IF TG_TABLE_NAME IN ('order_return_application','claim_return_request','claim_return_unit','claim_return_history') THEN
   RAISE EXCEPTION 'RETURN_APPLICATION_PROOF_MISSING' USING ERRCODE='23514';
  END IF;
  RETURN NULL;
 END IF;
 SELECT r.* INTO v_request FROM marketplace.claim_return_request r WHERE r.id=v_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'RETURN_REQUEST_GRAPH_MISSING' USING ERRCODE='23514'; END IF;
 SELECT h.* INTO v_hold FROM marketplace.order_unit_hold h WHERE h.id=v_app.hold_id;
 IF NOT FOUND OR v_hold.kind<>'RETURN' OR v_hold.order_id<>v_app.order_id OR v_hold.cause_id<>v_app.cause_id
  OR (v_request.id,v_request.order_id,v_request.seller_order_id,v_request.hold_id,v_request.cause_id)
   IS DISTINCT FROM (v_app.id,v_app.order_id,v_app.seller_order_id,v_app.hold_id,v_app.cause_id)
  OR v_request.requested_at<>v_app.requested_at THEN
  RAISE EXCEPTION 'RETURN_APPLICATION_IDENTITY_MISMATCH' USING ERRCODE='23514';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM marketplace.claim_financial_intent i
  WHERE i.id=v_id AND i.order_id=v_app.order_id AND i.seller_order_id=v_app.seller_order_id
   AND i.hold_id=v_app.hold_id AND i.cause_id=v_app.cause_id AND i.kind='RETURN') THEN
  RAISE EXCEPTION 'RETURN_FINANCIAL_IDENTITY_MISSING' USING ERRCODE='23514';
 END IF;
 IF v_request.state IN ('REJECTED','WITHDRAWN') THEN
  IF v_hold.state<>'RELEASED' OR v_hold.outcome<>v_request.state THEN
   RAISE EXCEPTION 'RETURN_RELEASE_RECEIPT_MISMATCH' USING ERRCODE='23514';
  END IF;
 ELSIF v_request.state='COMPLETED' THEN
  IF v_hold.state<>'REFUNDED' THEN RAISE EXCEPTION 'RETURN_TERMINAL_HOLD_MISSING' USING ERRCODE='23514'; END IF;
 ELSE
  IF v_hold.state<>'ACTIVE' THEN RAISE EXCEPTION 'RETURN_ACTIVE_HOLD_MISSING' USING ERRCODE='23514'; END IF;
 END IF;
 SELECT count(*) INTO v_units FROM marketplace.claim_return_unit u WHERE u.return_id=v_id;
 v_snapshot:=v_app.source_snapshot;
 IF v_units=0 OR jsonb_typeof(v_snapshot->'units') IS DISTINCT FROM 'array'
  OR jsonb_array_length(v_snapshot->'units')<>v_units
  OR (SELECT count(*) FROM marketplace.order_hold_unit u WHERE u.hold_id=v_app.hold_id)<>v_units
  OR (SELECT count(*) FROM marketplace.claim_financial_intent_unit u WHERE u.intent_id=v_id)<>v_units
  OR (SELECT count(DISTINCT (j.value->>'itemId',j.value->>'ordinal')) FROM jsonb_array_elements(v_snapshot->'units') j(value))<>v_units THEN
  RAISE EXCEPTION 'RETURN_EXACT_UNIT_SET_REQUIRED' USING ERRCODE='23514';
 END IF;
 IF v_snapshot->>'orderId' IS DISTINCT FROM v_app.order_id::text
  OR v_snapshot->>'sellerOrderId' IS DISTINCT FROM v_app.seller_order_id::text
  OR NOT EXISTS(SELECT 1 FROM marketplace.purchase_order o WHERE o.id=v_app.order_id
    AND o.checkout_id::text=v_snapshot->>'checkoutId' AND o.mode=v_snapshot->>'mode')
  OR NOT EXISTS(SELECT 1 FROM marketplace.order_aftercare_policy p WHERE p.order_id=v_app.order_id
    AND p.status='ACTIVE' AND p.claim_window_seconds=v_app.window_seconds) THEN
  RAISE EXCEPTION 'RETURN_ORIGINAL_APPLICATION_SOURCE_REQUIRED' USING ERRCODE='23514';
 END IF;
 IF EXISTS(
  SELECT 1 FROM jsonb_array_elements(v_snapshot->'units') j(value)
  LEFT JOIN marketplace.claim_return_unit u ON u.return_id=v_id AND u.item_id::text=j.value->>'itemId' AND u.ordinal::text=j.value->>'ordinal'
  LEFT JOIN marketplace.order_item i ON i.id=u.item_id AND i.order_id=v_app.order_id AND i.seller_order_id=v_app.seller_order_id
  LEFT JOIN marketplace.order_unit n ON n.item_id=u.item_id AND n.ordinal=u.ordinal
  LEFT JOIN marketplace.order_initial_delivery_unit du ON du.order_id=v_app.order_id AND du.seller_order_id=v_app.seller_order_id AND du.item_id=u.item_id AND du.ordinal=u.ordinal
  LEFT JOIN marketplace.order_initial_delivery d ON d.event_id=du.event_id
  LEFT JOIN marketplace.order_item_return_policy p ON p.item_id=u.item_id
  WHERE u.item_id IS NULL OR i.id IS NULL OR n.item_id IS NULL OR d.event_id IS NULL OR p.item_id IS NULL
   OR (j.value->>'net')::bigint IS DISTINCT FROM n.net OR j.value->>'skuId' IS DISTINCT FROM i.sku_id::text
   OR j.value->>'originalShipmentId' IS DISTINCT FROM d.shipment_id::text
   OR j.value->>'deliveryEventId' IS DISTINCT FROM d.event_id::text
   OR (j.value->>'deliveredAt')::timestamptz IS DISTINCT FROM d.occurred_at
   OR j.value->>'policyStatus' IS DISTINCT FROM p.status
   OR COALESCE(j.value->'returnPolicy','null'::jsonb) IS DISTINCT FROM COALESCE(p.policy_snapshot,'null'::jsonb)
   OR v_app.requested_at<d.occurred_at OR v_app.requested_at>=d.occurred_at+v_app.window_seconds*interval '1 second'
 ) THEN RAISE EXCEPTION 'RETURN_APPLICATION_ELIGIBILITY_NOT_PROVEN' USING ERRCODE='23514'; END IF;
 IF v_hold.state='ACTIVE' AND EXISTS(SELECT 1 FROM marketplace.claim_return_unit u
  LEFT JOIN marketplace.order_unit_lifecycle l ON l.item_id=u.item_id AND l.ordinal=u.ordinal
  WHERE u.return_id=v_id AND (l.state IS DISTINCT FROM 'HELD' OR l.active_hold_id IS DISTINCT FROM v_app.hold_id)) THEN
  RAISE EXCEPTION 'RETURN_HELD_UNIT_GRAPH_REQUIRED' USING ERRCODE='23514';
 END IF;
 IF jsonb_typeof(v_request.receipt->'units') IS DISTINCT FROM 'array'
  OR jsonb_array_length(v_request.receipt->'units')<>v_units
  OR v_request.receipt->>'returnId' IS DISTINCT FROM v_id::text
  OR v_request.receipt->>'intentId' IS DISTINCT FROM v_id::text
  OR v_request.receipt->>'holdId' IS DISTINCT FROM v_app.hold_id::text
  OR v_request.receipt->>'state' IS DISTINCT FROM 'REQUESTED'
  OR v_request.receipt->>'version' IS DISTINCT FROM '0'
  OR v_request.receipt->>'reason' IS DISTINCT FROM v_request.reason
  OR v_request.receipt->>'orderRevision' IS DISTINCT FROM v_snapshot->>'revision'
  OR (v_request.receipt->>'requestedAt')::timestamptz IS DISTINCT FROM v_app.requested_at
  OR EXISTS(SELECT 1 FROM jsonb_array_elements(v_request.receipt->'units') j(value)
    LEFT JOIN marketplace.claim_return_unit u ON u.return_id=v_id AND u.item_id::text=j.value->>'itemId' AND u.ordinal::text=j.value->>'ordinal'
    WHERE u.item_id IS NULL OR j.value->>'responsibility' IS DISTINCT FROM u.claimed_responsibility)
  OR (SELECT count(DISTINCT (j.value->>'itemId',j.value->>'ordinal')) FROM jsonb_array_elements(v_request.receipt->'units') j(value))<>v_units THEN
  RAISE EXCEPTION 'RETURN_IMMUTABLE_ACCEPTED_RECEIPT_MISMATCH' USING ERRCODE='23514';
 END IF;
 IF (SELECT count(*) FROM marketplace.claim_return_history h WHERE h.return_id=v_id AND h.version=0
    AND h.action='REQUESTED' AND h.reason=v_request.reason AND h.occurred_at=v_app.requested_at)<>1 THEN
  RAISE EXCEPTION 'RETURN_APPLICATION_HISTORY_REQUIRED' USING ERRCODE='23514';
 END IF;
 SELECT max(h.version) INTO v_history_version FROM marketplace.claim_return_history h WHERE h.return_id=v_id;
 IF v_request.version IS DISTINCT FROM v_history_version OR (v_request.version=0 AND v_request.state<>'REQUESTED') THEN
  RAISE EXCEPTION 'RETURN_CURRENT_HISTORY_VERSION_MISMATCH' USING ERRCODE='23514';
 END IF;
 IF v_request.state='REQUESTED' AND (
  EXISTS(SELECT 1 FROM marketplace.payment_refund_attempt a WHERE a.claim_id=v_id)
  OR EXISTS(SELECT 1 FROM marketplace.claim_fee_assessment a WHERE a.intent_id=v_id)
 ) THEN RAISE EXCEPTION 'RETURN_REQUEST_IS_NOT_FINANCIAL_CONSENT' USING ERRCODE='23514'; END IF;
 IF v_request.version=0 AND (SELECT count(*) FROM marketplace.job j JOIN marketplace.outbox_event e ON e.job_id=j.id
  WHERE j.type='RETURN_RECORDED.v1' AND j.dedupe_key='return-requested:'||v_id::text
   AND j.cause_key=v_id::text AND j.payload=v_id::text)<>1 THEN
  RAISE EXCEPTION 'RETURN_APPLICATION_OUTBOX_REQUIRED' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
EXCEPTION WHEN invalid_text_representation OR invalid_datetime_format OR datetime_field_overflow OR numeric_value_out_of_range THEN
 RAISE EXCEPTION 'RETURN_APPLICATION_PROOF_FORMAT' USING ERRCODE='23514';
END $$;
CREATE CONSTRAINT TRIGGER return_application_graph AFTER INSERT ON order_return_application DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_application_graph_guard();
CREATE CONSTRAINT TRIGGER return_request_graph AFTER INSERT OR UPDATE ON claim_return_request DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_application_graph_guard();
CREATE CONSTRAINT TRIGGER return_unit_graph AFTER INSERT ON claim_return_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_application_graph_guard();
CREATE CONSTRAINT TRIGGER return_history_graph AFTER INSERT ON claim_return_history DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_application_graph_guard();
CREATE CONSTRAINT TRIGGER return_hold_graph AFTER INSERT OR UPDATE ON order_unit_hold DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_application_graph_guard();
CREATE CONSTRAINT TRIGGER return_attempt_graph AFTER INSERT OR UPDATE ON payment_refund_attempt DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_application_graph_guard();
CREATE CONSTRAINT TRIGGER return_fee_graph AFTER INSERT OR UPDATE ON claim_fee_assessment DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_application_graph_guard();
