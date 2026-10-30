-- Refuse ambiguous legacy allocations rather than guessing an owner or rewriting history.
DO $$
BEGIN
 IF EXISTS(SELECT 1 FROM shipment WHERE purpose_key<>'BASIC') THEN
  RAISE EXCEPTION 'P09 legacy migration: non-BASIC shipment requires review';
 END IF;
 IF EXISTS(SELECT 1 FROM shipment s JOIN order_item i ON i.seller_order_id=s.seller_order_id
   LEFT JOIN shipment_item si ON si.shipment_id=s.id AND si.order_item_id=i.id
   WHERE si.quantity IS DISTINCT FROM i.quantity
      OR (SELECT count(*) FROM order_unit u WHERE u.item_id=i.id)<>i.quantity) THEN
  RAISE EXCEPTION 'P09 legacy migration: original shipment quantity/unit mismatch';
 END IF;
 IF EXISTS(SELECT 1 FROM shipment s WHERE s.state='DELIVERED'
   AND (SELECT count(*) FROM shipment_event e WHERE e.shipment_id=s.id AND e.applied AND e.next_state='DELIVERED')<>1) THEN
  RAISE EXCEPTION 'P09 legacy migration: DELIVERED requires exactly one applied original event';
 END IF;
END $$;
ALTER TABLE shipment ADD COLUMN tracking varchar(64),
 ADD COLUMN exception_status varchar(16) NOT NULL DEFAULT 'NONE' CHECK(exception_status IN ('NONE','DELAYED','FAILED')),
 ADD COLUMN progress_epoch bigint NOT NULL DEFAULT 0 CHECK(progress_epoch>=0),
 ADD CONSTRAINT shipment_tracking_format CHECK(tracking IS NULL OR tracking ~ '^[A-Za-z0-9][A-Za-z0-9-]{0,63}$');
CREATE TABLE shipment_unit (
 shipment_id uuid NOT NULL,
 order_item_id uuid NOT NULL,
 ordinal integer NOT NULL,
 PRIMARY KEY(order_item_id,ordinal),
 FOREIGN KEY(order_item_id,ordinal) REFERENCES order_unit(item_id,ordinal),
 FOREIGN KEY(shipment_id,order_item_id) REFERENCES shipment_item(shipment_id,order_item_id) DEFERRABLE INITIALLY DEFERRED
);
INSERT INTO shipment_unit(shipment_id,order_item_id,ordinal)
 SELECT s.id,i.id,u.ordinal FROM shipment s JOIN order_item i ON i.seller_order_id=s.seller_order_id JOIN order_unit u ON u.item_id=i.id;
CREATE TABLE shipment_preparation (
 seller_order_id uuid PRIMARY KEY REFERENCES seller_order(id),
 basic_id uuid NOT NULL UNIQUE,
 schema_version integer NOT NULL CHECK(schema_version=1),
 original_digest varchar(64) NOT NULL,
 FOREIGN KEY(basic_id,seller_order_id) REFERENCES shipment(id,seller_order_id)
);
INSERT INTO shipment_preparation(seller_order_id,basic_id,schema_version,original_digest)
 SELECT s.seller_order_id,s.id,1,encode(sha256(convert_to(s.order_id::text||'|'||s.seller_order_id::text||'|'||
  string_agg(u.order_item_id::text||':'||u.ordinal::text,',' ORDER BY u.order_item_id::text,u.ordinal),'UTF8')),'hex')
 FROM shipment s JOIN shipment_unit u ON u.shipment_id=s.id GROUP BY s.id,s.order_id,s.seller_order_id;
CREATE FUNCTION shipment_quantity_matches_units() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE sid uuid; iid uuid; expected integer; actual integer;
BEGIN
 IF TG_OP='DELETE' THEN sid=OLD.shipment_id; iid=OLD.order_item_id;
 ELSE sid=NEW.shipment_id; iid=NEW.order_item_id; END IF;
 SELECT quantity INTO expected FROM shipment_item WHERE shipment_id=sid AND order_item_id=iid;
 SELECT count(*) INTO actual FROM shipment_unit WHERE shipment_id=sid AND order_item_id=iid;
 IF coalesce(expected,0)<>actual THEN RAISE EXCEPTION 'shipment quantity/unit mismatch: shipment %, item %',sid,iid USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND (OLD.shipment_id,OLD.order_item_id) IS DISTINCT FROM (NEW.shipment_id,NEW.order_item_id) THEN
  SELECT quantity INTO expected FROM shipment_item WHERE shipment_id=OLD.shipment_id AND order_item_id=OLD.order_item_id;
  SELECT count(*) INTO actual FROM shipment_unit WHERE shipment_id=OLD.shipment_id AND order_item_id=OLD.order_item_id;
  IF coalesce(expected,0)<>actual THEN RAISE EXCEPTION 'donor shipment quantity/unit mismatch' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER shipment_item_unit_count AFTER INSERT OR UPDATE OR DELETE ON shipment_item DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION shipment_quantity_matches_units();
CREATE CONSTRAINT TRIGGER shipment_unit_item_count AFTER INSERT OR UPDATE OR DELETE ON shipment_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION shipment_quantity_matches_units();
CREATE TABLE shipment_command (
 shipment_id uuid NOT NULL REFERENCES shipment(id),
 command_kind varchar(16) NOT NULL CHECK(command_kind IN ('SPLIT','TRACKING')),
 request_key varchar(100) NOT NULL,
 content text NOT NULL,
 result jsonb NOT NULL,
 PRIMARY KEY(shipment_id,command_kind,request_key)
);
CREATE TABLE shipment_tracking_history (
 event_id uuid PRIMARY KEY REFERENCES shipment_event(id),
 shipment_id uuid NOT NULL REFERENCES shipment(id),
 previous_tracking varchar(64),
 next_tracking varchar(64) NOT NULL,
 reason varchar(800) NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 200 AND length(btrim(reason))>0),
 source varchar(30) NOT NULL,
 actor varchar(100) NOT NULL,
 recorded_at timestamptz NOT NULL
);
-- Order owns the trusted first-delivery facts. No FK or read back into fulfillment.
ALTER TABLE order_item ADD CONSTRAINT order_item_delivery_identity UNIQUE(id,seller_order_id,order_id);
CREATE TABLE order_initial_delivery (
 event_id uuid PRIMARY KEY,
 schema_version integer NOT NULL CHECK(schema_version=1),
 order_id uuid NOT NULL,
 seller_order_id uuid NOT NULL,
 shipment_id uuid NOT NULL,
 occurred_at timestamptz NOT NULL,
 fingerprint varchar(64) NOT NULL,
 FOREIGN KEY(seller_order_id,order_id) REFERENCES seller_order(id,order_id),
 UNIQUE(event_id,seller_order_id,order_id)
);
CREATE TABLE order_initial_delivery_unit (
 item_id uuid NOT NULL,
 ordinal integer NOT NULL,
 event_id uuid NOT NULL,
 order_id uuid NOT NULL,
 seller_order_id uuid NOT NULL,
 PRIMARY KEY(item_id,ordinal),
 FOREIGN KEY(item_id,ordinal) REFERENCES order_unit(item_id,ordinal),
 FOREIGN KEY(item_id,seller_order_id,order_id) REFERENCES order_item(id,seller_order_id,order_id),
 FOREIGN KEY(event_id,seller_order_id,order_id) REFERENCES order_initial_delivery(event_id,seller_order_id,order_id)
);
