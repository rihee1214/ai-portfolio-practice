-- Approval and its original seven-day reservation remain immutable. One exact extension is separate evidence.
CREATE TABLE inventory_exchange_extension (
 id uuid PRIMARY KEY,exchange_id uuid NOT NULL UNIQUE REFERENCES claim_exchange_request(id),previous_expires_at timestamptz NOT NULL,extended_expires_at timestamptz NOT NULL,extended_at timestamptz NOT NULL,
 created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),CHECK(extended_expires_at=previous_expires_at+interval '7 days' AND extended_at<previous_expires_at),UNIQUE(id,exchange_id,previous_expires_at,extended_expires_at,extended_at)
);
ALTER TABLE inventory_exchange_reservation ADD CONSTRAINT exchange_reservation_original_deadline_identity UNIQUE(id,exchange_id,expires_at);
CREATE TABLE inventory_exchange_extension_unit (
 extension_id uuid NOT NULL,exchange_id uuid NOT NULL,reservation_id uuid NOT NULL UNIQUE,previous_expires_at timestamptz NOT NULL,extended_expires_at timestamptz NOT NULL,extended_at timestamptz NOT NULL,
 PRIMARY KEY(extension_id,reservation_id),FOREIGN KEY(extension_id,exchange_id,previous_expires_at,extended_expires_at,extended_at) REFERENCES inventory_exchange_extension(id,exchange_id,previous_expires_at,extended_expires_at,extended_at),
 FOREIGN KEY(reservation_id,exchange_id,previous_expires_at) REFERENCES inventory_exchange_reservation(id,exchange_id,expires_at)
);
CREATE TABLE claim_exchange_extension (
 id uuid PRIMARY KEY,exchange_id uuid NOT NULL UNIQUE REFERENCES claim_exchange_request(id),command_key varchar(100) NOT NULL,fingerprint varchar(64) NOT NULL,
 original_revision bigint NOT NULL,revision bigint NOT NULL CHECK(revision=original_revision+1),prior_state varchar(32) NOT NULL CHECK(prior_state IN('APPROVED','COLLECTING','READY','REVIEW_REQUIRED','DISPATCH_PENDING')),
 previous_expires_at timestamptz NOT NULL,extended_expires_at timestamptz NOT NULL,extended_at timestamptz NOT NULL,actor_id uuid NOT NULL REFERENCES identity_user(id),reason varchar(500) NOT NULL CHECK(length(btrim(reason))>0),receipt jsonb NOT NULL,
 CHECK(extended_expires_at=previous_expires_at+interval '7 days' AND extended_at<previous_expires_at),
 FOREIGN KEY(id,exchange_id,previous_expires_at,extended_expires_at,extended_at) REFERENCES inventory_exchange_extension(id,exchange_id,previous_expires_at,extended_expires_at,extended_at) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(exchange_id,command_key) REFERENCES claim_exchange_command(exchange_id,command_key) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(exchange_id,original_revision) REFERENCES claim_exchange_history(exchange_id,version),FOREIGN KEY(exchange_id,revision) REFERENCES claim_exchange_history(exchange_id,version) DEFERRABLE INITIALLY DEFERRED
);
ALTER TABLE inventory_exchange_extension ADD FOREIGN KEY(id) REFERENCES claim_exchange_extension(id) DEFERRABLE INITIALLY DEFERRED;
CREATE TRIGGER inventory_exchange_extension_immutable BEFORE UPDATE OR DELETE ON inventory_exchange_extension FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER inventory_exchange_extension_unit_immutable BEFORE UPDATE OR DELETE ON inventory_exchange_extension_unit FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER claim_exchange_extension_immutable BEFORE UPDATE OR DELETE ON claim_exchange_extension FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE FUNCTION exchange_original_reservation_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN IF TG_OP='DELETE' OR(to_jsonb(NEW)-'status') IS DISTINCT FROM(to_jsonb(OLD)-'status') THEN RAISE EXCEPTION 'IMMUTABLE_EXCHANGE_ORIGINAL_RESERVATION' USING ERRCODE='23514';END IF;RETURN NEW;END $$;
CREATE TRIGGER exchange_original_reservation_protected BEFORE UPDATE OR DELETE ON inventory_exchange_reservation FOR EACH ROW EXECUTE FUNCTION exchange_original_reservation_immutable();
CREATE FUNCTION exchange_extension_unit_current_reservation() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN IF NOT EXISTS(SELECT 1 FROM inventory_exchange_extension e JOIN inventory_exchange_reservation r ON r.exchange_id=e.exchange_id WHERE e.id=NEW.extension_id AND e.created_xid=pg_current_xact_id() AND r.id=NEW.reservation_id AND r.status='RESERVED' AND e.extended_at>=r.approved_at AND e.previous_expires_at=r.expires_at) THEN RAISE EXCEPTION 'EXCHANGE_EXTENSION_CURRENT_RESERVED_SOURCE_REQUIRED' USING ERRCODE='23514';END IF;RETURN NEW;END $$;
CREATE TRIGGER exchange_extension_unit_current BEFORE INSERT ON inventory_exchange_extension_unit FOR EACH ROW EXECUTE FUNCTION exchange_extension_unit_current_reservation();
CREATE FUNCTION verify_exchange_extension(p_id uuid) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE c claim_exchange_request;e claim_exchange_extension;n integer;
BEGIN
 SELECT * INTO c FROM claim_exchange_request WHERE id=p_id;SELECT * INTO e FROM claim_exchange_extension WHERE exchange_id=p_id;
 IF e.id IS NULL THEN IF EXISTS(SELECT 1 FROM inventory_exchange_extension WHERE exchange_id=p_id) OR EXISTS(SELECT 1 FROM inventory_exchange_extension_unit WHERE exchange_id=p_id) OR(c.approved_at IS NOT NULL AND c.expires_at IS DISTINCT FROM c.approved_at+interval '7 days') THEN RAISE EXCEPTION 'EXCHANGE_EXTENSION_COMMAND_REQUIRED' USING ERRCODE='23514';END IF;RETURN;END IF;
 SELECT count(*) INTO n FROM claim_exchange_unit WHERE exchange_id=p_id;
 IF c.id IS NULL OR c.version<e.revision OR c.expires_at IS DISTINCT FROM e.extended_expires_at OR e.previous_expires_at IS DISTINCT FROM c.approved_at+interval '7 days'
 OR n=0 OR n<>(SELECT count(*) FROM inventory_exchange_extension_unit WHERE extension_id=e.id)
 OR NOT EXISTS(SELECT 1 FROM inventory_exchange_extension x WHERE x.id=e.id AND(x.exchange_id,x.previous_expires_at,x.extended_expires_at,x.extended_at)=(e.exchange_id,e.previous_expires_at,e.extended_expires_at,e.extended_at))
 OR NOT EXISTS(SELECT 1 FROM claim_exchange_history h WHERE h.exchange_id=p_id AND h.version=e.original_revision AND h.action=e.prior_state)
 OR NOT EXISTS(SELECT 1 FROM claim_exchange_history h WHERE h.exchange_id=p_id AND h.version=e.revision AND h.action='EXTENDED' AND h.actor='MEMBER:'||e.actor_id::text AND h.reason=e.reason AND h.occurred_at=e.extended_at)
 OR NOT EXISTS(SELECT 1 FROM claim_exchange_command command WHERE command.exchange_id=p_id AND command.command_key=e.command_key AND command.fingerprint=e.fingerprint AND command.action='EXTENDED' AND command.version=e.revision AND command.receipt=e.receipt)
 OR e.receipt->>'state' IS DISTINCT FROM e.prior_state OR(e.receipt->>'version')::bigint IS DISTINCT FROM e.revision OR e.receipt->'accepted'->>'exchangeId' IS DISTINCT FROM p_id::text
 OR NOT EXISTS(SELECT 1 FROM outbox_event outbox JOIN job work ON work.id=outbox.job_id WHERE work.type='EXCHANGE_RECORDED.v1' AND work.dedupe_key='exchange-reservation-extended:'||p_id::text AND work.cause_key=p_id::text AND work.payload=p_id::text)
 OR EXISTS(SELECT 1 FROM claim_exchange_unit u WHERE u.exchange_id=p_id AND NOT EXISTS(SELECT 1 FROM inventory_exchange_extension_unit x JOIN inventory_exchange_reservation r ON r.id=x.reservation_id WHERE x.extension_id=e.id AND r.exchange_id=p_id AND r.physical_id=u.physical_id AND r.item_id=u.item_id AND r.ordinal=u.ordinal AND r.target_sku_id=u.target_sku_id AND r.expires_at=e.previous_expires_at AND e.extended_at>=r.approved_at AND EXISTS(SELECT 1 FROM jsonb_array_elements(e.receipt->'reservations') saved WHERE saved->>'id'=r.id::text AND saved->>'status'='RESERVED' AND(saved->>'expiresAt')::timestamptz=e.extended_expires_at)))
 THEN RAISE EXCEPTION 'EXACT_ONE_REASONED_EXCHANGE_EXTENSION_GRAPH_REQUIRED' USING ERRCODE='23514';END IF;
END $$;
CREATE FUNCTION exchange_extension_graph_guard() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN IF TG_TABLE_NAME='claim_exchange_request' THEN PERFORM verify_exchange_extension(NEW.id);ELSE PERFORM verify_exchange_extension(NEW.exchange_id);END IF;RETURN NULL;END $$;
CREATE CONSTRAINT TRIGGER exchange_extension_workflow_graph AFTER INSERT OR UPDATE ON claim_exchange_request DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_extension_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_extension_claim_graph AFTER INSERT ON claim_exchange_extension DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_extension_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_extension_inventory_graph AFTER INSERT ON inventory_exchange_extension DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_extension_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_extension_inventory_unit_graph AFTER INSERT ON inventory_exchange_extension_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_extension_graph_guard();
CREATE OR REPLACE FUNCTION validate_exchange_graph() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE target uuid;a order_exchange_application;c claim_exchange_request;n integer;
BEGIN
 IF TG_TABLE_NAME IN ('order_exchange_application','claim_exchange_request') THEN target=NEW.id;
 ELSIF TG_TABLE_NAME='inventory_exchange_event' THEN SELECT exchange_id INTO target FROM inventory_exchange_reservation WHERE id=NEW.reservation_id;
 ELSE target=NEW.exchange_id;END IF;
 SELECT * INTO a FROM order_exchange_application WHERE id=target;SELECT * INTO c FROM claim_exchange_request WHERE id=target;
 IF a.id IS NULL OR c.id IS NULL OR a.source_snapshot<>c.source_snapshot OR c.source_snapshot->>'id'<>c.id::text OR c.receipt->>'exchangeId'<>c.id::text OR c.receipt->>'holdId'<>c.hold_id::text
 OR NOT EXISTS(SELECT 1 FROM order_unit_hold h WHERE h.id=a.hold_id AND h.kind='EXCHANGE' AND (h.state='ACTIVE' OR c.state='COMPLETED' AND h.state='RELEASED' AND h.outcome='EXCHANGED' AND EXISTS(SELECT 1 FROM order_exchange_completion z WHERE z.exchange_id=c.id AND z.hold_id=h.id)) AND h.cause_id=a.cause_id AND h.order_id=a.order_id)
 THEN RAISE EXCEPTION 'EXCHANGE_ACCEPTED_GRAPH' USING ERRCODE='23514';END IF;
 SELECT count(*) INTO n FROM claim_exchange_unit WHERE exchange_id=target;
 IF n=0 OR n<>(SELECT count(*) FROM order_exchange_unit WHERE exchange_id=target) OR n<>(SELECT count(*) FROM order_hold_unit WHERE hold_id=a.hold_id) OR n<>jsonb_array_length(a.source_snapshot->'units') OR n<>jsonb_array_length(c.receipt->'units')
 OR EXISTS(SELECT 1 FROM claim_exchange_unit u JOIN order_exchange_unit e ON e.exchange_id=u.exchange_id AND e.item_id=u.item_id AND e.ordinal=u.ordinal JOIN order_physical_unit p ON p.id=e.physical_id JOIN order_unit_lifecycle l ON l.item_id=u.item_id AND l.ordinal=u.ordinal JOIN inventory_reservation r ON r.id=p.reservation_id JOIN order_initial_delivery d ON d.event_id=p.delivery_event_id JOIN order_item i ON i.id=u.item_id WHERE u.exchange_id=target AND (u.hold_id<>a.hold_id OR e.hold_id<>a.hold_id OR u.physical_id<>p.id OR p.order_id<>a.order_id OR p.seller_order_id<>a.seller_order_id OR c.state<>'COMPLETED' AND (l.state<>'HELD' OR l.active_hold_id IS DISTINCT FROM a.hold_id) OR r.status<>'COMMITTED' OR r.checkout_id<>p.checkout_id OR r.order_item_id<>p.item_id OR r.sku_id<>p.sku_id OR r.quantity<=p.ordinal OR d.shipment_id<>p.delivery_bucket_id OR d.occurred_at<>p.delivered_at OR d.order_id<>a.order_id OR d.seller_order_id<>a.seller_order_id OR i.sku_id<>p.sku_id OR NOT EXISTS(SELECT 1 FROM order_initial_delivery_unit du WHERE du.event_id=d.event_id AND du.item_id=p.item_id AND du.ordinal=p.ordinal)))
 THEN RAISE EXCEPTION 'EXCHANGE_EXACT_SOURCE_UNITS' USING ERRCODE='23514';END IF;
 IF NOT EXISTS(SELECT 1 FROM claim_exchange_history h WHERE h.exchange_id=target AND h.version=0 AND h.action='REQUESTED' AND h.reason=c.reason AND h.occurred_at=c.requested_at)
 OR NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='EXCHANGE_RECORDED.v1' AND j.dedupe_key='exchange-requested:'||target::text AND j.cause_key=target::text AND j.payload=target::text)
 THEN RAISE EXCEPTION 'EXCHANGE_REQUEST_RECEIPT_OUTBOX' USING ERRCODE='23514';END IF;
 IF c.state='REQUESTED' AND EXISTS(SELECT 1 FROM inventory_exchange_reservation WHERE exchange_id=target) THEN RAISE EXCEPTION 'UNAPPROVED_EXCHANGE_RESERVATION' USING ERRCODE='23514';END IF;
 IF c.state='APPROVED' THEN
  IF n<>(SELECT count(*) FROM inventory_exchange_reservation WHERE exchange_id=target AND status='RESERVED') OR NOT EXISTS(SELECT 1 FROM claim_exchange_history WHERE exchange_id=target AND version=1 AND action='APPROVED') OR NOT EXISTS(SELECT 1 FROM claim_exchange_command WHERE exchange_id=target AND version=1 AND action='APPROVED')
  OR NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='EXCHANGE_RECORDED.v1' AND j.dedupe_key='exchange-approved:'||target::text AND j.cause_key=target::text AND j.payload=target::text)
  OR EXISTS(SELECT 1 FROM inventory_exchange_reservation r JOIN claim_exchange_unit u ON u.exchange_id=r.exchange_id AND u.physical_id=r.physical_id JOIN order_physical_unit p ON p.id=u.physical_id WHERE r.exchange_id=target AND (r.order_id<>a.order_id OR r.seller_order_id<>a.seller_order_id OR r.checkout_id<>p.checkout_id OR r.item_id<>p.item_id OR r.ordinal<>p.ordinal OR r.source_reservation_id<>p.reservation_id OR r.source_sku_id<>p.sku_id OR r.target_sku_id<>u.target_sku_id OR r.approved_at<>c.approved_at OR COALESCE((SELECT extended_expires_at FROM inventory_exchange_extension_unit WHERE reservation_id=r.id),r.expires_at)<>c.expires_at OR NOT EXISTS(SELECT 1 FROM inventory_exchange_event e WHERE e.reservation_id=r.id AND e.cause='RESERVE' AND e.occurred_at=c.approved_at)))
  THEN RAISE EXCEPTION 'EXCHANGE_APPROVAL_RESERVATION_GRAPH' USING ERRCODE='23514';END IF;
 END IF;RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION exchange_reship_graph() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE v_id uuid;c claim_exchange_request;r claim_exchange_dispatch_request;h fulfillment_exchange_shipment;e claim_exchange_dispatch_effect;n integer;
BEGIN
 IF TG_TABLE_NAME='claim_exchange_request' THEN v_id=NEW.id;
 ELSIF TG_TABLE_NAME='inventory_exchange_commit' THEN SELECT exchange_id INTO v_id FROM inventory_exchange_reservation WHERE id=NEW.reservation_id;
 ELSE v_id=NEW.exchange_id;END IF;
 SELECT * INTO c FROM claim_exchange_request WHERE id=v_id;SELECT * INTO r FROM claim_exchange_dispatch_request WHERE exchange_id=v_id;
 IF r.id IS NULL THEN
  IF c.state IN('DISPATCH_PENDING','DISPATCHED') THEN RAISE EXCEPTION 'ACTUAL_EXCHANGE_DISPATCH_REQUEST_REQUIRED' USING ERRCODE='23514';END IF;RETURN NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM claim_exchange_command z JOIN claim_exchange_history t ON t.exchange_id=z.exchange_id AND t.version=z.version WHERE z.exchange_id=v_id AND z.command_key=r.command_key AND z.action='DISPATCH_PENDING' AND z.version=r.version AND t.action=z.action)
 OR NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='EXCHANGE_RESHIP.v1' AND j.dedupe_key='exchange-reship:'||r.id::text AND j.cause_key=v_id::text AND j.payload=v_id::text||'|'||r.id::text)
 OR EXISTS(SELECT 1 FROM claim_exchange_unit u WHERE u.exchange_id=v_id AND NOT EXISTS(SELECT 1 FROM claim_exchange_collection x JOIN claim_exchange_inspection_unit i ON i.collection_id=x.id AND i.exchange_id=x.exchange_id AND i.item_id=x.item_id AND i.ordinal=x.ordinal AND i.physical_id=x.physical_id WHERE x.exchange_id=v_id AND x.item_id=u.item_id AND x.ordinal=u.ordinal AND x.status='RECEIVED'))
 THEN RAISE EXCEPTION 'EXACT_INSPECTED_EXCHANGE_DISPATCH_COMMAND_REQUIRED' USING ERRCODE='23514';END IF;
 SELECT * INTO h FROM fulfillment_exchange_shipment WHERE exchange_id=v_id;SELECT * INTO e FROM claim_exchange_dispatch_effect WHERE exchange_id=v_id;
 IF h.id IS NULL THEN
  IF c.state<>'DISPATCH_PENDING' OR e.exchange_id IS NOT NULL OR EXISTS(SELECT 1 FROM inventory_exchange_reservation WHERE exchange_id=v_id AND status<>'RESERVED') THEN RAISE EXCEPTION 'PENDING_EXCHANGE_CANNOT_COMMIT_STOCK' USING ERRCODE='23514';END IF;RETURN NULL;
 END IF;
 SELECT count(*) INTO n FROM claim_exchange_unit WHERE exchange_id=v_id;
 IF e.exchange_id IS NULL OR e.version<>(r.version+1+CASE WHEN EXISTS(SELECT 1 FROM claim_exchange_extension x WHERE x.exchange_id=v_id AND x.prior_state='DISPATCH_PENDING' AND x.original_revision=r.version AND x.revision=r.version+1) THEN 1 ELSE 0 END) OR e.dispatch_id<>r.id OR e.shipment_id<>h.id OR h.dispatch_id<>r.id OR h.order_id<>c.order_id OR h.seller_order_id<>c.seller_order_id
 OR c.state NOT IN('DISPATCHED','COMPLETED') OR n<>(SELECT count(*) FROM fulfillment_exchange_allocation WHERE exchange_id=v_id)
 OR n<>(SELECT count(*) FROM inventory_exchange_reservation WHERE exchange_id=v_id AND status='COMMITTED')
 OR NOT EXISTS(SELECT 1 FROM job j WHERE j.id=e.job_id AND j.type='EXCHANGE_RESHIP.v1' AND j.dedupe_key='exchange-reship:'||r.id::text AND j.cause_key=v_id::text AND j.payload=v_id::text||'|'||r.id::text)
 OR NOT EXISTS(SELECT 1 FROM order_exchange_dispatch d JOIN fulfillment_exchange_event f ON f.id=d.event_id WHERE d.exchange_id=v_id AND d.shipment_id=h.id AND d.dispatched_at=h.shipped_at AND f.shipment_id=h.id AND f.state='SHIPPED' AND f.occurred_at=h.shipped_at)
 OR EXISTS(SELECT 1 FROM inventory_exchange_reservation x LEFT JOIN inventory_exchange_commit z ON z.reservation_id=x.id LEFT JOIN inventory_exchange_source source ON source.reservation_id=x.id LEFT JOIN fulfillment_exchange_allocation a ON a.reservation_id=x.id WHERE x.exchange_id=v_id AND (z.reservation_id IS NULL OR z.dispatch_id<>r.id OR z.committed_at>=COALESCE((SELECT extended_expires_at FROM inventory_exchange_extension_unit WHERE reservation_id=x.id),x.expires_at) OR source.source_id IS NULL OR a.exchange_id IS DISTINCT FROM v_id OR a.source_id IS DISTINCT FROM source.source_id OR a.item_id<>x.item_id OR a.ordinal<>x.ordinal OR NOT EXISTS(SELECT 1 FROM order_physical_source p WHERE p.id=a.physical_id AND p.predecessor_id=a.predecessor_id AND p.committed_source_id=a.source_id)))
 THEN RAISE EXCEPTION 'EXACT_COMMITTED_EXCHANGE_SHIPMENT_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;
