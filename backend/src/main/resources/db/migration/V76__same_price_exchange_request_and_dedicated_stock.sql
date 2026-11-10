-- Dedicated physical exchange requests. Original COMMITTED purchase reservations and initial delivery never change.
CREATE TABLE order_physical_unit (
 id uuid PRIMARY KEY,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,item_id uuid NOT NULL,ordinal integer NOT NULL CHECK(ordinal>=0),
 round integer NOT NULL CHECK(round=0),sku_id uuid NOT NULL REFERENCES catalog_sku(id),reservation_id uuid NOT NULL REFERENCES inventory_reservation(id),
 checkout_id uuid NOT NULL,delivery_event_id uuid NOT NULL REFERENCES order_initial_delivery(event_id),delivery_bucket_id uuid NOT NULL,
 delivered_at timestamptz NOT NULL,source_snapshot jsonb NOT NULL CHECK(jsonb_typeof(source_snapshot)='object'),
 UNIQUE(item_id,ordinal,round),UNIQUE(id,item_id,ordinal),FOREIGN KEY(item_id,ordinal) REFERENCES order_unit(item_id,ordinal),
 FOREIGN KEY(seller_order_id,order_id) REFERENCES seller_order(id,order_id)
);
CREATE TABLE order_exchange_application (
 id uuid PRIMARY KEY,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,store_id uuid NOT NULL,hold_id uuid NOT NULL UNIQUE,cause_id uuid NOT NULL UNIQUE,
 hold_kind varchar(10) NOT NULL DEFAULT 'EXCHANGE' CHECK(hold_kind='EXCHANGE'),requested_at timestamptz NOT NULL,
 source_snapshot jsonb NOT NULL CHECK(jsonb_typeof(source_snapshot)='object'),
 UNIQUE(id,hold_id,seller_order_id,order_id),FOREIGN KEY(seller_order_id,order_id) REFERENCES seller_order(id,order_id),
 FOREIGN KEY(hold_id,cause_id,order_id,hold_kind) REFERENCES order_unit_hold(id,cause_id,order_id,kind)
);
CREATE TABLE order_exchange_unit (
 exchange_id uuid NOT NULL REFERENCES order_exchange_application(id),hold_id uuid NOT NULL,physical_id uuid NOT NULL,item_id uuid NOT NULL,ordinal integer NOT NULL,
 PRIMARY KEY(exchange_id,item_id,ordinal),UNIQUE(exchange_id,physical_id),FOREIGN KEY(physical_id,item_id,ordinal) REFERENCES order_physical_unit(id,item_id,ordinal),
 FOREIGN KEY(hold_id,item_id,ordinal) REFERENCES order_hold_unit(hold_id,item_id,ordinal)
);
CREATE TABLE claim_exchange_request (
 id uuid PRIMARY KEY,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,hold_id uuid NOT NULL,cause_id uuid NOT NULL,
 request_key varchar(100) NOT NULL,request_fingerprint varchar(64) NOT NULL,reason varchar(500) NOT NULL CHECK(length(btrim(reason))>0),
 state varchar(32) NOT NULL CHECK(state IN ('REQUESTED','APPROVED')),version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 requested_at timestamptz NOT NULL,receipt jsonb NOT NULL,source_snapshot jsonb NOT NULL,approved_at timestamptz,expires_at timestamptz,approved_offers jsonb NOT NULL DEFAULT '[]',
 FOREIGN KEY(id,hold_id,seller_order_id,order_id) REFERENCES order_exchange_application(id,hold_id,seller_order_id,order_id),
 UNIQUE(order_id,request_key),UNIQUE(id,hold_id),CHECK((state='REQUESTED' AND version=0 AND approved_at IS NULL AND expires_at IS NULL AND approved_offers='[]') OR (state='APPROVED' AND version=1 AND approved_at IS NOT NULL AND expires_at=approved_at+interval '7 days' AND jsonb_array_length(approved_offers)>0))
);
ALTER TABLE order_exchange_application ADD CONSTRAINT accepted_exchange_requires_claim FOREIGN KEY(id) REFERENCES claim_exchange_request(id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE claim_exchange_unit (
 exchange_id uuid NOT NULL,hold_id uuid NOT NULL,item_id uuid NOT NULL,ordinal integer NOT NULL,physical_id uuid NOT NULL,target_sku_id uuid NOT NULL REFERENCES catalog_sku(id),
 claimed_responsibility varchar(10) NOT NULL CHECK(claimed_responsibility IN ('BUYER','SELLER')),
 PRIMARY KEY(exchange_id,item_id,ordinal),UNIQUE(exchange_id,physical_id),
 FOREIGN KEY(exchange_id,hold_id) REFERENCES claim_exchange_request(id,hold_id),
 FOREIGN KEY(exchange_id,item_id,ordinal) REFERENCES order_exchange_unit(exchange_id,item_id,ordinal),
 FOREIGN KEY(physical_id,item_id,ordinal) REFERENCES order_physical_unit(id,item_id,ordinal)
);
CREATE TABLE inventory_exchange_reservation (
 id uuid PRIMARY KEY,exchange_id uuid NOT NULL REFERENCES claim_exchange_request(id),order_id uuid NOT NULL,seller_order_id uuid NOT NULL,checkout_id uuid NOT NULL,
 item_id uuid NOT NULL,ordinal integer NOT NULL,physical_id uuid NOT NULL,source_reservation_id uuid NOT NULL REFERENCES inventory_reservation(id),source_sku_id uuid NOT NULL REFERENCES catalog_sku(id),target_sku_id uuid NOT NULL REFERENCES catalog_sku(id),
 quantity bigint NOT NULL CHECK(quantity=1),sequence integer NOT NULL CHECK(sequence=1),status varchar(16) NOT NULL CHECK(status='RESERVED'),
 approved_at timestamptz NOT NULL,expires_at timestamptz NOT NULL CHECK(expires_at=approved_at+interval '7 days'),
 UNIQUE(exchange_id,physical_id,sequence),FOREIGN KEY(exchange_id,physical_id) REFERENCES claim_exchange_unit(exchange_id,physical_id)
);
CREATE UNIQUE INDEX one_active_exchange_physical_reservation ON inventory_exchange_reservation(physical_id) WHERE status='RESERVED';
CREATE TABLE inventory_exchange_event (
 id uuid PRIMARY KEY,reservation_id uuid NOT NULL REFERENCES inventory_exchange_reservation(id),cause varchar(16) NOT NULL CHECK(cause='RESERVE'),
 before_reserved bigint NOT NULL CHECK(before_reserved>=0),after_reserved bigint NOT NULL,before_version bigint NOT NULL,version bigint NOT NULL,occurred_at timestamptz NOT NULL,
 UNIQUE(reservation_id,cause),CHECK(after_reserved=before_reserved+1 AND version=before_version+1)
);
CREATE TABLE claim_exchange_history (
 id uuid PRIMARY KEY,exchange_id uuid NOT NULL REFERENCES claim_exchange_request(id),action varchar(32) NOT NULL,version bigint NOT NULL,
 reason varchar(500) NOT NULL CHECK(length(btrim(reason))>0),actor varchar(100) NOT NULL,occurred_at timestamptz NOT NULL,UNIQUE(exchange_id,version)
);
CREATE TABLE claim_exchange_command (
 exchange_id uuid NOT NULL REFERENCES claim_exchange_request(id),command_key varchar(100) NOT NULL,fingerprint varchar(64) NOT NULL,
 action varchar(32) NOT NULL,version bigint NOT NULL,receipt jsonb NOT NULL,PRIMARY KEY(exchange_id,command_key),UNIQUE(exchange_id,version)
);
CREATE FUNCTION exchange_evidence_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'IMMUTABLE_EXCHANGE_EVIDENCE' USING ERRCODE='23514';END $$;
CREATE TRIGGER order_physical_unit_immutable BEFORE UPDATE OR DELETE ON order_physical_unit FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER order_exchange_application_immutable BEFORE UPDATE OR DELETE ON order_exchange_application FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER order_exchange_unit_immutable BEFORE UPDATE OR DELETE ON order_exchange_unit FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER claim_exchange_unit_immutable BEFORE UPDATE OR DELETE ON claim_exchange_unit FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER claim_exchange_history_immutable BEFORE UPDATE OR DELETE ON claim_exchange_history FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER claim_exchange_command_immutable BEFORE UPDATE OR DELETE ON claim_exchange_command FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER inventory_exchange_event_immutable BEFORE UPDATE OR DELETE ON inventory_exchange_event FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE FUNCTION exchange_request_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR (OLD.id,OLD.order_id,OLD.seller_order_id,OLD.hold_id,OLD.cause_id,OLD.request_key,OLD.request_fingerprint,OLD.reason,OLD.requested_at,OLD.receipt,OLD.source_snapshot) IS DISTINCT FROM (NEW.id,NEW.order_id,NEW.seller_order_id,NEW.hold_id,NEW.cause_id,NEW.request_key,NEW.request_fingerprint,NEW.reason,NEW.requested_at,NEW.receipt,NEW.source_snapshot)
 THEN RAISE EXCEPTION 'IMMUTABLE_EXCHANGE_REQUEST' USING ERRCODE='23514';END IF;RETURN NEW;
END $$;
CREATE TRIGGER claim_exchange_request_immutable BEFORE UPDATE OR DELETE ON claim_exchange_request FOR EACH ROW EXECUTE FUNCTION exchange_request_immutable();
CREATE FUNCTION validate_exchange_graph() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid;a order_exchange_application;c claim_exchange_request;n integer;
BEGIN
 IF TG_TABLE_NAME IN ('order_exchange_application','claim_exchange_request') THEN target=NEW.id;
 ELSIF TG_TABLE_NAME='inventory_exchange_event' THEN SELECT exchange_id INTO target FROM inventory_exchange_reservation WHERE id=NEW.reservation_id;
 ELSE target=NEW.exchange_id;END IF;
 SELECT * INTO a FROM order_exchange_application WHERE id=target;SELECT * INTO c FROM claim_exchange_request WHERE id=target;
 IF a.id IS NULL OR c.id IS NULL OR a.source_snapshot<>c.source_snapshot OR c.source_snapshot->>'id'<>c.id::text OR c.receipt->>'exchangeId'<>c.id::text OR c.receipt->>'holdId'<>c.hold_id::text
 OR NOT EXISTS(SELECT 1 FROM order_unit_hold h WHERE h.id=a.hold_id AND h.kind='EXCHANGE' AND h.state='ACTIVE' AND h.cause_id=a.cause_id AND h.order_id=a.order_id)
 THEN RAISE EXCEPTION 'EXCHANGE_ACCEPTED_GRAPH' USING ERRCODE='23514';END IF;
 SELECT count(*) INTO n FROM claim_exchange_unit WHERE exchange_id=target;
 IF n=0 OR n<>(SELECT count(*) FROM order_exchange_unit WHERE exchange_id=target) OR n<>(SELECT count(*) FROM order_hold_unit WHERE hold_id=a.hold_id) OR n<>jsonb_array_length(a.source_snapshot->'units') OR n<>jsonb_array_length(c.receipt->'units')
 OR EXISTS(SELECT 1 FROM claim_exchange_unit u JOIN order_exchange_unit e ON e.exchange_id=u.exchange_id AND e.item_id=u.item_id AND e.ordinal=u.ordinal JOIN order_physical_unit p ON p.id=e.physical_id JOIN order_unit_lifecycle l ON l.item_id=u.item_id AND l.ordinal=u.ordinal JOIN inventory_reservation r ON r.id=p.reservation_id JOIN order_initial_delivery d ON d.event_id=p.delivery_event_id JOIN order_item i ON i.id=u.item_id WHERE u.exchange_id=target AND (u.hold_id<>a.hold_id OR e.hold_id<>a.hold_id OR u.physical_id<>p.id OR p.order_id<>a.order_id OR p.seller_order_id<>a.seller_order_id OR l.state<>'HELD' OR l.active_hold_id IS DISTINCT FROM a.hold_id OR r.status<>'COMMITTED' OR r.checkout_id<>p.checkout_id OR r.order_item_id<>p.item_id OR r.sku_id<>p.sku_id OR r.quantity<=p.ordinal OR d.shipment_id<>p.delivery_bucket_id OR d.occurred_at<>p.delivered_at OR d.order_id<>a.order_id OR d.seller_order_id<>a.seller_order_id OR i.sku_id<>p.sku_id OR NOT EXISTS(SELECT 1 FROM order_initial_delivery_unit du WHERE du.event_id=d.event_id AND du.item_id=p.item_id AND du.ordinal=p.ordinal)))
 THEN RAISE EXCEPTION 'EXCHANGE_EXACT_SOURCE_UNITS' USING ERRCODE='23514';END IF;
 IF NOT EXISTS(SELECT 1 FROM claim_exchange_history h WHERE h.exchange_id=target AND h.version=0 AND h.action='REQUESTED' AND h.reason=c.reason AND h.occurred_at=c.requested_at)
 OR NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='EXCHANGE_RECORDED.v1' AND j.dedupe_key='exchange-requested:'||target::text AND j.cause_key=target::text AND j.payload=target::text)
 THEN RAISE EXCEPTION 'EXCHANGE_REQUEST_RECEIPT_OUTBOX' USING ERRCODE='23514';END IF;
 IF c.state='REQUESTED' AND EXISTS(SELECT 1 FROM inventory_exchange_reservation WHERE exchange_id=target) THEN RAISE EXCEPTION 'UNAPPROVED_EXCHANGE_RESERVATION' USING ERRCODE='23514';END IF;
 IF c.state='APPROVED' THEN
  IF n<>(SELECT count(*) FROM inventory_exchange_reservation WHERE exchange_id=target AND status='RESERVED') OR NOT EXISTS(SELECT 1 FROM claim_exchange_history WHERE exchange_id=target AND version=1 AND action='APPROVED') OR NOT EXISTS(SELECT 1 FROM claim_exchange_command WHERE exchange_id=target AND version=1 AND action='APPROVED')
  OR NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='EXCHANGE_RECORDED.v1' AND j.dedupe_key='exchange-approved:'||target::text AND j.cause_key=target::text AND j.payload=target::text)
  OR EXISTS(SELECT 1 FROM inventory_exchange_reservation r JOIN claim_exchange_unit u ON u.exchange_id=r.exchange_id AND u.physical_id=r.physical_id JOIN order_physical_unit p ON p.id=u.physical_id WHERE r.exchange_id=target AND (r.order_id<>a.order_id OR r.seller_order_id<>a.seller_order_id OR r.checkout_id<>p.checkout_id OR r.item_id<>p.item_id OR r.ordinal<>p.ordinal OR r.source_reservation_id<>p.reservation_id OR r.source_sku_id<>p.sku_id OR r.target_sku_id<>u.target_sku_id OR r.approved_at<>c.approved_at OR r.expires_at<>c.expires_at OR NOT EXISTS(SELECT 1 FROM inventory_exchange_event e WHERE e.reservation_id=r.id AND e.cause='RESERVE' AND e.occurred_at=c.approved_at)))
  THEN RAISE EXCEPTION 'EXCHANGE_APPROVAL_RESERVATION_GRAPH' USING ERRCODE='23514';END IF;
 END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER accepted_exchange_complete AFTER INSERT ON order_exchange_application DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_exchange_graph();
CREATE CONSTRAINT TRIGGER exchange_workflow_complete AFTER INSERT OR UPDATE ON claim_exchange_request DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_exchange_graph();
CREATE CONSTRAINT TRIGGER exchange_source_units_complete AFTER INSERT ON order_exchange_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_exchange_graph();
CREATE CONSTRAINT TRIGGER exchange_claim_units_complete AFTER INSERT ON claim_exchange_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_exchange_graph();
CREATE CONSTRAINT TRIGGER exchange_reservation_complete AFTER INSERT OR UPDATE ON inventory_exchange_reservation DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_exchange_graph();
CREATE CONSTRAINT TRIGGER exchange_stock_event_complete AFTER INSERT ON inventory_exchange_event DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_exchange_graph();
CREATE CONSTRAINT TRIGGER exchange_history_complete AFTER INSERT ON claim_exchange_history DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_exchange_graph();
CREATE CONSTRAINT TRIGGER exchange_command_complete AFTER INSERT ON claim_exchange_command DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_exchange_graph();
