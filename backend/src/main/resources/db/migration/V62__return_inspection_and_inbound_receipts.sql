ALTER TABLE claim_return_command DROP CONSTRAINT claim_return_command_action_check;
ALTER TABLE claim_return_command ADD CONSTRAINT claim_return_command_action_check CHECK(action IN ('APPROVED','REJECTED','WITHDRAWN','COLLECTING','REVIEW_REQUIRED','AWAITING_CONSENT'));
ALTER TABLE claim_return_request ADD CONSTRAINT return_request_physical_identity UNIQUE(id,order_id,seller_order_id);
ALTER TABLE claim_return_collection ADD CONSTRAINT return_received_source_identity UNIQUE(id,return_id,item_id,ordinal,status);
CREATE TABLE claim_return_inspection (
 id uuid PRIMARY KEY,return_id uuid NOT NULL,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,checkout_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>1),reason varchar(500) NOT NULL CHECK(length(btrim(reason))>0),actor varchar(100) NOT NULL,occurred_at timestamptz NOT NULL,
 UNIQUE(id,return_id),UNIQUE(id,return_id,checkout_id),UNIQUE(return_id,version),
 FOREIGN KEY(return_id,order_id,seller_order_id) REFERENCES claim_return_request(id,order_id,seller_order_id),
 FOREIGN KEY(return_id,version) REFERENCES claim_return_command(return_id,version) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE claim_return_inspection_unit (
 inspection_id uuid NOT NULL,return_id uuid NOT NULL,item_id uuid NOT NULL,ordinal integer NOT NULL,
 collection_id uuid NOT NULL,collection_status varchar(20) NOT NULL DEFAULT 'RECEIVED' CHECK(collection_status='RECEIVED'),
 condition varchar(20) NOT NULL CHECK(condition IN ('SALEABLE','DEFECTIVE')),responsibility varchar(10) NOT NULL CHECK(responsibility IN ('BUYER','SELLER')),
 restitution_id uuid,CHECK((condition='SALEABLE' AND restitution_id IS NOT NULL AND restitution_id=inspection_id) OR(condition='DEFECTIVE' AND restitution_id IS NULL)),
 PRIMARY KEY(inspection_id,item_id,ordinal),UNIQUE(return_id,item_id,ordinal),
 FOREIGN KEY(inspection_id,return_id) REFERENCES claim_return_inspection(id,return_id),
 FOREIGN KEY(return_id,item_id,ordinal) REFERENCES claim_return_unit(return_id,item_id,ordinal),
 FOREIGN KEY(collection_id,return_id,item_id,ordinal,collection_status) REFERENCES claim_return_collection(id,return_id,item_id,ordinal,status)
);
CREATE TRIGGER return_inspection_immutable BEFORE UPDATE OR DELETE ON claim_return_inspection FOR EACH ROW EXECUTE FUNCTION return_application_immutable();
CREATE TRIGGER return_inspection_unit_immutable BEFORE UPDATE OR DELETE ON claim_return_inspection_unit FOR EACH ROW EXECUTE FUNCTION return_application_immutable();

-- Original cancellation fingerprints, units, movements and COMMITTED reservations remain unchanged.
ALTER TABLE inventory_restitution ADD COLUMN kind varchar(10) NOT NULL DEFAULT 'CANCEL' CHECK(kind IN ('CANCEL','RETURN'));
ALTER TABLE inventory_restitution ADD CONSTRAINT inventory_restitution_kind_identity UNIQUE(cause_id,checkout_id,kind);
CREATE TABLE inventory_return_restitution (
 inspection_id uuid PRIMARY KEY,return_id uuid NOT NULL,checkout_id uuid NOT NULL,cause_id uuid NOT NULL UNIQUE,
 kind varchar(10) NOT NULL DEFAULT 'RETURN' CHECK(kind='RETURN'),CHECK(cause_id=inspection_id),
 FOREIGN KEY(cause_id,checkout_id,kind) REFERENCES inventory_restitution(cause_id,checkout_id,kind) DEFERRABLE INITIALLY DEFERRED
);
CREATE FUNCTION inventory_return_receipt_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'IMMUTABLE_RETURN_INBOUND_RECEIPT' USING ERRCODE='23514';END $$;
CREATE TRIGGER inventory_return_receipt_immutable BEFORE UPDATE OR DELETE ON inventory_return_restitution FOR EACH ROW EXECUTE FUNCTION inventory_return_receipt_immutable();
CREATE FUNCTION inventory_restitution_kind_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.kind IS DISTINCT FROM NEW.kind THEN RAISE EXCEPTION 'IMMUTABLE_RESTITUTION_KIND' USING ERRCODE='23514';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER inventory_restitution_kind_immutable BEFORE UPDATE ON inventory_restitution FOR EACH ROW EXECUTE FUNCTION inventory_restitution_kind_immutable();

-- Extend the existing collection projection to genuine received-unit inspection facts.
-- Irreversible inventory/inspection graph completeness is verified by the following TDD increment.
CREATE OR REPLACE FUNCTION return_collection_graph_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_id uuid;v_request marketplace.claim_return_request%ROWTYPE;v_command marketplace.claim_return_command%ROWTYPE;
 v_lost boolean;v_ready boolean;
BEGIN
 IF TG_TABLE_NAME='claim_return_request' THEN v_id:=NEW.id;
 ELSIF TG_TABLE_NAME='payment_refund_attempt' THEN v_id:=NEW.claim_id;
 ELSIF TG_TABLE_NAME='claim_fee_assessment' THEN v_id:=NEW.intent_id;
 ELSE v_id:=NEW.return_id;END IF;
 SELECT * INTO v_request FROM marketplace.claim_return_request WHERE id=v_id;
 IF NOT FOUND THEN RETURN NULL;END IF;
 IF v_request.state IN ('REQUESTED','APPROVED','COLLECTING','REVIEW_REQUIRED','AWAITING_CONSENT') AND (
  EXISTS(SELECT 1 FROM marketplace.payment_refund_attempt a WHERE a.claim_id=v_id)
  OR EXISTS(SELECT 1 FROM marketplace.claim_fee_assessment a WHERE a.intent_id=v_id)
 ) THEN RAISE EXCEPTION 'RETURN_PHYSICAL_FACT_IS_NOT_FINANCIAL_CONSENT' USING ERRCODE='23514';END IF;
 IF v_request.state NOT IN ('COLLECTING','REVIEW_REQUIRED','AWAITING_CONSENT') THEN
  IF EXISTS(SELECT 1 FROM marketplace.claim_return_collection c WHERE c.return_id=v_id)
   OR EXISTS(SELECT 1 FROM marketplace.claim_return_inspection i WHERE i.return_id=v_id) THEN RAISE EXCEPTION 'RETURN_PHYSICAL_STATE_REQUIRED' USING ERRCODE='23514';END IF;
  RETURN NULL;
 END IF;
 SELECT COALESCE(bool_or(COALESCE(c.status,'PENDING')='LOST_REPORTED'),false),
  COALESCE(bool_and(COALESCE(c.status,'PENDING')='RECEIVED' AND i.inspection_id IS NOT NULL),false)
 INTO v_lost,v_ready
 FROM marketplace.claim_return_unit u
 LEFT JOIN LATERAL(SELECT c.status FROM marketplace.claim_return_collection c WHERE c.return_id=u.return_id AND c.item_id=u.item_id AND c.ordinal=u.ordinal ORDER BY c.version DESC LIMIT 1)c ON true
 LEFT JOIN marketplace.claim_return_inspection_unit i ON i.return_id=u.return_id AND i.item_id=u.item_id AND i.ordinal=u.ordinal
 WHERE u.return_id=v_id;
 IF NOT EXISTS(SELECT 1 FROM marketplace.claim_return_collection c WHERE c.return_id=v_id)
  OR v_request.state IS DISTINCT FROM (CASE WHEN v_lost THEN 'REVIEW_REQUIRED' WHEN v_ready THEN 'AWAITING_CONSENT' ELSE 'COLLECTING' END) THEN
  RAISE EXCEPTION 'RETURN_CURRENT_PHYSICAL_FACT_REQUIRED' USING ERRCODE='23514';END IF;
 IF EXISTS(SELECT 1 FROM marketplace.claim_return_collection c LEFT JOIN marketplace.claim_return_command x ON x.return_id=c.return_id AND x.version=c.version
  WHERE c.return_id=v_id AND(x.action NOT IN ('COLLECTING','REVIEW_REQUIRED') OR x.return_id IS NULL)) THEN RAISE EXCEPTION 'RETURN_COLLECTION_COMMAND_REQUIRED' USING ERRCODE='23514';END IF;
 IF EXISTS(SELECT 1 FROM marketplace.claim_return_inspection i LEFT JOIN marketplace.claim_return_command x ON x.return_id=i.return_id AND x.version=i.version
  WHERE i.return_id=v_id AND(x.action NOT IN ('COLLECTING','REVIEW_REQUIRED','AWAITING_CONSENT') OR x.return_id IS NULL)) THEN RAISE EXCEPTION 'RETURN_INSPECTION_COMMAND_REQUIRED' USING ERRCODE='23514';END IF;
 FOR v_command IN SELECT * FROM marketplace.claim_return_command WHERE return_id=v_id AND action IN ('COLLECTING','REVIEW_REQUIRED','AWAITING_CONSENT') LOOP
  IF jsonb_typeof(v_command.receipt->'physical') IS DISTINCT FROM 'array'
   OR jsonb_array_length(v_command.receipt->'physical')<>(SELECT count(*) FROM marketplace.claim_return_unit WHERE return_id=v_id)
   OR (SELECT count(DISTINCT(j.value->>'itemId',j.value->>'ordinal')) FROM jsonb_array_elements(v_command.receipt->'physical')j(value))<>(SELECT count(*) FROM marketplace.claim_return_unit WHERE return_id=v_id)
   OR EXISTS(SELECT 1 FROM jsonb_array_elements(v_command.receipt->'physical')j(value)
    LEFT JOIN marketplace.claim_return_unit u ON u.return_id=v_id AND u.item_id::text=j.value->>'itemId' AND u.ordinal::text=j.value->>'ordinal'
    LEFT JOIN LATERAL(SELECT c.id,c.status FROM marketplace.claim_return_collection c WHERE c.return_id=u.return_id AND c.item_id=u.item_id AND c.ordinal=u.ordinal AND c.version<=v_command.version ORDER BY c.version DESC LIMIT 1)c ON true
    LEFT JOIN LATERAL(SELECT x.inspection_id,x.condition,x.responsibility,x.restitution_id FROM marketplace.claim_return_inspection_unit x JOIN marketplace.claim_return_inspection h ON h.id=x.inspection_id WHERE x.return_id=u.return_id AND x.item_id=u.item_id AND x.ordinal=u.ordinal AND h.version<=v_command.version)i ON true
    WHERE u.item_id IS NULL OR j.value->>'collection' IS DISTINCT FROM COALESCE(c.status,'PENDING')
     OR j.value->>'collectionId' IS DISTINCT FROM c.id::text OR j.value->>'inspection' IS DISTINCT FROM COALESCE(i.condition,'NOT_INSPECTED')
     OR j.value->>'inspectionId' IS DISTINCT FROM i.inspection_id::text OR j.value->>'restitutionId' IS DISTINCT FROM i.restitution_id::text OR j.value->>'responsibility' IS DISTINCT FROM i.responsibility
   ) THEN RAISE EXCEPTION 'RETURN_PHYSICAL_RECEIPT_FACT_MISMATCH' USING ERRCODE='23514';END IF;
 END LOOP;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER return_inspection_state AFTER INSERT ON claim_return_inspection DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_collection_graph_guard();
CREATE CONSTRAINT TRIGGER return_inspection_unit_state AFTER INSERT ON claim_return_inspection_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_collection_graph_guard();
