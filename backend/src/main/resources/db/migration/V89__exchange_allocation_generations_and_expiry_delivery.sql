-- Immutable generations separate historical approval/extension/release from the current allocation.
ALTER TABLE claim_exchange_request ADD COLUMN current_sequence integer NOT NULL DEFAULT 0 CHECK(current_sequence>=0);
UPDATE claim_exchange_request c SET current_sequence=COALESCE((SELECT max(sequence) FROM inventory_exchange_reservation WHERE exchange_id=c.id),0);
ALTER TABLE inventory_exchange_reservation DROP CONSTRAINT inventory_exchange_reservation_sequence_check;
ALTER TABLE inventory_exchange_reservation ADD CONSTRAINT inventory_exchange_reservation_sequence_check CHECK(sequence>=1);
ALTER TABLE inventory_exchange_reservation DROP CONSTRAINT inventory_exchange_reservation_status_check;
ALTER TABLE inventory_exchange_reservation ADD CONSTRAINT inventory_exchange_reservation_status_check CHECK(status IN('RESERVED','COMMITTED','EXPIRED','RELEASED'));
CREATE TABLE inventory_exchange_approval (
 exchange_id uuid NOT NULL REFERENCES claim_exchange_request(id),sequence integer NOT NULL CHECK(sequence>=1),order_id uuid NOT NULL,seller_order_id uuid NOT NULL,checkout_id uuid NOT NULL,
 approved_at timestamptz NOT NULL,expires_at timestamptz NOT NULL CHECK(expires_at=approved_at+interval '7 days'),created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
 PRIMARY KEY(exchange_id,sequence),UNIQUE(exchange_id,sequence,order_id,seller_order_id,checkout_id,approved_at,expires_at)
);
INSERT INTO inventory_exchange_approval(exchange_id,sequence,order_id,seller_order_id,checkout_id,approved_at,expires_at) SELECT DISTINCT exchange_id,sequence,order_id,seller_order_id,checkout_id,approved_at,expires_at FROM inventory_exchange_reservation;
ALTER TABLE inventory_exchange_reservation ADD FOREIGN KEY(exchange_id,sequence,order_id,seller_order_id,checkout_id,approved_at,expires_at) REFERENCES inventory_exchange_approval(exchange_id,sequence,order_id,seller_order_id,checkout_id,approved_at,expires_at) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE claim_exchange_approval_generation (
 exchange_id uuid NOT NULL REFERENCES claim_exchange_request(id),sequence integer NOT NULL CHECK(sequence>=1),command_key varchar(100) NOT NULL,original_revision bigint NOT NULL,revision bigint NOT NULL CHECK(revision=original_revision+1),
 approved_at timestamptz NOT NULL,expires_at timestamptz NOT NULL CHECK(expires_at=approved_at+interval '7 days'),receipt jsonb NOT NULL,created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
 PRIMARY KEY(exchange_id,sequence),UNIQUE(exchange_id,command_key),FOREIGN KEY(exchange_id,sequence) REFERENCES inventory_exchange_approval(exchange_id,sequence) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(exchange_id,command_key) REFERENCES claim_exchange_command(exchange_id,command_key) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(exchange_id,original_revision) REFERENCES claim_exchange_history(exchange_id,version),FOREIGN KEY(exchange_id,revision) REFERENCES claim_exchange_history(exchange_id,version) DEFERRABLE INITIALLY DEFERRED
);
INSERT INTO claim_exchange_approval_generation(exchange_id,sequence,command_key,original_revision,revision,approved_at,expires_at,receipt) SELECT a.exchange_id,a.sequence,c.command_key,c.version-1,c.version,a.approved_at,a.expires_at,c.receipt FROM inventory_exchange_approval a JOIN claim_exchange_command c ON c.exchange_id=a.exchange_id AND c.action='APPROVED' AND c.version=1 WHERE a.sequence=1;
ALTER TABLE inventory_exchange_approval ADD FOREIGN KEY(exchange_id,sequence) REFERENCES claim_exchange_approval_generation(exchange_id,sequence) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE inventory_exchange_extension ADD COLUMN sequence integer NOT NULL DEFAULT 1 CHECK(sequence>=1);
ALTER TABLE inventory_exchange_extension ADD FOREIGN KEY(exchange_id,sequence) REFERENCES inventory_exchange_approval(exchange_id,sequence);
ALTER TABLE claim_exchange_extension ADD COLUMN sequence integer NOT NULL DEFAULT 1 CHECK(sequence>=1);
ALTER TABLE claim_exchange_extension ADD FOREIGN KEY(exchange_id,sequence) REFERENCES claim_exchange_approval_generation(exchange_id,sequence);
CREATE TABLE claim_exchange_reservation_schedule (
 exchange_id uuid NOT NULL,sequence integer NOT NULL,epoch integer NOT NULL CHECK(epoch IN(1,2)),due_at timestamptz NOT NULL,job_id uuid NOT NULL UNIQUE REFERENCES job(id),outbox_id uuid NOT NULL UNIQUE REFERENCES outbox_event(id),
 PRIMARY KEY(exchange_id,sequence,epoch),UNIQUE(job_id,exchange_id,sequence,epoch),FOREIGN KEY(exchange_id,sequence) REFERENCES claim_exchange_approval_generation(exchange_id,sequence)
);
-- Genuine previous approvals get a new due delivery without rewriting any old command or source timestamp.
INSERT INTO job(id,type,dedupe_key,cause_key,payload,scheduled_at,next_run_at,status,attempts)
 SELECT gen_random_uuid(),'EXCHANGE_RESERVATION_DUE.v1','exchange-reservation-due:'||exchange_id::text||':'||sequence::text||':1',exchange_id::text,exchange_id::text||'|'||sequence::text||'|1',expires_at,expires_at,'READY',0 FROM claim_exchange_approval_generation;
INSERT INTO outbox_event(id,job_id) SELECT gen_random_uuid(),j.id FROM job j WHERE j.type='EXCHANGE_RESERVATION_DUE.v1';
INSERT INTO claim_exchange_reservation_schedule(exchange_id,sequence,epoch,due_at,job_id,outbox_id) SELECT a.exchange_id,a.sequence,1,a.expires_at,j.id,o.id FROM claim_exchange_approval_generation a JOIN job j ON j.dedupe_key='exchange-reservation-due:'||a.exchange_id::text||':'||a.sequence::text||':1' JOIN outbox_event o ON o.job_id=j.id;
INSERT INTO job(id,type,dedupe_key,cause_key,payload,scheduled_at,next_run_at,status,attempts)
 SELECT gen_random_uuid(),'EXCHANGE_RESERVATION_DUE.v1','exchange-reservation-due:'||exchange_id::text||':'||sequence::text||':2',exchange_id::text,exchange_id::text||'|'||sequence::text||'|2',extended_expires_at,extended_expires_at,'READY',0 FROM claim_exchange_extension;
INSERT INTO outbox_event(id,job_id) SELECT gen_random_uuid(),j.id FROM job j LEFT JOIN outbox_event o ON o.job_id=j.id WHERE j.type='EXCHANGE_RESERVATION_DUE.v1' AND o.id IS NULL;
INSERT INTO claim_exchange_reservation_schedule(exchange_id,sequence,epoch,due_at,job_id,outbox_id) SELECT a.exchange_id,a.sequence,2,a.extended_expires_at,j.id,o.id FROM claim_exchange_extension a JOIN job j ON j.dedupe_key='exchange-reservation-due:'||a.exchange_id::text||':'||a.sequence::text||':2' JOIN outbox_event o ON o.job_id=j.id;
ALTER TABLE inventory_exchange_event DROP CONSTRAINT inventory_exchange_event_cause_check;
ALTER TABLE inventory_exchange_event ADD CONSTRAINT inventory_exchange_event_cause_check CHECK(cause IN('RESERVE','EXPIRE','RELEASE'));
ALTER TABLE inventory_exchange_event DROP CONSTRAINT inventory_exchange_event_check;
ALTER TABLE inventory_exchange_event ADD CONSTRAINT inventory_exchange_event_effect_check CHECK(version=before_version+1 AND after_reserved=before_reserved+CASE WHEN cause='RESERVE' THEN 1 ELSE -1 END);
ALTER TABLE inventory_exchange_event ADD CONSTRAINT exchange_stock_event_concrete_identity UNIQUE(id,reservation_id,cause);
CREATE TABLE inventory_exchange_release (
 id uuid PRIMARY KEY,exchange_id uuid NOT NULL,sequence integer NOT NULL,reason varchar(32) NOT NULL CHECK(reason IN('EXPIRED','REFUND_CONVERSION')),released_at timestamptz NOT NULL,
 UNIQUE(exchange_id,sequence),UNIQUE(id,exchange_id,sequence),FOREIGN KEY(exchange_id,sequence) REFERENCES inventory_exchange_approval(exchange_id,sequence)
);
CREATE TABLE inventory_exchange_release_unit (
 release_id uuid NOT NULL,exchange_id uuid NOT NULL,sequence integer NOT NULL,reservation_id uuid NOT NULL UNIQUE,event_id uuid NOT NULL UNIQUE,cause varchar(16) NOT NULL CHECK(cause IN('EXPIRE','RELEASE')),
 PRIMARY KEY(release_id,reservation_id),FOREIGN KEY(release_id,exchange_id,sequence) REFERENCES inventory_exchange_release(id,exchange_id,sequence),FOREIGN KEY(reservation_id) REFERENCES inventory_exchange_reservation(id),
 FOREIGN KEY(event_id,reservation_id,cause) REFERENCES inventory_exchange_event(id,reservation_id,cause) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE claim_exchange_expiry_delivery (
 job_id uuid PRIMARY KEY,exchange_id uuid NOT NULL,sequence integer NOT NULL,epoch integer NOT NULL,mode varchar(20) NOT NULL CHECK(mode IN('EXPIRED','SUPERSEDED','COMMITTED')),release_id uuid UNIQUE REFERENCES inventory_exchange_release(id) DEFERRABLE INITIALLY DEFERRED,
 workflow_revision bigint,owner_token uuid NOT NULL,recorded_at timestamptz NOT NULL,receipt jsonb NOT NULL,CHECK((mode='EXPIRED' AND release_id IS NOT NULL AND workflow_revision IS NOT NULL) OR(mode<>'EXPIRED' AND release_id IS NULL AND workflow_revision IS NULL)),
 FOREIGN KEY(job_id,exchange_id,sequence,epoch) REFERENCES claim_exchange_reservation_schedule(job_id,exchange_id,sequence,epoch)
);
ALTER TABLE claim_exchange_dispatch_request ADD COLUMN sequence integer NOT NULL DEFAULT 1 CHECK(sequence>=1);
ALTER TABLE claim_exchange_dispatch_request DROP CONSTRAINT claim_exchange_dispatch_request_exchange_id_key;
ALTER TABLE claim_exchange_dispatch_request ADD UNIQUE(exchange_id,sequence);
ALTER TABLE claim_exchange_dispatch_request ADD FOREIGN KEY(exchange_id,sequence) REFERENCES claim_exchange_approval_generation(exchange_id,sequence);
CREATE TRIGGER inventory_exchange_approval_immutable BEFORE UPDATE OR DELETE ON inventory_exchange_approval FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER claim_exchange_approval_generation_immutable BEFORE UPDATE OR DELETE ON claim_exchange_approval_generation FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER claim_exchange_reservation_schedule_immutable BEFORE UPDATE OR DELETE ON claim_exchange_reservation_schedule FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER inventory_exchange_release_immutable BEFORE UPDATE OR DELETE ON inventory_exchange_release FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER inventory_exchange_release_unit_immutable BEFORE UPDATE OR DELETE ON inventory_exchange_release_unit FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER claim_exchange_expiry_delivery_immutable BEFORE UPDATE OR DELETE ON claim_exchange_expiry_delivery FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();

CREATE FUNCTION verify_exchange_reservation_schedule(p_id uuid,p_sequence integer,p_epoch integer) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE schedule claim_exchange_reservation_schedule;expected timestamptz;
BEGIN
 SELECT * INTO schedule FROM claim_exchange_reservation_schedule WHERE exchange_id=p_id AND sequence=p_sequence AND epoch=p_epoch;
 IF p_epoch=1 THEN SELECT expires_at INTO expected FROM claim_exchange_approval_generation WHERE exchange_id=p_id AND sequence=p_sequence;ELSE SELECT extended_expires_at INTO expected FROM claim_exchange_extension WHERE exchange_id=p_id AND sequence=p_sequence;END IF;
 IF schedule.job_id IS NULL OR expected IS NULL OR schedule.due_at<>expected OR NOT EXISTS(SELECT 1 FROM job j JOIN outbox_event o ON o.job_id=j.id WHERE j.id=schedule.job_id AND o.id=schedule.outbox_id AND j.type='EXCHANGE_RESERVATION_DUE.v1' AND j.dedupe_key='exchange-reservation-due:'||p_id::text||':'||p_sequence::text||':'||p_epoch::text AND j.cause_key=p_id::text AND j.payload=p_id::text||'|'||p_sequence::text||'|'||p_epoch::text AND j.scheduled_at=expected) THEN RAISE EXCEPTION 'EXACT_EXCHANGE_RESERVATION_DUE_DELIVERY_REQUIRED' USING ERRCODE='23514';END IF;
END $$;
CREATE FUNCTION verify_exchange_approval_generation(p_id uuid,p_sequence integer) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE g claim_exchange_approval_generation;a inventory_exchange_approval;c claim_exchange_request;n integer;effective timestamptz;
BEGIN
 SELECT * INTO c FROM claim_exchange_request WHERE id=p_id;SELECT * INTO g FROM claim_exchange_approval_generation WHERE exchange_id=p_id AND sequence=p_sequence;SELECT * INTO a FROM inventory_exchange_approval WHERE exchange_id=p_id AND sequence=p_sequence;SELECT count(*) INTO n FROM claim_exchange_unit WHERE exchange_id=p_id;
 IF g.exchange_id IS NULL OR a.exchange_id IS NULL OR c.id IS NULL OR g.approved_at<c.requested_at OR n=0 OR c.current_sequence<p_sequence OR c.current_sequence<>(SELECT max(sequence) FROM claim_exchange_approval_generation WHERE exchange_id=p_id) OR c.current_sequence<>(SELECT count(*) FROM claim_exchange_approval_generation WHERE exchange_id=p_id)
 OR(a.order_id,a.seller_order_id,a.approved_at,a.expires_at) IS DISTINCT FROM(c.order_id,c.seller_order_id,g.approved_at,g.expires_at)
 OR n<>(SELECT count(*) FROM inventory_exchange_reservation WHERE exchange_id=p_id AND sequence=p_sequence)
 OR NOT EXISTS(SELECT 1 FROM claim_exchange_command command JOIN claim_exchange_history history ON history.exchange_id=command.exchange_id AND history.version=command.version WHERE command.exchange_id=p_id AND command.command_key=g.command_key AND command.action='APPROVED' AND command.version=g.revision AND command.receipt=g.receipt AND history.action='APPROVED' AND history.occurred_at=g.approved_at)
 OR NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='EXCHANGE_RECORDED.v1' AND j.dedupe_key=(CASE WHEN p_sequence=1 THEN 'exchange-approved:' ELSE 'exchange-approved-'||p_sequence::text||':' END)||p_id::text AND j.cause_key=p_id::text AND j.payload=p_id::text)
 OR(g.receipt->>'version')::bigint IS DISTINCT FROM g.revision OR g.receipt->'accepted'->>'exchangeId' IS DISTINCT FROM p_id::text
 OR n<>(SELECT count(*) FROM jsonb_array_elements(g.receipt->'reservations') saved WHERE(saved->>'sequence')::integer=p_sequence)
 OR EXISTS(SELECT 1 FROM inventory_exchange_reservation r JOIN claim_exchange_unit u ON u.exchange_id=r.exchange_id AND u.physical_id=r.physical_id WHERE r.exchange_id=p_id AND r.sequence=p_sequence AND((r.order_id,r.seller_order_id,r.checkout_id,r.item_id,r.ordinal,r.target_sku_id,r.approved_at,r.expires_at) IS DISTINCT FROM(a.order_id,a.seller_order_id,a.checkout_id,u.item_id,u.ordinal,u.target_sku_id,a.approved_at,a.expires_at)
  OR NOT EXISTS(SELECT 1 FROM jsonb_array_elements(g.receipt->'reservations') saved WHERE saved->>'id'=r.id::text AND saved->>'exchangeId'=p_id::text AND saved->>'physicalId'=r.physical_id::text AND saved->>'targetSkuId'=r.target_sku_id::text AND(saved->>'sequence')::integer=r.sequence AND(saved->>'approvedAt')::timestamptz=r.approved_at AND(saved->>'expiresAt')::timestamptz=r.expires_at AND saved->>'status'='RESERVED')
  OR NOT EXISTS(SELECT 1 FROM inventory_exchange_event e WHERE e.reservation_id=r.id AND e.cause='RESERVE' AND e.occurred_at=r.approved_at)))
 THEN RAISE EXCEPTION 'EXACT_EXCHANGE_APPROVAL_GENERATION_REQUIRED' USING ERRCODE='23514';END IF;
 IF p_sequence>1 AND NOT EXISTS(SELECT 1 FROM inventory_exchange_release released JOIN claim_exchange_expiry_delivery expiry ON expiry.release_id=released.id WHERE released.exchange_id=p_id AND released.sequence=p_sequence-1 AND released.reason='EXPIRED' AND expiry.mode='EXPIRED' AND released.released_at<=g.approved_at AND NOT EXISTS(SELECT 1 FROM inventory_exchange_reservation old WHERE old.exchange_id=p_id AND old.sequence=p_sequence-1 AND old.status<>'EXPIRED')) THEN RAISE EXCEPTION 'EXPLICIT_REAPPROVAL_REQUIRES_PREVIOUS_EXPIRY_EFFECT' USING ERRCODE='23514';END IF;
 IF EXISTS(SELECT 1 FROM claim_exchange_unit u WHERE u.exchange_id=p_id AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(g.receipt->'approvedOffers') offer JOIN jsonb_array_elements(c.source_snapshot->'units') original ON(original->>'itemId')::uuid=u.item_id AND(original->>'ordinal')::integer=u.ordinal WHERE offer->>'skuId'=u.target_sku_id::text AND offer->>'storeId'=c.source_snapshot->>'storeId' AND offer->>'price'=original->>'originalUnitPrice')) THEN RAISE EXCEPTION 'GENERATION_STORED_GROSS_PRICE_AND_OWN_STORE_REQUIRED' USING ERRCODE='23514';END IF;
 PERFORM verify_exchange_reservation_schedule(p_id,p_sequence,1);
 SELECT COALESCE((SELECT extended_expires_at FROM claim_exchange_extension WHERE exchange_id=p_id AND sequence=p_sequence),g.expires_at) INTO effective;
 IF p_sequence=c.current_sequence AND(c.approved_at,c.expires_at,c.approved_offers) IS DISTINCT FROM(g.approved_at,effective,g.receipt->'approvedOffers') THEN RAISE EXCEPTION 'CURRENT_EXCHANGE_GENERATION_PROJECTION_REQUIRED' USING ERRCODE='23514';END IF;
END $$;
CREATE FUNCTION verify_exchange_release(p_release uuid) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE released inventory_exchange_release;r record;expected numeric;n integer;
BEGIN
 SELECT * INTO released FROM inventory_exchange_release WHERE id=p_release;IF released.id IS NULL THEN RAISE EXCEPTION 'EXCHANGE_RELEASE_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;
 IF released.reason<>'EXPIRED' THEN RAISE EXCEPTION 'EXCHANGE_FINANCIAL_CONVERSION_NOT_BOUND' USING ERRCODE='23514';END IF;
 SELECT count(*) INTO n FROM inventory_exchange_reservation WHERE exchange_id=released.exchange_id AND sequence=released.sequence;
 IF n=0 OR n<>(SELECT count(*) FROM inventory_exchange_release_unit WHERE release_id=p_release) OR NOT EXISTS(SELECT 1 FROM claim_exchange_expiry_delivery delivery WHERE delivery.release_id=p_release AND delivery.exchange_id=released.exchange_id AND delivery.sequence=released.sequence AND delivery.mode='EXPIRED' AND delivery.recorded_at=released.released_at) THEN RAISE EXCEPTION 'EXACT_EXCHANGE_RELEASE_OPERATION_REQUIRED' USING ERRCODE='23514';END IF;
 FOR r IN SELECT * FROM inventory_exchange_reservation WHERE exchange_id=released.exchange_id AND sequence=released.sequence LOOP
  IF r.status<>'EXPIRED' OR released.released_at<COALESCE((SELECT extended_expires_at FROM inventory_exchange_extension_unit WHERE reservation_id=r.id),r.expires_at) OR NOT EXISTS(SELECT 1 FROM inventory_exchange_release_unit unit JOIN inventory_exchange_event event ON event.id=unit.event_id WHERE unit.release_id=p_release AND unit.exchange_id=released.exchange_id AND unit.sequence=released.sequence AND unit.reservation_id=r.id AND unit.cause='EXPIRE' AND event.reservation_id=r.id AND event.cause=unit.cause AND event.occurred_at=released.released_at AND event.after_reserved=event.before_reserved-r.quantity AND event.version=event.before_version+1) THEN RAISE EXCEPTION 'EXACT_EXPIRED_ALLOCATION_RELEASE_REQUIRED' USING ERRCODE='23514';END IF;
  SELECT COALESCE((SELECT sum(quantity) FROM inventory_reservation WHERE sku_id=r.target_sku_id AND status='RESERVED'),0)+COALESCE((SELECT sum(quantity) FROM inventory_exchange_reservation WHERE target_sku_id=r.target_sku_id AND status='RESERVED'),0) INTO expected;
  IF NOT EXISTS(SELECT 1 FROM inventory_balance WHERE sku_id=r.target_sku_id AND reserved=expected) THEN RAISE EXCEPTION 'EXCHANGE_RELEASE_BALANCE_AND_ACTIVE_RESERVATIONS_REQUIRED' USING ERRCODE='23514';END IF;
 END LOOP;
END $$;
CREATE FUNCTION verify_exchange_expiry_delivery(p_job uuid) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE delivery claim_exchange_expiry_delivery;schedule claim_exchange_reservation_schedule;c claim_exchange_request;
BEGIN
 SELECT * INTO delivery FROM claim_exchange_expiry_delivery WHERE job_id=p_job;SELECT * INTO schedule FROM claim_exchange_reservation_schedule WHERE job_id=p_job;SELECT * INTO c FROM claim_exchange_request WHERE id=delivery.exchange_id;PERFORM verify_exchange_reservation_schedule(delivery.exchange_id,delivery.sequence,delivery.epoch);
 IF delivery.job_id IS NULL OR c.id IS NULL THEN RAISE EXCEPTION 'EXACT_EXPIRY_DELIVERY_SOURCE_REQUIRED' USING ERRCODE='23514';END IF;
 IF delivery.mode='EXPIRED' THEN
  IF delivery.recorded_at<schedule.due_at OR NOT EXISTS(SELECT 1 FROM claim_exchange_command command JOIN claim_exchange_history history ON history.exchange_id=command.exchange_id AND history.version=command.version WHERE command.exchange_id=delivery.exchange_id AND command.command_key='reservation-expired:'||delivery.sequence::text||':'||delivery.epoch::text AND command.action='EXPIRED' AND command.version=delivery.workflow_revision AND command.receipt=delivery.receipt AND history.action='EXPIRED' AND history.actor='RECORDED_WORKER' AND history.occurred_at=delivery.recorded_at)
  OR NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='EXCHANGE_RECORDED.v1' AND j.dedupe_key='exchange-reservation-expired-'||delivery.sequence::text||':'||delivery.exchange_id::text AND j.cause_key=delivery.exchange_id::text AND j.payload=delivery.exchange_id::text)
  THEN RAISE EXCEPTION 'EXACT_EXPIRY_WORKFLOW_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;PERFORM verify_exchange_release(delivery.release_id);
 ELSIF delivery.mode='SUPERSEDED' THEN
  IF NOT(c.current_sequence>delivery.sequence OR EXISTS(SELECT 1 FROM claim_exchange_reservation_schedule newer WHERE newer.exchange_id=delivery.exchange_id AND newer.sequence=delivery.sequence AND newer.epoch>delivery.epoch)) THEN RAISE EXCEPTION 'EXACT_SUPERSEDED_DEADLINE_REQUIRED' USING ERRCODE='23514';END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM claim_exchange_dispatch_request request JOIN claim_exchange_dispatch_effect effect ON effect.dispatch_id=request.id WHERE request.exchange_id=delivery.exchange_id AND request.sequence=delivery.sequence) OR EXISTS(SELECT 1 FROM inventory_exchange_reservation r WHERE r.exchange_id=delivery.exchange_id AND r.sequence=delivery.sequence AND r.status<>'COMMITTED') THEN RAISE EXCEPTION 'ACTUAL_COMMITTED_GENERATION_REQUIRED' USING ERRCODE='23514';END IF;
 END IF;
END $$;
CREATE FUNCTION exchange_expiry_current_job() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM job j JOIN claim_exchange_reservation_schedule schedule ON schedule.job_id=j.id WHERE j.id=NEW.job_id AND j.type='EXCHANGE_RESERVATION_DUE.v1' AND j.status='RUNNING' AND j.owner_token=NEW.owner_token AND j.lease_until>clock_timestamp() AND(schedule.exchange_id,schedule.sequence,schedule.epoch)=(NEW.exchange_id,NEW.sequence,NEW.epoch)) THEN RAISE EXCEPTION 'EXCHANGE_EXPIRY_CURRENT_EXACT_JOB_REQUIRED' USING ERRCODE='23514';END IF;
 IF NEW.mode='EXPIRED' AND NOT EXISTS(SELECT 1 FROM claim_exchange_request c WHERE c.id=NEW.exchange_id AND c.current_sequence=NEW.sequence AND c.version=NEW.workflow_revision AND c.state IN('EXPIRED','REVIEW_REQUIRED') AND NEW.epoch=(SELECT max(epoch) FROM claim_exchange_reservation_schedule WHERE exchange_id=NEW.exchange_id AND sequence=NEW.sequence)) THEN RAISE EXCEPTION 'EXCHANGE_EXPIRY_CURRENT_GENERATION_REQUIRED' USING ERRCODE='23514';END IF;RETURN NEW;
END $$;
CREATE TRIGGER exchange_expiry_current_job_insert BEFORE INSERT ON claim_exchange_expiry_delivery FOR EACH ROW EXECUTE FUNCTION exchange_expiry_current_job();
CREATE FUNCTION exchange_generation_graph_guard() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE target uuid;seq integer;recorded record;released uuid;
BEGIN
 IF TG_TABLE_NAME='claim_exchange_request' THEN target=NEW.id;seq=NEW.current_sequence;IF seq IS DISTINCT FROM COALESCE((SELECT max(sequence) FROM claim_exchange_approval_generation WHERE exchange_id=target),0) OR(seq=0 AND NEW.approved_at IS NOT NULL) THEN RAISE EXCEPTION 'CURRENT_EXCHANGE_SEQUENCE_MUST_MATCH_OWNED_GENERATIONS' USING ERRCODE='23514';END IF;
 ELSIF TG_TABLE_NAME='inventory_exchange_event' THEN SELECT exchange_id,sequence INTO target,seq FROM inventory_exchange_reservation WHERE id=NEW.reservation_id;
 ELSE target=NEW.exchange_id;seq=NEW.sequence;END IF;
 IF seq>0 THEN PERFORM verify_exchange_approval_generation(target,seq);END IF;
 IF TG_TABLE_NAME='claim_exchange_expiry_delivery' THEN PERFORM verify_exchange_expiry_delivery(NEW.job_id);
 ELSIF TG_TABLE_NAME='inventory_exchange_release' THEN PERFORM verify_exchange_release(NEW.id);
 ELSIF TG_TABLE_NAME='inventory_exchange_release_unit' THEN PERFORM verify_exchange_release(NEW.release_id);
 ELSIF TG_TABLE_NAME='claim_exchange_reservation_schedule' THEN PERFORM verify_exchange_reservation_schedule(target,seq,NEW.epoch);
 ELSIF TG_TABLE_NAME='inventory_exchange_reservation' THEN
  SELECT release_id INTO released FROM inventory_exchange_release_unit WHERE reservation_id=NEW.id;IF released IS NOT NULL THEN PERFORM verify_exchange_release(released);ELSIF NEW.status IN('EXPIRED','RELEASED') THEN RAISE EXCEPTION 'TERMINAL_EXCHANGE_RESERVATION_RELEASE_PROOF_REQUIRED' USING ERRCODE='23514';END IF;
 ELSIF TG_TABLE_NAME='inventory_exchange_event' THEN
  IF NEW.cause IN('EXPIRE','RELEASE') THEN SELECT release_id INTO released FROM inventory_exchange_release_unit WHERE event_id=NEW.id;IF released IS NULL THEN RAISE EXCEPTION 'EXCHANGE_RELEASE_EVENT_REQUIRES_RECEIPT' USING ERRCODE='23514';END IF;PERFORM verify_exchange_release(released);END IF;
 END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER exchange_generation_workflow_graph AFTER INSERT OR UPDATE ON claim_exchange_request DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_generation_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_generation_claim_graph AFTER INSERT ON claim_exchange_approval_generation DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_generation_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_generation_inventory_graph AFTER INSERT ON inventory_exchange_approval DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_generation_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_generation_reservation_graph AFTER INSERT OR UPDATE ON inventory_exchange_reservation DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_generation_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_generation_event_graph AFTER INSERT ON inventory_exchange_event DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_generation_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_generation_due_graph AFTER INSERT ON claim_exchange_reservation_schedule DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_generation_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_generation_release_graph AFTER INSERT ON inventory_exchange_release DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_generation_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_generation_release_unit_graph AFTER INSERT ON inventory_exchange_release_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_generation_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_generation_delivery_graph AFTER INSERT ON claim_exchange_expiry_delivery DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_generation_graph_guard();
CREATE OR REPLACE FUNCTION verify_exchange_extension(p_id uuid) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE c claim_exchange_request;e claim_exchange_extension;n integer;
BEGIN
 SELECT * INTO c FROM claim_exchange_request WHERE id=p_id;SELECT * INTO e FROM claim_exchange_extension WHERE exchange_id=p_id;
 IF e.id IS NULL THEN IF EXISTS(SELECT 1 FROM inventory_exchange_extension WHERE exchange_id=p_id) OR EXISTS(SELECT 1 FROM inventory_exchange_extension_unit WHERE exchange_id=p_id)  THEN RAISE EXCEPTION 'EXCHANGE_EXTENSION_COMMAND_REQUIRED' USING ERRCODE='23514';END IF;RETURN;END IF;
 SELECT count(*) INTO n FROM claim_exchange_unit WHERE exchange_id=p_id;
 IF c.id IS NULL OR c.version<e.revision OR(c.current_sequence=e.sequence AND c.expires_at IS DISTINCT FROM e.extended_expires_at) OR NOT EXISTS(SELECT 1 FROM claim_exchange_approval_generation g WHERE g.exchange_id=p_id AND g.sequence=e.sequence AND g.expires_at=e.previous_expires_at)
 OR n=0 OR n<>(SELECT count(*) FROM inventory_exchange_extension_unit WHERE extension_id=e.id)
 OR NOT EXISTS(SELECT 1 FROM inventory_exchange_extension x WHERE x.id=e.id AND(x.exchange_id,x.sequence,x.previous_expires_at,x.extended_expires_at,x.extended_at)=(e.exchange_id,e.sequence,e.previous_expires_at,e.extended_expires_at,e.extended_at))
 OR NOT EXISTS(SELECT 1 FROM claim_exchange_history h WHERE h.exchange_id=p_id AND h.version=e.original_revision AND h.action=e.prior_state)
 OR NOT EXISTS(SELECT 1 FROM claim_exchange_history h WHERE h.exchange_id=p_id AND h.version=e.revision AND h.action='EXTENDED' AND h.actor='MEMBER:'||e.actor_id::text AND h.reason=e.reason AND h.occurred_at=e.extended_at)
 OR NOT EXISTS(SELECT 1 FROM claim_exchange_command command WHERE command.exchange_id=p_id AND command.command_key=e.command_key AND command.fingerprint=e.fingerprint AND command.action='EXTENDED' AND command.version=e.revision AND command.receipt=e.receipt)
 OR e.receipt->>'state' IS DISTINCT FROM e.prior_state OR(e.receipt->>'version')::bigint IS DISTINCT FROM e.revision OR e.receipt->'accepted'->>'exchangeId' IS DISTINCT FROM p_id::text
 OR NOT EXISTS(SELECT 1 FROM outbox_event outbox JOIN job work ON work.id=outbox.job_id WHERE work.type='EXCHANGE_RECORDED.v1' AND work.dedupe_key='exchange-reservation-extended:'||p_id::text AND work.cause_key=p_id::text AND work.payload=p_id::text)
 OR EXISTS(SELECT 1 FROM claim_exchange_unit u WHERE u.exchange_id=p_id AND NOT EXISTS(SELECT 1 FROM inventory_exchange_extension_unit x JOIN inventory_exchange_reservation r ON r.id=x.reservation_id WHERE x.extension_id=e.id AND r.exchange_id=p_id AND r.sequence=e.sequence AND r.physical_id=u.physical_id AND r.item_id=u.item_id AND r.ordinal=u.ordinal AND r.target_sku_id=u.target_sku_id AND r.expires_at=e.previous_expires_at AND e.extended_at>=r.approved_at AND EXISTS(SELECT 1 FROM jsonb_array_elements(e.receipt->'reservations') saved WHERE saved->>'id'=r.id::text AND saved->>'status'='RESERVED' AND(saved->>'expiresAt')::timestamptz=e.extended_expires_at)))
 THEN RAISE EXCEPTION 'EXACT_ONE_REASONED_EXCHANGE_EXTENSION_GRAPH_REQUIRED' USING ERRCODE='23514';END IF;
 PERFORM verify_exchange_reservation_schedule(p_id,e.sequence,2);
END $$;
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
  IF n<>(SELECT count(*) FROM inventory_exchange_reservation WHERE exchange_id=target AND sequence=c.current_sequence AND status='RESERVED') OR NOT EXISTS(SELECT 1 FROM claim_exchange_history WHERE exchange_id=target AND version=1 AND action='APPROVED') OR NOT EXISTS(SELECT 1 FROM claim_exchange_command WHERE exchange_id=target AND version=1 AND action='APPROVED')
  OR NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='EXCHANGE_RECORDED.v1' AND j.dedupe_key='exchange-approved:'||target::text AND j.cause_key=target::text AND j.payload=target::text)
  OR EXISTS(SELECT 1 FROM inventory_exchange_reservation r JOIN claim_exchange_unit u ON u.exchange_id=r.exchange_id AND u.physical_id=r.physical_id JOIN order_physical_unit p ON p.id=u.physical_id WHERE r.exchange_id=target AND r.sequence=c.current_sequence AND (r.order_id<>a.order_id OR r.seller_order_id<>a.seller_order_id OR r.checkout_id<>p.checkout_id OR r.item_id<>p.item_id OR r.ordinal<>p.ordinal OR r.source_reservation_id<>p.reservation_id OR r.source_sku_id<>p.sku_id OR r.target_sku_id<>u.target_sku_id OR r.approved_at<>c.approved_at OR COALESCE((SELECT extended_expires_at FROM inventory_exchange_extension_unit WHERE reservation_id=r.id),r.expires_at)<>c.expires_at OR NOT EXISTS(SELECT 1 FROM inventory_exchange_event e WHERE e.reservation_id=r.id AND e.cause='RESERVE' AND e.occurred_at=c.approved_at)))
  THEN RAISE EXCEPTION 'EXCHANGE_APPROVAL_RESERVATION_GRAPH' USING ERRCODE='23514';END IF;
 END IF;RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION exchange_reship_graph() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE v_id uuid;c claim_exchange_request;r claim_exchange_dispatch_request;h fulfillment_exchange_shipment;e claim_exchange_dispatch_effect;n integer;
BEGIN
 IF TG_TABLE_NAME='claim_exchange_request' THEN v_id=NEW.id;
 ELSIF TG_TABLE_NAME='inventory_exchange_commit' THEN SELECT exchange_id INTO v_id FROM inventory_exchange_reservation WHERE id=NEW.reservation_id;
 ELSE v_id=NEW.exchange_id;END IF;
 SELECT * INTO c FROM claim_exchange_request WHERE id=v_id;SELECT * INTO r FROM claim_exchange_dispatch_request WHERE exchange_id=v_id AND sequence=c.current_sequence;
 IF r.id IS NULL THEN
  IF c.state IN('DISPATCH_PENDING','DISPATCHED') THEN RAISE EXCEPTION 'ACTUAL_EXCHANGE_DISPATCH_REQUEST_REQUIRED' USING ERRCODE='23514';END IF;RETURN NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM claim_exchange_command z JOIN claim_exchange_history t ON t.exchange_id=z.exchange_id AND t.version=z.version WHERE z.exchange_id=v_id AND z.command_key=r.command_key AND z.action='DISPATCH_PENDING' AND z.version=r.version AND t.action=z.action)
 OR NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='EXCHANGE_RESHIP.v1' AND j.dedupe_key='exchange-reship:'||r.id::text AND j.cause_key=v_id::text AND j.payload=v_id::text||'|'||r.id::text)
 OR EXISTS(SELECT 1 FROM claim_exchange_unit u WHERE u.exchange_id=v_id AND NOT EXISTS(SELECT 1 FROM claim_exchange_collection x JOIN claim_exchange_inspection_unit i ON i.collection_id=x.id AND i.exchange_id=x.exchange_id AND i.item_id=x.item_id AND i.ordinal=x.ordinal AND i.physical_id=x.physical_id WHERE x.exchange_id=v_id AND x.item_id=u.item_id AND x.ordinal=u.ordinal AND x.status='RECEIVED'))
 THEN RAISE EXCEPTION 'EXACT_INSPECTED_EXCHANGE_DISPATCH_COMMAND_REQUIRED' USING ERRCODE='23514';END IF;
 SELECT * INTO h FROM fulfillment_exchange_shipment WHERE exchange_id=v_id;SELECT * INTO e FROM claim_exchange_dispatch_effect WHERE exchange_id=v_id;
 IF h.id IS NULL THEN
  IF EXISTS(SELECT 1 FROM inventory_exchange_release released JOIN claim_exchange_expiry_delivery delivery ON delivery.release_id=released.id WHERE released.exchange_id=v_id AND released.sequence=c.current_sequence AND released.reason='EXPIRED' AND delivery.mode='EXPIRED') AND c.state IN('EXPIRED','REVIEW_REQUIRED') THEN RETURN NULL;END IF;
  IF c.state<>'DISPATCH_PENDING' OR e.exchange_id IS NOT NULL OR EXISTS(SELECT 1 FROM inventory_exchange_reservation WHERE exchange_id=v_id AND sequence=c.current_sequence AND status<>'RESERVED') THEN RAISE EXCEPTION 'PENDING_EXCHANGE_CANNOT_COMMIT_STOCK' USING ERRCODE='23514';END IF;RETURN NULL;
 END IF;
 SELECT count(*) INTO n FROM claim_exchange_unit WHERE exchange_id=v_id;
 IF e.exchange_id IS NULL OR e.version<>(r.version+1+CASE WHEN EXISTS(SELECT 1 FROM claim_exchange_extension x WHERE x.exchange_id=v_id AND x.prior_state='DISPATCH_PENDING' AND x.original_revision=r.version AND x.revision=r.version+1) THEN 1 ELSE 0 END) OR e.dispatch_id<>r.id OR e.shipment_id<>h.id OR h.dispatch_id<>r.id OR h.order_id<>c.order_id OR h.seller_order_id<>c.seller_order_id
 OR c.state NOT IN('DISPATCHED','COMPLETED') OR n<>(SELECT count(*) FROM fulfillment_exchange_allocation WHERE exchange_id=v_id)
 OR n<>(SELECT count(*) FROM inventory_exchange_reservation WHERE exchange_id=v_id AND sequence=c.current_sequence AND status='COMMITTED')
 OR NOT EXISTS(SELECT 1 FROM job j WHERE j.id=e.job_id AND j.type='EXCHANGE_RESHIP.v1' AND j.dedupe_key='exchange-reship:'||r.id::text AND j.cause_key=v_id::text AND j.payload=v_id::text||'|'||r.id::text)
 OR NOT EXISTS(SELECT 1 FROM order_exchange_dispatch d JOIN fulfillment_exchange_event f ON f.id=d.event_id WHERE d.exchange_id=v_id AND d.shipment_id=h.id AND d.dispatched_at=h.shipped_at AND f.shipment_id=h.id AND f.state='SHIPPED' AND f.occurred_at=h.shipped_at)
 OR EXISTS(SELECT 1 FROM inventory_exchange_reservation x LEFT JOIN inventory_exchange_commit z ON z.reservation_id=x.id LEFT JOIN inventory_exchange_source source ON source.reservation_id=x.id LEFT JOIN fulfillment_exchange_allocation a ON a.reservation_id=x.id WHERE x.exchange_id=v_id AND x.sequence=c.current_sequence AND (z.reservation_id IS NULL OR z.dispatch_id<>r.id OR z.committed_at>=COALESCE((SELECT extended_expires_at FROM inventory_exchange_extension_unit WHERE reservation_id=x.id),x.expires_at) OR source.source_id IS NULL OR a.exchange_id IS DISTINCT FROM v_id OR a.source_id IS DISTINCT FROM source.source_id OR a.item_id<>x.item_id OR a.ordinal<>x.ordinal OR NOT EXISTS(SELECT 1 FROM order_physical_source p WHERE p.id=a.physical_id AND p.predecessor_id=a.predecessor_id AND p.committed_source_id=a.source_id)))
 THEN RAISE EXCEPTION 'EXACT_COMMITTED_EXCHANGE_SHIPMENT_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;

-- Existing genuine allocations must satisfy the new generation bridge before the migration commits.
DO $$ DECLARE g record;BEGIN FOR g IN SELECT exchange_id,sequence FROM claim_exchange_approval_generation LOOP PERFORM verify_exchange_approval_generation(g.exchange_id,g.sequence);PERFORM verify_exchange_extension(g.exchange_id);END LOOP;END $$;
