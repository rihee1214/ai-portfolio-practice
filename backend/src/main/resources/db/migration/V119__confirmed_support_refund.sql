SET search_path TO marketplace;

ALTER TABLE operations_support_case ADD UNIQUE(id,order_number,seller_order_id);
ALTER TABLE purchase_order ADD UNIQUE(id,order_number);

CREATE TABLE claim_support_collection (
 id uuid PRIMARY KEY, case_id uuid NOT NULL,order_number text NOT NULL,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,
 item_id uuid NOT NULL,ordinal integer NOT NULL,confirmation_id uuid NOT NULL,collection_reference varchar(200) NOT NULL CHECK(btrim(collection_reference)<>''),
 actor_id uuid NOT NULL REFERENCES identity_user(id),occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(case_id,order_number,seller_order_id) REFERENCES operations_support_case(id,order_number,seller_order_id),
 FOREIGN KEY(order_id,order_number) REFERENCES purchase_order(id,order_number),
 FOREIGN KEY(item_id,seller_order_id,order_id) REFERENCES order_item(id,seller_order_id,order_id),
 FOREIGN KEY(confirmation_id,item_id,ordinal,order_id) REFERENCES order_confirmation_unit(confirmation_id,item_id,ordinal,order_id),
 UNIQUE(item_id,ordinal),UNIQUE(case_id,collection_reference,item_id,ordinal),
 UNIQUE(id,order_id,seller_order_id,item_id,ordinal,confirmation_id)
);
CREATE TABLE claim_support_inspection (
 id uuid PRIMARY KEY,collection_id uuid NOT NULL UNIQUE REFERENCES claim_support_collection(id),
 inspection_reference varchar(200) NOT NULL CHECK(btrim(inspection_reference)<>''),
 responsibility text NOT NULL CHECK(responsibility IN ('BUYER','SELLER')),
 defect_evidence varchar(1000) NOT NULL CHECK(btrim(defect_evidence)<>''),
 actor_id uuid NOT NULL REFERENCES identity_user(id),occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(id,collection_id,responsibility)
);
CREATE TABLE claim_support_refund_approval (
 id uuid PRIMARY KEY,inspection_id uuid NOT NULL UNIQUE,collection_id uuid NOT NULL,
 responsibility text NOT NULL DEFAULT 'SELLER' CHECK(responsibility='SELLER'),
 order_id uuid NOT NULL,seller_order_id uuid NOT NULL,item_id uuid NOT NULL,ordinal integer NOT NULL,
 confirmation_id uuid NOT NULL,net bigint NOT NULL CHECK(net>=0),policy_version text NOT NULL,
 reason varchar(500) NOT NULL CHECK(btrim(reason)<>''),actor_id uuid NOT NULL REFERENCES identity_user(id),
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(inspection_id,collection_id,responsibility) REFERENCES claim_support_inspection(id,collection_id,responsibility),
 FOREIGN KEY(collection_id,order_id,seller_order_id,item_id,ordinal,confirmation_id)
 REFERENCES claim_support_collection(id,order_id,seller_order_id,item_id,ordinal,confirmation_id),
 UNIQUE(id,order_id,seller_order_id), UNIQUE(id,item_id,ordinal)
);
CREATE FUNCTION verify_support_approval_quote() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM order_unit u JOIN order_item i ON i.id=u.item_id JOIN purchase_order o ON o.id=i.order_id
 WHERE u.item_id=NEW.item_id AND u.ordinal=NEW.ordinal AND i.seller_order_id=NEW.seller_order_id AND o.id=NEW.order_id
 AND u.net=NEW.net AND o.policy_version=NEW.policy_version) THEN
 RAISE EXCEPTION 'SUPPORT_ORIGINAL_UNIT_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER support_approval_quote BEFORE INSERT ON claim_support_refund_approval FOR EACH ROW EXECUTE FUNCTION verify_support_approval_quote();

-- Existing financial origins still require their original physical hold. SUPPORT instead requires the
-- matching immutable confirmation, collection, SELLER inspection and operator approval.
ALTER TABLE claim_financial_intent ALTER COLUMN hold_id DROP NOT NULL;
ALTER TABLE claim_financial_intent DROP CONSTRAINT claim_financial_intent_kind_check;
ALTER TABLE claim_financial_intent ADD CHECK(kind IN ('CANCEL','RETURN','EXCHANGE_REFUND','SUPPORT'));
ALTER TABLE claim_financial_intent ADD CHECK((kind='SUPPORT')=(hold_id IS NULL));
ALTER TABLE claim_financial_intent ADD COLUMN support_id uuid GENERATED ALWAYS AS (CASE WHEN kind='SUPPORT' THEN id END) STORED;
ALTER TABLE claim_financial_intent ADD FOREIGN KEY(support_id,order_id,seller_order_id) REFERENCES claim_support_refund_approval(id,order_id,seller_order_id);
ALTER TABLE claim_financial_intent_unit ALTER COLUMN hold_id DROP NOT NULL;
ALTER TABLE claim_financial_intent_unit ADD COLUMN support_id uuid GENERATED ALWAYS AS (CASE WHEN hold_id IS NULL THEN intent_id END) STORED;
ALTER TABLE claim_financial_intent_unit ADD FOREIGN KEY(support_id,item_id,ordinal) REFERENCES claim_support_refund_approval(id,item_id,ordinal);
ALTER TABLE claim_financial_intent_unit ADD FOREIGN KEY(intent_id,seller_order_id,order_id) REFERENCES claim_financial_intent(id,seller_order_id,order_id);
ALTER TABLE claim_financial_refund_binding DROP CONSTRAINT financial_binding_kind_purpose;
ALTER TABLE claim_financial_refund_binding ADD CONSTRAINT financial_binding_kind_purpose CHECK(intent_kind IN('CANCEL','RETURN','EXCHANGE_REFUND','SUPPORT'));
ALTER TABLE claim_financial_refund_binding DROP CONSTRAINT financial_return_fee_required;
ALTER TABLE claim_financial_refund_binding ADD CONSTRAINT financial_return_fee_required CHECK(
 (intent_kind IN('CANCEL','SUPPORT') AND fee_assessment_id IS NULL)
 OR(intent_kind IN('RETURN','EXCHANGE_REFUND') AND purpose='ITEM' AND fee_assessment_id IS NOT NULL)
 OR(intent_kind IN('RETURN','EXCHANGE_REFUND') AND purpose='SHIPPING' AND fee_assessment_id IS NULL));

CREATE TABLE claim_support_refund_execution (
 id uuid PRIMARY KEY,approval_id uuid NOT NULL UNIQUE REFERENCES claim_support_refund_approval(id),
 attempt_id uuid NOT NULL UNIQUE REFERENCES claim_financial_refund_binding(attempt_id) DEFERRABLE INITIALLY DEFERRED,
 actor_id uuid NOT NULL REFERENCES identity_user(id),occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION verify_support_finance(target uuid) RETURNS void LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE a claim_support_refund_approval%ROWTYPE;
BEGIN
 SELECT * INTO a FROM claim_support_refund_approval WHERE id=target;
 IF NOT FOUND OR NOT EXISTS(SELECT 1 FROM claim_financial_intent i
 JOIN claim_financial_intent_unit u ON u.intent_id=i.id AND u.hold_id IS NULL AND u.item_id=a.item_id AND u.ordinal=a.ordinal
 JOIN claim_support_refund_execution x ON x.approval_id=i.id
 JOIN claim_financial_refund_binding b ON b.attempt_id=x.attempt_id AND b.intent_id=i.id AND b.intent_kind='SUPPORT'
 WHERE i.id=a.id AND i.kind='SUPPORT' AND i.cause_id=a.id AND i.order_id=a.order_id AND i.seller_order_id=a.seller_order_id
 AND b.purpose='ITEM' AND b.amount=a.net AND b.original_reference=a.id::text) THEN
 RAISE EXCEPTION 'SUPPORT_EXECUTION_GRAPH_REQUIRED' USING ERRCODE='23514';END IF;
END $$;
CREATE FUNCTION support_finance_graph_guard() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF TG_TABLE_NAME='claim_support_refund_execution' THEN PERFORM verify_support_finance(NEW.approval_id);
 ELSIF TG_TABLE_NAME='claim_financial_intent' THEN IF NEW.kind='SUPPORT' THEN PERFORM verify_support_finance(NEW.id);END IF;
 ELSIF TG_TABLE_NAME='claim_financial_intent_unit' THEN IF NEW.hold_id IS NULL THEN PERFORM verify_support_finance(NEW.intent_id);END IF;
 ELSIF NEW.intent_kind='SUPPORT' THEN
  PERFORM verify_support_finance(NEW.intent_id);
  IF NEW.purpose='ITEM' AND NOT EXISTS(SELECT 1 FROM claim_support_refund_execution WHERE approval_id=NEW.intent_id AND attempt_id=NEW.attempt_id)
  THEN RAISE EXCEPTION 'SUPPORT_EXACT_ITEM_BINDING_REQUIRED' USING ERRCODE='23514';END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER support_execution_graph AFTER INSERT ON claim_support_refund_execution DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION support_finance_graph_guard();
CREATE CONSTRAINT TRIGGER support_intent_graph AFTER INSERT ON claim_financial_intent DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION support_finance_graph_guard();
CREATE CONSTRAINT TRIGGER support_unit_graph AFTER INSERT ON claim_financial_intent_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION support_finance_graph_guard();
CREATE CONSTRAINT TRIGGER support_binding_graph AFTER INSERT ON claim_financial_refund_binding DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION support_finance_graph_guard();
CREATE TABLE claim_support_refund_result (
 attempt_id uuid NOT NULL REFERENCES claim_financial_refund_binding(attempt_id),result_version bigint NOT NULL,
 approval_id uuid NOT NULL REFERENCES claim_support_refund_approval(id),purpose text NOT NULL CHECK(purpose IN('ITEM','SHIPPING')),
 amount bigint NOT NULL CHECK(amount>=0),state text NOT NULL CHECK(state IN('FAILED','UNKNOWN','SUCCEEDED')),
 reference text,result_at timestamptz,occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),PRIMARY KEY(attempt_id,result_version)
);
CREATE FUNCTION verify_support_result() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM payment_refund_attempt p JOIN claim_financial_refund_binding b ON b.attempt_id=p.id
 WHERE p.id=NEW.attempt_id AND p.version=NEW.result_version AND p.state=NEW.state AND p.purpose=NEW.purpose AND p.amount=NEW.amount
 AND p.result_reference IS NOT DISTINCT FROM NEW.reference AND p.result_at IS NOT DISTINCT FROM NEW.result_at
 AND b.intent_kind='SUPPORT' AND b.intent_id=NEW.approval_id) THEN
 RAISE EXCEPTION 'SUPPORT_ACTUAL_REFUND_RESULT_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER support_result_actual BEFORE INSERT ON claim_support_refund_result FOR EACH ROW EXECUTE FUNCTION verify_support_result();
CREATE TRIGGER support_collection_immutable BEFORE UPDATE OR DELETE ON claim_support_collection FOR EACH ROW EXECUTE FUNCTION settlement_immutable();
CREATE TRIGGER support_inspection_immutable BEFORE UPDATE OR DELETE ON claim_support_inspection FOR EACH ROW EXECUTE FUNCTION settlement_immutable();
CREATE TRIGGER support_refund_approval_immutable BEFORE UPDATE OR DELETE ON claim_support_refund_approval FOR EACH ROW EXECUTE FUNCTION settlement_immutable();
CREATE TRIGGER support_refund_execution_immutable BEFORE UPDATE OR DELETE ON claim_support_refund_execution FOR EACH ROW EXECUTE FUNCTION settlement_immutable();
CREATE TRIGGER support_refund_result_immutable BEFORE UPDATE OR DELETE ON claim_support_refund_result FOR EACH ROW EXECUTE FUNCTION settlement_immutable();
