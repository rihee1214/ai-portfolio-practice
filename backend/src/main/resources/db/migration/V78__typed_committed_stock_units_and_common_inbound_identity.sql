-- A typed registry supplements original reservation keys/FKs; it never replaces or nulls them.
CREATE TABLE inventory_committed_unit (
 id uuid PRIMARY KEY,kind varchar(10) NOT NULL CHECK(kind IN ('PURCHASE','EXCHANGE')),checkout_id uuid NOT NULL,
 item_id uuid NOT NULL,ordinal integer NOT NULL CHECK(ordinal>=0),sku_id uuid NOT NULL REFERENCES catalog_sku(id),
 UNIQUE(id,kind),UNIQUE(id,checkout_id,item_id,ordinal,sku_id)
);
CREATE TABLE inventory_purchase_source (
 source_id uuid PRIMARY KEY,reservation_id uuid NOT NULL,reservation_unit integer NOT NULL CHECK(reservation_unit>=0),
 checkout_id uuid NOT NULL,item_id uuid NOT NULL,sku_id uuid NOT NULL,kind varchar(10) NOT NULL DEFAULT 'PURCHASE' CHECK(kind='PURCHASE'),
 UNIQUE(reservation_id,reservation_unit),FOREIGN KEY(source_id,kind) REFERENCES inventory_committed_unit(id,kind),
 FOREIGN KEY(reservation_id,checkout_id,item_id,sku_id) REFERENCES inventory_reservation(id,checkout_id,order_item_id,sku_id)
);
CREATE TABLE inventory_exchange_source (
 source_id uuid PRIMARY KEY,reservation_id uuid NOT NULL REFERENCES inventory_exchange_reservation(id),reservation_unit integer NOT NULL DEFAULT 0 CHECK(reservation_unit=0),
 kind varchar(10) NOT NULL DEFAULT 'EXCHANGE' CHECK(kind='EXCHANGE'),UNIQUE(reservation_id,reservation_unit),
 FOREIGN KEY(source_id,kind) REFERENCES inventory_committed_unit(id,kind)
);
ALTER TABLE inventory_restitution DROP CONSTRAINT inventory_restitution_kind_check;
ALTER TABLE inventory_restitution ADD CONSTRAINT inventory_restitution_kind_check CHECK(kind IN ('CANCEL','RETURN','EXCHANGE'));
CREATE TABLE inventory_source_restitution (
 source_id uuid PRIMARY KEY REFERENCES inventory_committed_unit(id),cause_id uuid NOT NULL REFERENCES inventory_restitution(cause_id),
 UNIQUE(cause_id,source_id)
);
CREATE TABLE inventory_physical_inbound (
 inspection_id uuid PRIMARY KEY,cause_id uuid NOT NULL UNIQUE CHECK(cause_id=inspection_id),claim_id uuid NOT NULL,
 checkout_id uuid NOT NULL,kind varchar(10) NOT NULL CHECK(kind IN ('RETURN','EXCHANGE')),
 FOREIGN KEY(cause_id,checkout_id,kind) REFERENCES inventory_restitution(cause_id,checkout_id,kind)
);
CREATE TABLE inventory_physical_inbound_unit (
 inspection_id uuid NOT NULL REFERENCES inventory_physical_inbound(inspection_id),physical_id uuid NOT NULL,source_id uuid NOT NULL,
 PRIMARY KEY(inspection_id,physical_id),UNIQUE(inspection_id,source_id),
 FOREIGN KEY(inspection_id,source_id) REFERENCES inventory_source_restitution(cause_id,source_id)
);
CREATE FUNCTION inventory_purchase_source_id(p_id uuid,p_unit integer) RETURNS uuid LANGUAGE sql IMMUTABLE AS $$ SELECT md5('inventory/PURCHASE/'||p_id::text||'/'||p_unit::text)::uuid $$;
CREATE FUNCTION inventory_ensure_purchase_source(p_reservation uuid,p_unit integer) RETURNS uuid LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE r inventory_reservation;v_id uuid;
BEGIN
 SELECT * INTO r FROM inventory_reservation WHERE id=p_reservation;
 IF NOT FOUND OR r.status<>'COMMITTED' OR p_unit<0 OR p_unit>=r.quantity THEN RAISE EXCEPTION 'ACTUAL_COMMITTED_PURCHASE_SOURCE_REQUIRED' USING ERRCODE='23514';END IF;
 v_id=inventory_purchase_source_id(p_reservation,p_unit);
 INSERT INTO inventory_committed_unit(id,kind,checkout_id,item_id,ordinal,sku_id) VALUES(v_id,'PURCHASE',r.checkout_id,r.order_item_id,p_unit,r.sku_id) ON CONFLICT DO NOTHING;
 INSERT INTO inventory_purchase_source(source_id,reservation_id,reservation_unit,checkout_id,item_id,sku_id) VALUES(v_id,r.id,p_unit,r.checkout_id,r.order_item_id,r.sku_id) ON CONFLICT DO NOTHING;
 RETURN v_id;
END $$;
CREATE FUNCTION inventory_committed_source_guard() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE v_id uuid;s inventory_committed_unit;
BEGIN
 IF TG_TABLE_NAME='inventory_committed_unit' THEN v_id=NEW.id;ELSE v_id=NEW.source_id;END IF;
 SELECT * INTO s FROM inventory_committed_unit WHERE id=v_id;
 IF s.kind='PURCHASE' THEN
  IF EXISTS(SELECT 1 FROM inventory_exchange_source WHERE source_id=v_id) OR NOT EXISTS(SELECT 1 FROM inventory_purchase_source p JOIN inventory_reservation r ON r.id=p.reservation_id WHERE p.source_id=v_id AND r.status='COMMITTED' AND p.reservation_unit>=0 AND p.reservation_unit<r.quantity AND p.reservation_unit=s.ordinal AND p.checkout_id=s.checkout_id AND p.item_id=s.item_id AND p.sku_id=s.sku_id AND r.checkout_id=s.checkout_id AND r.order_item_id=s.item_id AND r.sku_id=s.sku_id) THEN RAISE EXCEPTION 'EXACT_TYPED_PURCHASE_SOURCE_REQUIRED' USING ERRCODE='23514';END IF;
 ELSIF s.kind='EXCHANGE' THEN
  IF EXISTS(SELECT 1 FROM inventory_purchase_source WHERE source_id=v_id) OR NOT EXISTS(SELECT 1 FROM inventory_exchange_source p JOIN inventory_exchange_reservation r ON r.id=p.reservation_id WHERE p.source_id=v_id AND p.reservation_unit=0 AND r.quantity=1 AND r.status='COMMITTED' AND r.checkout_id=s.checkout_id AND r.item_id=s.item_id AND r.ordinal=s.ordinal AND r.target_sku_id=s.sku_id) THEN RAISE EXCEPTION 'EXACT_TYPED_EXCHANGE_SOURCE_REQUIRED' USING ERRCODE='23514';END IF;
 ELSE RAISE EXCEPTION 'TYPED_COMMITTED_SOURCE_REQUIRED' USING ERRCODE='23514';END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER committed_source_complete AFTER INSERT ON inventory_committed_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION inventory_committed_source_guard();
CREATE CONSTRAINT TRIGGER purchase_source_complete AFTER INSERT ON inventory_purchase_source DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION inventory_committed_source_guard();
CREATE CONSTRAINT TRIGGER exchange_source_complete AFTER INSERT ON inventory_exchange_source DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION inventory_committed_source_guard();
CREATE TRIGGER committed_unit_immutable BEFORE UPDATE OR DELETE ON inventory_committed_unit FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER purchase_source_immutable BEFORE UPDATE OR DELETE ON inventory_purchase_source FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER exchange_source_immutable BEFORE UPDATE OR DELETE ON inventory_exchange_source FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
-- Backfill creates evidence only, no balance or movement effects.
SELECT inventory_ensure_purchase_source(u.reservation_id,u.ordinal) FROM inventory_restitution_unit u;
INSERT INTO inventory_source_restitution(source_id,cause_id) SELECT inventory_purchase_source_id(u.reservation_id,u.ordinal),u.cause_id FROM inventory_restitution_unit u;
CREATE FUNCTION inventory_legacy_restitution_source() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE v_id uuid;v_cause uuid;
BEGIN
 v_id=inventory_ensure_purchase_source(NEW.reservation_id,NEW.ordinal);
 INSERT INTO inventory_source_restitution(source_id,cause_id) VALUES(v_id,NEW.cause_id) ON CONFLICT DO NOTHING;
 SELECT cause_id INTO v_cause FROM inventory_source_restitution WHERE source_id=v_id;
 IF v_cause<>NEW.cause_id THEN RAISE EXCEPTION 'PHYSICAL_SOURCE_ALREADY_RESTORED' USING ERRCODE='23514';END IF;RETURN NEW;
END $$;
CREATE TRIGGER legacy_restitution_uses_common_source AFTER INSERT ON inventory_restitution_unit FOR EACH ROW EXECUTE FUNCTION inventory_legacy_restitution_source();
CREATE FUNCTION inventory_source_restitution_guard() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE v_source uuid;v_cause uuid;s inventory_committed_unit;
BEGIN
 IF TG_TABLE_NAME='inventory_source_restitution' THEN v_source=NEW.source_id;v_cause=NEW.cause_id;
 ELSE v_source=NEW.source_id;v_cause=NEW.inspection_id;END IF;
 SELECT * INTO s FROM inventory_committed_unit WHERE id=v_source;
 IF NOT EXISTS(SELECT 1 FROM inventory_source_restitution WHERE source_id=v_source AND cause_id=v_cause) OR (
  NOT EXISTS(SELECT 1 FROM inventory_purchase_source p JOIN inventory_restitution_unit u ON u.reservation_id=p.reservation_id AND u.ordinal=p.reservation_unit WHERE p.source_id=v_source AND u.cause_id=v_cause)
  AND NOT EXISTS(SELECT 1 FROM inventory_physical_inbound_unit u JOIN inventory_physical_inbound h ON h.inspection_id=u.inspection_id WHERE u.source_id=v_source AND u.inspection_id=v_cause AND h.checkout_id=s.checkout_id)
 ) THEN RAISE EXCEPTION 'EXACT_COMMON_PHYSICAL_INBOUND_REQUIRED' USING ERRCODE='23514';END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER source_restitution_complete AFTER INSERT ON inventory_source_restitution DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION inventory_source_restitution_guard();
CREATE CONSTRAINT TRIGGER physical_inbound_source_complete AFTER INSERT ON inventory_physical_inbound_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION inventory_source_restitution_guard();
CREATE TRIGGER source_restitution_immutable BEFORE UPDATE OR DELETE ON inventory_source_restitution FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER physical_inbound_immutable BEFORE UPDATE OR DELETE ON inventory_physical_inbound FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER physical_inbound_unit_immutable BEFORE UPDATE OR DELETE ON inventory_physical_inbound_unit FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
