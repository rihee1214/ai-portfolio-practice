ALTER TABLE claim_return_command DROP CONSTRAINT claim_return_command_action_check;
ALTER TABLE claim_return_command ADD CONSTRAINT claim_return_command_action_check CHECK(action IN ('APPROVED','REJECTED','WITHDRAWN','COLLECTING','REVIEW_REQUIRED'));
CREATE TABLE claim_return_collection (
 id uuid PRIMARY KEY,return_id uuid NOT NULL,item_id uuid NOT NULL,ordinal integer NOT NULL,
 status varchar(20) NOT NULL CHECK(status IN ('RECEIVED','LOST_REPORTED')),version bigint NOT NULL CHECK(version>1),
 occurred_at timestamptz NOT NULL,UNIQUE(return_id,item_id,ordinal,version),
 FOREIGN KEY(return_id,item_id,ordinal) REFERENCES claim_return_unit(return_id,item_id,ordinal),
 FOREIGN KEY(return_id,version) REFERENCES claim_return_command(return_id,version) DEFERRABLE INITIALLY DEFERRED
);
CREATE TRIGGER return_collection_immutable BEFORE UPDATE OR DELETE ON claim_return_collection FOR EACH ROW EXECUTE FUNCTION return_application_immutable();
CREATE FUNCTION return_collection_graph_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_id uuid;v_request marketplace.claim_return_request%ROWTYPE;v_command marketplace.claim_return_command%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='claim_return_request' THEN v_id:=NEW.id;
 ELSIF TG_TABLE_NAME='payment_refund_attempt' THEN v_id:=NEW.claim_id;
 ELSIF TG_TABLE_NAME='claim_fee_assessment' THEN v_id:=NEW.intent_id;
 ELSE v_id:=NEW.return_id;END IF;
 SELECT * INTO v_request FROM marketplace.claim_return_request WHERE id=v_id;
 IF NOT FOUND THEN RETURN NULL;END IF;
 IF v_request.state IN ('REQUESTED','APPROVED','COLLECTING','REVIEW_REQUIRED') AND (
  EXISTS(SELECT 1 FROM marketplace.payment_refund_attempt a WHERE a.claim_id=v_id)
  OR EXISTS(SELECT 1 FROM marketplace.claim_fee_assessment a WHERE a.intent_id=v_id)
 ) THEN RAISE EXCEPTION 'RETURN_COLLECTION_IS_NOT_FINANCIAL_CONSENT' USING ERRCODE='23514';END IF;
 IF v_request.state NOT IN ('COLLECTING','REVIEW_REQUIRED') THEN
  IF EXISTS(SELECT 1 FROM marketplace.claim_return_collection c WHERE c.return_id=v_id) THEN RAISE EXCEPTION 'RETURN_COLLECTION_STATE_REQUIRED' USING ERRCODE='23514';END IF;
  RETURN NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM marketplace.claim_return_collection c WHERE c.return_id=v_id)
  OR (v_request.state='REVIEW_REQUIRED') IS DISTINCT FROM EXISTS(
   SELECT 1 FROM marketplace.claim_return_unit u JOIN LATERAL(SELECT c.status FROM marketplace.claim_return_collection c
    WHERE c.return_id=u.return_id AND c.item_id=u.item_id AND c.ordinal=u.ordinal ORDER BY c.version DESC LIMIT 1)c ON true
   WHERE u.return_id=v_id AND c.status='LOST_REPORTED') THEN RAISE EXCEPTION 'RETURN_COLLECTION_CURRENT_FACT_REQUIRED' USING ERRCODE='23514';END IF;
 IF EXISTS(SELECT 1 FROM marketplace.claim_return_collection c
  LEFT JOIN marketplace.claim_return_command x ON x.return_id=c.return_id AND x.version=c.version
  WHERE c.return_id=v_id AND (x.action NOT IN ('COLLECTING','REVIEW_REQUIRED') OR x.return_id IS NULL)) THEN
  RAISE EXCEPTION 'RETURN_COLLECTION_COMMAND_REQUIRED' USING ERRCODE='23514';END IF;
 FOR v_command IN SELECT * FROM marketplace.claim_return_command WHERE return_id=v_id AND action IN ('COLLECTING','REVIEW_REQUIRED') LOOP
  IF jsonb_typeof(v_command.receipt->'physical') IS DISTINCT FROM 'array'
   OR jsonb_array_length(v_command.receipt->'physical')<>(SELECT count(*) FROM marketplace.claim_return_unit WHERE return_id=v_id)
   OR (SELECT count(DISTINCT(j.value->>'itemId',j.value->>'ordinal')) FROM jsonb_array_elements(v_command.receipt->'physical')j(value))<>(SELECT count(*) FROM marketplace.claim_return_unit WHERE return_id=v_id)
   OR EXISTS(SELECT 1 FROM jsonb_array_elements(v_command.receipt->'physical')j(value)
    LEFT JOIN marketplace.claim_return_unit u ON u.return_id=v_id AND u.item_id::text=j.value->>'itemId' AND u.ordinal::text=j.value->>'ordinal'
    LEFT JOIN LATERAL(SELECT c.id,c.status FROM marketplace.claim_return_collection c WHERE c.return_id=u.return_id AND c.item_id=u.item_id AND c.ordinal=u.ordinal AND c.version<=v_command.version ORDER BY c.version DESC LIMIT 1)c ON true
    WHERE u.item_id IS NULL OR j.value->>'collection' IS DISTINCT FROM COALESCE(c.status,'PENDING')
     OR j.value->>'collectionId' IS DISTINCT FROM c.id::text OR j.value->>'inspection' IS DISTINCT FROM 'NOT_INSPECTED'
     OR j.value->>'inspectionId' IS NOT NULL OR j.value->>'restitutionId' IS NOT NULL OR j.value->>'responsibility' IS NOT NULL
   ) THEN RAISE EXCEPTION 'RETURN_COLLECTION_RECEIPT_FACT_MISMATCH' USING ERRCODE='23514';END IF;
 END LOOP;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER return_collection_state AFTER INSERT OR UPDATE ON claim_return_request DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_collection_graph_guard();
CREATE CONSTRAINT TRIGGER return_collection_fact AFTER INSERT ON claim_return_collection DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_collection_graph_guard();
CREATE CONSTRAINT TRIGGER return_collection_receipt AFTER INSERT ON claim_return_command DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_collection_graph_guard();
CREATE CONSTRAINT TRIGGER return_collection_payment AFTER INSERT OR UPDATE ON payment_refund_attempt DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_collection_graph_guard();
CREATE CONSTRAINT TRIGGER return_collection_fee AFTER INSERT OR UPDATE ON claim_fee_assessment DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_collection_graph_guard();
