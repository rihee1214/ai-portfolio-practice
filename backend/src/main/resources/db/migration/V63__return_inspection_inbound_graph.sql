-- Inventory validates its newly produced stock receipt without reading claim storage.
-- The deferred cross-module graph separately requires the real SALEABLE inspection.
CREATE FUNCTION inventory_return_stock_receipt_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_stock marketplace.inventory_restitution%ROWTYPE;v_count bigint;
BEGIN
 SELECT * INTO v_stock FROM marketplace.inventory_restitution WHERE cause_id=NEW.cause_id;
 IF NOT FOUND OR v_stock.kind<>'RETURN' OR v_stock.checkout_id<>NEW.checkout_id OR NEW.inspection_id<>NEW.cause_id THEN
  RAISE EXCEPTION 'RETURN_STOCK_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;
 SELECT count(*) INTO v_count FROM marketplace.inventory_restitution_unit WHERE cause_id=NEW.cause_id;
 IF v_count=0 OR (SELECT count(*) FROM marketplace.inventory_restitution_movement WHERE cause_id=NEW.cause_id)
   <>(SELECT count(DISTINCT sku_id) FROM marketplace.inventory_restitution_unit WHERE cause_id=NEW.cause_id)
  OR EXISTS(SELECT 1 FROM marketplace.inventory_restitution_unit u JOIN marketplace.inventory_reservation r ON r.id=u.reservation_id
   WHERE u.cause_id=NEW.cause_id AND(r.status<>'COMMITTED' OR r.checkout_id<>NEW.checkout_id OR r.order_item_id<>u.item_id OR r.sku_id<>u.sku_id OR u.ordinal<0 OR u.ordinal>=r.quantity))
  OR EXISTS(SELECT 1 FROM marketplace.inventory_restitution_movement m
   JOIN marketplace.inventory_balance b ON b.sku_id=m.sku_id
   WHERE m.cause_id=NEW.cause_id AND(
    m.quantity<>(SELECT count(*) FROM marketplace.inventory_restitution_unit u WHERE u.cause_id=NEW.cause_id AND u.sku_id=m.sku_id)
    OR b.on_hand<>m.after_on_hand OR b.reserved<>m.reserved OR b.version<>m.version
   )) THEN RAISE EXCEPTION 'RETURN_STOCK_EFFECT_NOT_RECORDED' USING ERRCODE='23514';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER inventory_return_stock_receipt BEFORE INSERT ON inventory_return_restitution FOR EACH ROW EXECUTE FUNCTION inventory_return_stock_receipt_guard();

CREATE FUNCTION inventory_return_evidence_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_cause uuid;v_kind varchar(10);
BEGIN
 v_cause:=OLD.cause_id;
 IF TG_TABLE_NAME='inventory_restitution' THEN v_kind:=OLD.kind;
 ELSE SELECT kind INTO v_kind FROM marketplace.inventory_restitution WHERE cause_id=v_cause;END IF;
 IF v_kind='RETURN' THEN RAISE EXCEPTION 'IMMUTABLE_RETURN_STOCK_EVIDENCE' USING ERRCODE='23514';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;END IF;RETURN NEW;
END $$;
CREATE TRIGGER return_stock_header_immutable BEFORE UPDATE OR DELETE ON inventory_restitution FOR EACH ROW EXECUTE FUNCTION inventory_return_evidence_immutable();
CREATE TRIGGER return_stock_unit_immutable BEFORE UPDATE OR DELETE ON inventory_restitution_unit FOR EACH ROW EXECUTE FUNCTION inventory_return_evidence_immutable();
CREATE TRIGGER return_stock_movement_immutable BEFORE UPDATE OR DELETE ON inventory_restitution_movement FOR EACH ROW EXECUTE FUNCTION inventory_return_evidence_immutable();

-- This is an explicit database integrity graph, not an order/inventory Java storage dependency.
CREATE FUNCTION verify_return_inspection_inbound(p_inspection uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE v_inspection marketplace.claim_return_inspection%ROWTYPE;v_saleable bigint;v_units bigint;
BEGIN
 SELECT * INTO v_inspection FROM marketplace.claim_return_inspection WHERE id=p_inspection;
 IF NOT FOUND THEN RAISE EXCEPTION 'ACTUAL_RETURN_INSPECTION_REQUIRED' USING ERRCODE='23514';END IF;
 IF NOT EXISTS(SELECT 1 FROM marketplace.order_return_application a JOIN marketplace.claim_return_request r ON r.id=a.id
  WHERE a.id=v_inspection.return_id AND a.order_id=v_inspection.order_id AND a.seller_order_id=v_inspection.seller_order_id
   AND a.source_snapshot->>'checkoutId'=v_inspection.checkout_id::text AND r.version>=v_inspection.version)
  OR NOT EXISTS(SELECT 1 FROM marketplace.claim_return_command c JOIN marketplace.claim_return_history h ON h.return_id=c.return_id AND h.version=c.version
   WHERE c.return_id=v_inspection.return_id AND c.version=v_inspection.version AND c.action IN ('COLLECTING','REVIEW_REQUIRED','AWAITING_CONSENT')
    AND h.action=c.action AND h.actor=v_inspection.actor AND h.reason=v_inspection.reason AND h.occurred_at=v_inspection.occurred_at) THEN
  RAISE EXCEPTION 'RETURN_INSPECTION_COMMAND_IDENTITY_REQUIRED' USING ERRCODE='23514';END IF;
 SELECT count(*),count(*) FILTER(WHERE condition='SALEABLE') INTO v_units,v_saleable FROM marketplace.claim_return_inspection_unit WHERE inspection_id=p_inspection;
 IF v_units=0 OR EXISTS(SELECT 1 FROM marketplace.claim_return_inspection_unit i
  LEFT JOIN marketplace.claim_return_collection c ON c.id=i.collection_id AND c.return_id=i.return_id AND c.item_id=i.item_id AND c.ordinal=i.ordinal
  WHERE i.inspection_id=p_inspection AND(i.return_id<>v_inspection.return_id OR c.status IS DISTINCT FROM 'RECEIVED' OR c.version>=v_inspection.version)) THEN
  RAISE EXCEPTION 'ACTUAL_RECEIVED_ORIGINAL_INSPECTION_UNITS_REQUIRED' USING ERRCODE='23514';END IF;
 IF v_saleable=0 THEN
  IF EXISTS(SELECT 1 FROM marketplace.inventory_return_restitution x WHERE x.inspection_id=p_inspection)
   OR EXISTS(SELECT 1 FROM marketplace.inventory_restitution x WHERE x.cause_id=p_inspection) THEN
   RAISE EXCEPTION 'DEFECTIVE_INSPECTION_CANNOT_RESTOCK' USING ERRCODE='23514';END IF;
  RETURN;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM marketplace.inventory_return_restitution x JOIN marketplace.inventory_restitution s ON s.cause_id=x.cause_id
  WHERE x.inspection_id=p_inspection AND x.cause_id=p_inspection AND x.return_id=v_inspection.return_id AND x.checkout_id=v_inspection.checkout_id
   AND x.kind='RETURN' AND s.kind='RETURN' AND s.checkout_id=v_inspection.checkout_id)
  OR (SELECT count(*) FROM marketplace.inventory_restitution_unit WHERE cause_id=p_inspection)<>v_saleable THEN
  RAISE EXCEPTION 'ACTUAL_SALEABLE_INBOUND_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;
 IF EXISTS(SELECT 1 FROM marketplace.claim_return_inspection_unit i
  LEFT JOIN marketplace.inventory_restitution_unit u ON u.cause_id=p_inspection AND u.item_id=i.item_id AND u.ordinal=i.ordinal
  LEFT JOIN marketplace.inventory_reservation r ON r.id=u.reservation_id
  LEFT JOIN marketplace.order_item original ON original.id=i.item_id AND original.order_id=v_inspection.order_id AND original.seller_order_id=v_inspection.seller_order_id
  WHERE i.inspection_id=p_inspection AND i.condition='SALEABLE' AND(
   i.restitution_id IS DISTINCT FROM p_inspection OR u.cause_id IS NULL OR original.id IS NULL
   OR u.checkout_id IS DISTINCT FROM v_inspection.checkout_id OR u.sku_id IS DISTINCT FROM original.sku_id
   OR r.status IS DISTINCT FROM 'COMMITTED' OR r.checkout_id IS DISTINCT FROM v_inspection.checkout_id OR r.order_item_id IS DISTINCT FROM i.item_id
   OR r.sku_id IS DISTINCT FROM original.sku_id OR i.ordinal<0 OR i.ordinal>=r.quantity
  )) OR EXISTS(SELECT 1 FROM marketplace.inventory_restitution_unit u
   LEFT JOIN marketplace.claim_return_inspection_unit i ON i.inspection_id=p_inspection AND i.item_id=u.item_id AND i.ordinal=u.ordinal AND i.condition='SALEABLE'
   WHERE u.cause_id=p_inspection AND i.inspection_id IS NULL)
  OR (SELECT count(*) FROM marketplace.inventory_restitution_movement WHERE cause_id=p_inspection)
   <>(SELECT count(DISTINCT sku_id) FROM marketplace.inventory_restitution_unit WHERE cause_id=p_inspection)
  OR EXISTS(SELECT 1 FROM marketplace.inventory_restitution_movement m WHERE m.cause_id=p_inspection AND m.quantity<>(
   SELECT count(*) FROM marketplace.inventory_restitution_unit u WHERE u.cause_id=p_inspection AND u.sku_id=m.sku_id)) THEN
  RAISE EXCEPTION 'RETURN_EXACT_SALEABLE_STOCK_GRAPH_REQUIRED' USING ERRCODE='23514';END IF;
END $$;

CREATE FUNCTION return_inspection_inbound_graph_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_inspection uuid;v_kind varchar(10);
BEGIN
 IF TG_TABLE_NAME='claim_return_inspection' THEN v_inspection:=NEW.id;
 ELSIF TG_TABLE_NAME IN ('claim_return_inspection_unit','inventory_return_restitution') THEN v_inspection:=NEW.inspection_id;
 ELSE
  IF TG_TABLE_NAME='inventory_restitution' THEN v_kind:=NEW.kind;
  ELSE SELECT kind INTO v_kind FROM marketplace.inventory_restitution WHERE cause_id=NEW.cause_id;END IF;
  IF v_kind IS DISTINCT FROM 'RETURN' THEN RETURN NULL;END IF;
  v_inspection:=NEW.cause_id;
 END IF;
 PERFORM marketplace.verify_return_inspection_inbound(v_inspection);RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER return_inspection_inbound_header AFTER INSERT ON claim_return_inspection DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_inspection_inbound_graph_guard();
CREATE CONSTRAINT TRIGGER return_inspection_inbound_unit AFTER INSERT ON claim_return_inspection_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_inspection_inbound_graph_guard();
CREATE CONSTRAINT TRIGGER return_inbound_inspection_marker AFTER INSERT ON inventory_return_restitution DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_inspection_inbound_graph_guard();
CREATE CONSTRAINT TRIGGER return_inbound_inspection_receipt AFTER INSERT ON inventory_restitution DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_inspection_inbound_graph_guard();
CREATE CONSTRAINT TRIGGER return_inbound_inspection_unit AFTER INSERT ON inventory_restitution_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_inspection_inbound_graph_guard();
CREATE CONSTRAINT TRIGGER return_inbound_inspection_movement AFTER INSERT ON inventory_restitution_movement DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_inspection_inbound_graph_guard();

DO $$ DECLARE row record;BEGIN
 FOR row IN SELECT id FROM marketplace.claim_return_inspection LOOP PERFORM marketplace.verify_return_inspection_inbound(row.id);END LOOP;
 FOR row IN SELECT cause_id FROM marketplace.inventory_restitution WHERE kind='RETURN' LOOP PERFORM marketplace.verify_return_inspection_inbound(row.cause_id);END LOOP;
END $$;
