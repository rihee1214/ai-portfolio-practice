-- Connect existing V76/V77 evidence to the typed registry without rewriting immutable JSON or stock effects.
SELECT inventory_ensure_purchase_source(p.reservation_id,p.ordinal) FROM order_physical_unit p;
INSERT INTO order_physical_source(id,order_id,seller_order_id,item_id,ordinal,round,original_physical_id,committed_source_id,created_at)
 SELECT p.id,p.order_id,p.seller_order_id,p.item_id,p.ordinal,0,p.id,inventory_purchase_source_id(p.reservation_id,p.ordinal),p.delivered_at FROM order_physical_unit p;
INSERT INTO order_delivery_bucket(id,order_id,seller_order_id,kind) SELECT DISTINCT delivery_bucket_id,order_id,seller_order_id,'BASIC' FROM order_physical_unit;
INSERT INTO order_basic_delivery_bucket(bucket_id,shipment_id) SELECT id,id FROM order_delivery_bucket WHERE kind='BASIC';
INSERT INTO order_physical_delivery(physical_id,bucket_id,event_id,delivered_at) SELECT id,delivery_bucket_id,delivery_event_id,delivered_at FROM order_physical_unit;
INSERT INTO order_physical_current(item_id,ordinal,physical_id) SELECT item_id,ordinal,id FROM order_physical_unit;
INSERT INTO order_exchange_physical_unit(exchange_id,item_id,ordinal,physical_id,source_schema_version,source_snapshot)
 SELECT u.exchange_id,p.item_id,p.ordinal,p.id,1,jsonb_build_object('id',p.id,'orderId',p.order_id,'sellerOrderId',p.seller_order_id,'itemId',p.item_id,'ordinal',p.ordinal,'round',0,'predecessorId',NULL,'originalPhysicalId',p.id,
 'stock',jsonb_build_object('id',inventory_purchase_source_id(p.reservation_id,p.ordinal),'kind','PURCHASE','reservationId',p.reservation_id,'reservationUnit',p.ordinal,'checkoutId',p.checkout_id,'itemId',p.item_id,'ordinal',p.ordinal,'skuId',p.sku_id),
 'delivery',jsonb_build_object('bucketId',p.delivery_bucket_id,'kind','BASIC','shipmentId',p.delivery_bucket_id,'eventId',p.delivery_event_id,'deliveredAt',p.delivered_at))
 FROM order_exchange_unit u JOIN order_physical_unit p ON p.id=u.physical_id;
INSERT INTO inventory_exchange_reservation_physical_source(reservation_id,physical_id,source_id)
 SELECT r.id,p.id,inventory_purchase_source_id(p.reservation_id,p.ordinal) FROM inventory_exchange_reservation r JOIN order_physical_unit p ON p.id=r.physical_id;
CREATE FUNCTION verify_order_physical_source(p_id uuid) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE p order_physical_source;s inventory_committed_unit;o order_physical_unit;
BEGIN
 SELECT * INTO p FROM order_physical_source WHERE id=p_id;SELECT * INTO s FROM inventory_committed_unit WHERE id=p.committed_source_id;SELECT * INTO o FROM order_physical_unit WHERE id=p.original_physical_id;
 IF p.id IS NULL OR s.id IS NULL OR o.id IS NULL OR (p.order_id,p.seller_order_id,p.item_id,p.ordinal) IS DISTINCT FROM (o.order_id,o.seller_order_id,o.item_id,o.ordinal) OR (s.item_id,s.ordinal,s.checkout_id) IS DISTINCT FROM (p.item_id,p.ordinal,o.checkout_id) THEN RAISE EXCEPTION 'EXACT_ORDER_PHYSICAL_SOURCE_REQUIRED' USING ERRCODE='23514';END IF;
 IF p.round=0 THEN
  IF p.id<>o.id OR p.predecessor_id IS NOT NULL OR s.kind<>'PURCHASE' OR s.sku_id<>o.sku_id OR NOT EXISTS(SELECT 1 FROM inventory_purchase_source x WHERE x.source_id=s.id AND x.reservation_id=o.reservation_id AND x.reservation_unit=o.ordinal)
  OR NOT EXISTS(SELECT 1 FROM order_initial_delivery_unit u JOIN order_initial_delivery d ON d.event_id=u.event_id WHERE u.item_id=p.item_id AND u.ordinal=p.ordinal AND u.order_id=p.order_id AND u.seller_order_id=p.seller_order_id AND d.event_id=o.delivery_event_id AND d.shipment_id=o.delivery_bucket_id AND d.occurred_at=o.delivered_at)
  THEN RAISE EXCEPTION 'ACTUAL_ORIGINAL_PHYSICAL_SOURCE_REQUIRED' USING ERRCODE='23514';END IF;
 ELSE RAISE EXCEPTION 'ACTUAL_EXCHANGE_DISPATCH_SOURCE_REQUIRED' USING ERRCODE='23514';END IF;
END $$;
CREATE FUNCTION order_physical_graph_guard() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE v_id uuid;v_bucket uuid;d order_physical_delivery;p order_physical_source;b order_delivery_bucket;
BEGIN
 IF TG_TABLE_NAME='order_physical_source' THEN v_id=NEW.id;
 ELSIF TG_TABLE_NAME IN ('order_physical_current','order_exchange_physical_unit','order_physical_delivery') THEN v_id=NEW.physical_id;
 ELSIF TG_TABLE_NAME='order_delivery_bucket' THEN v_bucket=NEW.id;ELSE v_bucket=NEW.bucket_id;END IF;
 IF v_id IS NOT NULL THEN
  PERFORM verify_order_physical_source(v_id);SELECT * INTO p FROM order_physical_source WHERE id=v_id;SELECT * INTO d FROM order_physical_delivery WHERE physical_id=v_id;
  IF d.physical_id IS NOT NULL THEN
   SELECT * INTO b FROM order_delivery_bucket WHERE id=d.bucket_id;
   IF (b.order_id,b.seller_order_id) IS DISTINCT FROM (p.order_id,p.seller_order_id) OR p.round=0 AND (b.kind<>'BASIC' OR NOT EXISTS(SELECT 1 FROM order_physical_unit o WHERE o.id=p.original_physical_id AND o.delivery_event_id=d.event_id AND o.delivery_bucket_id=d.bucket_id AND o.delivered_at=d.delivered_at)) THEN RAISE EXCEPTION 'EXACT_PHYSICAL_DELIVERY_BUCKET_REQUIRED' USING ERRCODE='23514';END IF;v_bucket=d.bucket_id;
  END IF;
 END IF;
 IF v_bucket IS NOT NULL THEN
  SELECT * INTO b FROM order_delivery_bucket WHERE id=v_bucket;
  IF b.kind='BASIC' THEN
   IF NOT EXISTS(SELECT 1 FROM order_basic_delivery_bucket x JOIN shipment s ON s.id=x.shipment_id WHERE x.bucket_id=v_bucket AND s.order_id=b.order_id AND s.seller_order_id=b.seller_order_id AND x.shipment_id=b.id)
   THEN RAISE EXCEPTION 'CONCRETE_BASIC_DELIVERY_BUCKET_REQUIRED' USING ERRCODE='23514';END IF;
  ELSE RAISE EXCEPTION 'CONCRETE_EXCHANGE_DELIVERY_BUCKET_REQUIRED' USING ERRCODE='23514';END IF;
 END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER physical_source_exact AFTER INSERT ON order_physical_source DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION order_physical_graph_guard();
CREATE CONSTRAINT TRIGGER physical_current_exact AFTER INSERT OR UPDATE ON order_physical_current DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION order_physical_graph_guard();
CREATE CONSTRAINT TRIGGER accepted_physical_exact AFTER INSERT ON order_exchange_physical_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION order_physical_graph_guard();
CREATE CONSTRAINT TRIGGER physical_delivery_exact AFTER INSERT ON order_physical_delivery DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION order_physical_graph_guard();
CREATE CONSTRAINT TRIGGER delivery_bucket_exact AFTER INSERT ON order_delivery_bucket DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION order_physical_graph_guard();
CREATE CONSTRAINT TRIGGER basic_delivery_bucket_exact AFTER INSERT ON order_basic_delivery_bucket DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION order_physical_graph_guard();
CREATE FUNCTION current_physical_at_acceptance() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN
 IF NEW.source_schema_version=2 AND NOT EXISTS(SELECT 1 FROM order_physical_current c WHERE c.item_id=NEW.item_id AND c.ordinal=NEW.ordinal AND c.physical_id=NEW.physical_id) THEN RAISE EXCEPTION 'CURRENT_PHYSICAL_AT_ACCEPTANCE_REQUIRED' USING ERRCODE='23514';END IF;RETURN NEW;
END $$;
CREATE TRIGGER accepted_current_physical BEFORE INSERT ON order_exchange_physical_unit FOR EACH ROW EXECUTE FUNCTION current_physical_at_acceptance();
CREATE FUNCTION exchange_physical_graph_guard() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE v_id uuid;a order_exchange_application;c claim_exchange_request;n integer;v_lost boolean;v_ready boolean;v_inspection uuid;h claim_exchange_inspection;v_saleable integer;
BEGIN
 IF TG_TABLE_NAME IN ('order_exchange_application','claim_exchange_request') THEN v_id=NEW.id;
 ELSIF TG_TABLE_NAME='inventory_exchange_reservation_physical_source' THEN SELECT exchange_id INTO v_id FROM inventory_exchange_reservation WHERE id=NEW.reservation_id;
 ELSIF TG_TABLE_NAME IN ('inventory_physical_inbound','inventory_physical_inbound_unit') THEN
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
  IF c.state NOT IN ('DISPATCHED','COMPLETED','REFUND_PENDING','REFUNDED','EXPIRED') AND c.state IS DISTINCT FROM (CASE WHEN v_lost THEN 'REVIEW_REQUIRED' WHEN v_ready AND c.approved_at IS NOT NULL THEN 'READY' ELSE 'COLLECTING' END) THEN RAISE EXCEPTION 'CURRENT_EXCHANGE_PHYSICAL_PROGRESS_REQUIRED' USING ERRCODE='23514';END IF;
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
CREATE CONSTRAINT TRIGGER accepted_exchange_physical_graph AFTER INSERT ON order_exchange_application DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_physical_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_current_physical_graph AFTER INSERT OR UPDATE ON claim_exchange_request DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_physical_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_fixed_physical_graph AFTER INSERT ON order_exchange_physical_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_physical_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_collection_graph AFTER INSERT ON claim_exchange_collection DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_physical_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_inspection_graph AFTER INSERT ON claim_exchange_inspection DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_physical_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_inspection_unit_graph AFTER INSERT ON claim_exchange_inspection_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_physical_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_inbound_graph AFTER INSERT ON inventory_physical_inbound DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_physical_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_inbound_unit_graph AFTER INSERT ON inventory_physical_inbound_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_physical_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_bound_stock_graph AFTER INSERT ON inventory_exchange_reservation_physical_source DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_physical_graph_guard();
CREATE FUNCTION physical_inbound_movement_matches_balance() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM inventory_physical_inbound WHERE inspection_id=NEW.cause_id) AND NOT EXISTS(SELECT 1 FROM inventory_balance b WHERE b.sku_id=NEW.sku_id AND b.on_hand=NEW.after_on_hand AND b.reserved=NEW.reserved AND b.version=NEW.version) THEN RAISE EXCEPTION 'ACTUAL_PHYSICAL_INBOUND_MOVEMENT_REQUIRED' USING ERRCODE='23514';END IF;RETURN NEW;
END $$;
CREATE TRIGGER physical_inbound_actual_balance BEFORE INSERT ON inventory_restitution_movement FOR EACH ROW EXECUTE FUNCTION physical_inbound_movement_matches_balance();
CREATE FUNCTION immutable_physical_stock_header() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN IF EXISTS(SELECT 1 FROM inventory_physical_inbound WHERE inspection_id=OLD.cause_id) THEN RAISE EXCEPTION 'IMMUTABLE_PHYSICAL_INBOUND_EVIDENCE' USING ERRCODE='23514';END IF;IF TG_OP='DELETE' THEN RETURN OLD;END IF;RETURN NEW;END $$;
CREATE TRIGGER physical_stock_header_immutable BEFORE UPDATE OR DELETE ON inventory_restitution FOR EACH ROW EXECUTE FUNCTION immutable_physical_stock_header();
CREATE TRIGGER physical_stock_movement_immutable BEFORE UPDATE OR DELETE ON inventory_restitution_movement FOR EACH ROW EXECUTE FUNCTION immutable_physical_stock_header();
CREATE FUNCTION immutable_exchange_approval_prices() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN IF OLD.approved_at IS NOT NULL AND (OLD.approved_at,OLD.approved_offers) IS DISTINCT FROM (NEW.approved_at,NEW.approved_offers) THEN RAISE EXCEPTION 'IMMUTABLE_EXCHANGE_APPROVAL_PRICE_EVIDENCE' USING ERRCODE='23514';END IF;RETURN NEW;END $$;
CREATE TRIGGER exchange_approval_prices_immutable BEFORE UPDATE ON claim_exchange_request FOR EACH ROW EXECUTE FUNCTION immutable_exchange_approval_prices();
