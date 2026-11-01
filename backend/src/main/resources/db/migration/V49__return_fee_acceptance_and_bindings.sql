CREATE TABLE claim_fee_assessment (
 id uuid PRIMARY KEY,quote_id uuid NOT NULL UNIQUE,intent_id uuid NOT NULL UNIQUE,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,
 intent_kind varchar(10) NOT NULL DEFAULT 'RETURN' CHECK(intent_kind='RETURN'),request_key varchar(100) NOT NULL,fingerprint varchar(64) NOT NULL,quote_fingerprint varchar(64) NOT NULL,
 net bigint NOT NULL CHECK(net>=0),increment bigint NOT NULL CHECK(increment>=0),refund bigint NOT NULL CHECK(refund BETWEEN 0 AND net),additional_due bigint NOT NULL CHECK(additional_due>=0),
 state varchar(12) NOT NULL CHECK(state IN ('RESERVED','BOUND','APPLIED','WITHDRAWN')),version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 receipt jsonb NOT NULL,accepted_at timestamptz NOT NULL,created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
 FOREIGN KEY(quote_id,order_id,seller_order_id) REFERENCES claim_fee_quote(id,order_id,seller_order_id),
 FOREIGN KEY(intent_id,seller_order_id,order_id,intent_kind) REFERENCES claim_financial_intent(id,seller_order_id,order_id,kind),
 UNIQUE(order_id,request_key),UNIQUE(id,order_id,seller_order_id),UNIQUE(id,intent_id,order_id,seller_order_id),UNIQUE(id,intent_id,order_id,seller_order_id,refund,quote_fingerprint),
 CHECK(increment::numeric=net::numeric-refund+additional_due)
);
CREATE TABLE claim_fee_consent (
 assessment_id uuid PRIMARY KEY REFERENCES claim_fee_assessment(id),quote_id uuid NOT NULL REFERENCES claim_fee_quote(id),legacy_consent boolean NOT NULL,
 policy_version varchar(100) NOT NULL CHECK(policy_version='P09-RETURN-PRODUCT-MAX-1'),policy_source varchar(40) NOT NULL CHECK(policy_source IN ('ORDER_ITEM_SNAPSHOT','LEGACY_QUOTE_EXPLICIT_CONSENT')),
 authority_reference varchar(64) NOT NULL,accepted_at timestamptz NOT NULL
);
CREATE TABLE claim_fee_assessment_bucket (
 assessment_id uuid NOT NULL REFERENCES claim_fee_assessment(id),bucket_id uuid NOT NULL,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,original_shipment_id uuid NOT NULL,
 baseline bigint NOT NULL CHECK(baseline>=0),selected_maximum bigint NOT NULL CHECK(selected_maximum>=0),target bigint NOT NULL CHECK(target>=baseline AND target=GREATEST(baseline,selected_maximum)),
 increment bigint NOT NULL CHECK(increment>=0 AND increment=target-baseline),deducted bigint NOT NULL CHECK(deducted BETWEEN 0 AND increment),additional_due bigint NOT NULL CHECK(additional_due=increment-deducted),
 PRIMARY KEY(assessment_id,bucket_id),FOREIGN KEY(assessment_id,order_id,seller_order_id) REFERENCES claim_fee_assessment(id,order_id,seller_order_id),FOREIGN KEY(bucket_id,order_id,seller_order_id,original_shipment_id) REFERENCES claim_fee_bucket(id,order_id,seller_order_id,original_shipment_id)
);
CREATE TABLE claim_fee_assessment_unit (
 assessment_id uuid NOT NULL,intent_id uuid NOT NULL,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,item_id uuid NOT NULL,ordinal integer NOT NULL,
 bucket_id uuid NOT NULL,original_shipment_id uuid NOT NULL,net bigint NOT NULL CHECK(net>=0),policy_fee bigint NOT NULL CHECK(policy_fee>=0),policy_source varchar(40) NOT NULL,policy_revision bigint NOT NULL CHECK(policy_revision>=0),
 responsibility varchar(10) NOT NULL CHECK(responsibility IN ('BUYER','SELLER')),deducted bigint NOT NULL CHECK(deducted BETWEEN 0 AND net AND (responsibility='BUYER' OR deducted=0)),
 PRIMARY KEY(assessment_id,item_id,ordinal),FOREIGN KEY(assessment_id,intent_id,order_id,seller_order_id) REFERENCES claim_fee_assessment(id,intent_id,order_id,seller_order_id),
 FOREIGN KEY(intent_id,item_id,ordinal) REFERENCES claim_financial_intent_unit(intent_id,item_id,ordinal),
 FOREIGN KEY(assessment_id,bucket_id) REFERENCES claim_fee_assessment_bucket(assessment_id,bucket_id),FOREIGN KEY(bucket_id,order_id,seller_order_id,original_shipment_id) REFERENCES claim_fee_bucket(id,order_id,seller_order_id,original_shipment_id)
);
ALTER TABLE claim_fee_bucket ADD CONSTRAINT fee_active_assessment_required FOREIGN KEY(active_assessment_id,id) REFERENCES claim_fee_assessment_bucket(assessment_id,bucket_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE claim_financial_refund_binding ADD COLUMN fee_assessment_id uuid;
ALTER TABLE claim_financial_refund_binding ADD CONSTRAINT financial_return_fee_required CHECK((intent_kind='CANCEL' AND fee_assessment_id IS NULL) OR(intent_kind='RETURN' AND fee_assessment_id IS NOT NULL));
ALTER TABLE claim_financial_refund_binding ADD CONSTRAINT financial_fee_binding_identity UNIQUE(attempt_id,intent_id,order_id,seller_order_id,amount,original_reference,purpose);
CREATE TABLE claim_fee_binding_history (
 attempt_id uuid PRIMARY KEY,assessment_id uuid NOT NULL,intent_id uuid NOT NULL,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,
 amount bigint NOT NULL CHECK(amount>=0),quote_fingerprint varchar(64) NOT NULL,purpose varchar(10) NOT NULL DEFAULT 'ITEM' CHECK(purpose='ITEM'),created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(assessment_id,attempt_id),FOREIGN KEY(assessment_id,intent_id,order_id,seller_order_id,amount,quote_fingerprint) REFERENCES claim_fee_assessment(id,intent_id,order_id,seller_order_id,refund,quote_fingerprint),
 FOREIGN KEY(attempt_id,intent_id,order_id,seller_order_id,amount,quote_fingerprint,purpose) REFERENCES claim_financial_refund_binding(attempt_id,intent_id,order_id,seller_order_id,amount,original_reference,purpose) DEFERRABLE INITIALLY DEFERRED
);
ALTER TABLE claim_financial_refund_binding ADD CONSTRAINT financial_fee_assessment_required FOREIGN KEY(fee_assessment_id,attempt_id) REFERENCES claim_fee_binding_history(assessment_id,attempt_id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE claim_fee_binding (
 assessment_id uuid PRIMARY KEY REFERENCES claim_fee_assessment(id),attempt_id uuid NOT NULL UNIQUE,
 FOREIGN KEY(assessment_id,attempt_id) REFERENCES claim_fee_binding_history(assessment_id,attempt_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE FUNCTION protect_fee_current_binding() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF TG_OP='DELETE' OR NEW.assessment_id<>OLD.assessment_id
 OR NOT EXISTS(SELECT 1 FROM claim_fee_assessment a JOIN payment_refund_attempt old_attempt ON old_attempt.id=OLD.attempt_id JOIN payment_refund_attempt new_attempt ON new_attempt.id=NEW.attempt_id
 WHERE a.id=OLD.assessment_id AND a.state='BOUND' AND old_attempt.state='FAILED' AND new_attempt.state='RESERVED' AND new_attempt.claim_id=old_attempt.claim_id AND new_attempt.purpose=old_attempt.purpose
 AND new_attempt.amount=old_attempt.amount AND new_attempt.original_reference=old_attempt.original_reference AND new_attempt.sequence=old_attempt.sequence+1)
 THEN RAISE EXCEPTION 'IMMUTABLE_FEE_REFUND_BINDING' USING ERRCODE='23514';END IF;RETURN NEW;
END $$;
CREATE TRIGGER fee_current_binding_protected BEFORE UPDATE OR DELETE ON claim_fee_binding FOR EACH ROW EXECUTE FUNCTION protect_fee_current_binding();
ALTER TABLE claim_fee_assessment ADD CONSTRAINT fee_refund_binding_required FOREIGN KEY(id) REFERENCES claim_fee_binding(assessment_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE claim_fee_assessment ADD CONSTRAINT fee_consent_required FOREIGN KEY(id) REFERENCES claim_fee_consent(assessment_id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE claim_fee_event (
 id uuid PRIMARY KEY,assessment_id uuid NOT NULL REFERENCES claim_fee_assessment(id),version bigint NOT NULL,action varchar(40) NOT NULL,reason varchar(500),occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(assessment_id,version,action)
);
CREATE FUNCTION fee_content_insert_once() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM claim_fee_assessment a WHERE a.id=NEW.assessment_id AND a.created_xid=pg_current_xact_id()) THEN RAISE EXCEPTION 'IMMUTABLE_FEE_ASSESSMENT_CONTENT' USING ERRCODE='23514'; END IF;RETURN NEW;
END $$;
CREATE TRIGGER fee_unit_insert_once BEFORE INSERT ON claim_fee_assessment_unit FOR EACH ROW EXECUTE FUNCTION fee_content_insert_once();
CREATE TRIGGER fee_bucket_insert_once BEFORE INSERT ON claim_fee_assessment_bucket FOR EACH ROW EXECUTE FUNCTION fee_content_insert_once();
CREATE TRIGGER fee_consent_insert_once BEFORE INSERT ON claim_fee_consent FOR EACH ROW EXECUTE FUNCTION fee_content_insert_once();
CREATE FUNCTION fee_unit_original_quote_valid() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE q jsonb;claimed jsonb;p record;original_net bigint;shipment uuid;
BEGIN
 SELECT x.body INTO q FROM claim_fee_assessment a JOIN claim_fee_quote x ON x.id=a.quote_id WHERE a.id=NEW.assessment_id;
 IF(SELECT count(*) FROM jsonb_array_elements(q->'units') x WHERE(x->>'itemId')::uuid=NEW.item_id AND(x->>'ordinal')::integer=NEW.ordinal)<>1 THEN RAISE EXCEPTION 'FEE_QUOTE_UNIT_MISMATCH' USING ERRCODE='23514';END IF;
 SELECT x INTO claimed FROM jsonb_array_elements(q->'units') x WHERE(x->>'itemId')::uuid=NEW.item_id AND(x->>'ordinal')::integer=NEW.ordinal;
 SELECT net INTO original_net FROM order_unit WHERE item_id=NEW.item_id AND ordinal=NEW.ordinal;
 SELECT d.shipment_id INTO shipment FROM order_initial_delivery_unit u JOIN order_initial_delivery d ON d.event_id=u.event_id WHERE u.item_id=NEW.item_id AND u.ordinal=NEW.ordinal AND u.order_id=NEW.order_id AND u.seller_order_id=NEW.seller_order_id;
 SELECT status,policy_snapshot INTO p FROM order_item_return_policy WHERE item_id=NEW.item_id;
 IF NEW.net IS DISTINCT FROM original_net OR NEW.original_shipment_id IS DISTINCT FROM shipment
 OR NEW.net IS DISTINCT FROM(claimed->>'net')::bigint OR NEW.policy_fee IS DISTINCT FROM(claimed->>'policyFee')::bigint OR NEW.policy_revision IS DISTINCT FROM(claimed->>'policyRevision')::bigint
 OR NEW.original_shipment_id IS DISTINCT FROM(claimed->>'originalShipmentId')::uuid OR NEW.responsibility IS DISTINCT FROM claimed->>'responsibility' OR NEW.deducted IS DISTINCT FROM(claimed->>'deducted')::bigint
 OR NEW.policy_source IS DISTINCT FROM (CASE WHEN claimed->>'policySource'='LEGACY_UNSPECIFIED' THEN 'LEGACY_QUOTE_EXPLICIT_CONSENT' ELSE claimed->>'policySource' END)
 OR NOT((p.status='ACTIVE' AND NEW.policy_fee=(p.policy_snapshot->>'amount')::bigint AND NEW.policy_revision=(p.policy_snapshot->>'revision')::bigint AND NEW.policy_source=p.policy_snapshot->>'source')
 OR(p.status='UNRESOLVED' AND NEW.policy_fee=3000 AND NEW.policy_revision=0 AND NEW.policy_source='LEGACY_QUOTE_EXPLICIT_CONSENT'))
 THEN RAISE EXCEPTION 'FEE_QUOTE_ORIGINAL_UNIT_MISMATCH' USING ERRCODE='23514';END IF;RETURN NEW;
END $$;
CREATE TRIGGER fee_unit_quote_original BEFORE INSERT ON claim_fee_assessment_unit FOR EACH ROW EXECUTE FUNCTION fee_unit_original_quote_valid();
CREATE TRIGGER fee_unit_immutable BEFORE UPDATE OR DELETE ON claim_fee_assessment_unit FOR EACH ROW EXECUTE FUNCTION immutable_fee_quote();
CREATE TRIGGER fee_bucket_content_immutable BEFORE UPDATE OR DELETE ON claim_fee_assessment_bucket FOR EACH ROW EXECUTE FUNCTION immutable_fee_quote();
CREATE TRIGGER fee_consent_immutable BEFORE UPDATE OR DELETE ON claim_fee_consent FOR EACH ROW EXECUTE FUNCTION immutable_fee_quote();
CREATE TRIGGER fee_binding_history_immutable BEFORE UPDATE OR DELETE ON claim_fee_binding_history FOR EACH ROW EXECUTE FUNCTION immutable_fee_quote();
CREATE TRIGGER fee_event_immutable BEFORE UPDATE OR DELETE ON claim_fee_event FOR EACH ROW EXECUTE FUNCTION immutable_fee_quote();
CREATE FUNCTION protect_fee_assessment() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF TG_OP='DELETE' OR OLD.state IN ('APPLIED','WITHDRAWN') OR NEW.version<>OLD.version+1
 OR(NEW.id,NEW.quote_id,NEW.intent_id,NEW.order_id,NEW.seller_order_id,NEW.intent_kind,NEW.request_key,NEW.fingerprint,NEW.quote_fingerprint,NEW.net,NEW.increment,NEW.refund,NEW.additional_due,NEW.receipt,NEW.accepted_at,NEW.created_xid)
 IS DISTINCT FROM(OLD.id,OLD.quote_id,OLD.intent_id,OLD.order_id,OLD.seller_order_id,OLD.intent_kind,OLD.request_key,OLD.fingerprint,OLD.quote_fingerprint,OLD.net,OLD.increment,OLD.refund,OLD.additional_due,OLD.receipt,OLD.accepted_at,OLD.created_xid)
 OR NOT((OLD.state='RESERVED' AND NEW.state IN ('BOUND','WITHDRAWN')) OR(OLD.state='BOUND' AND NEW.state IN ('BOUND','APPLIED','WITHDRAWN')))
 THEN RAISE EXCEPTION 'IMMUTABLE_FEE_ASSESSMENT' USING ERRCODE='23514';END IF;RETURN NEW;
END $$;
CREATE TRIGGER fee_assessment_protected BEFORE UPDATE OR DELETE ON claim_fee_assessment FOR EACH ROW EXECUTE FUNCTION protect_fee_assessment();
CREATE FUNCTION check_fee_assessment(target uuid) RETURNS void LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE a claim_fee_assessment%ROWTYPE;b record;remaining bigint;u record;share bigint;
BEGIN
 SELECT * INTO a FROM claim_fee_assessment WHERE id=target;IF NOT FOUND THEN RETURN;END IF;
 IF NOT EXISTS(SELECT 1 FROM claim_fee_assessment_unit WHERE assessment_id=target)
 OR NOT EXISTS(SELECT 1 FROM claim_fee_quote q WHERE q.id=a.quote_id AND q.body->>'net'=a.net::text AND q.body->>'increment'=a.increment::text AND q.body->>'refund'=a.refund::text AND q.body->>'additionalDue'=a.additional_due::text AND jsonb_array_length(q.body->'units')=(SELECT count(*) FROM claim_fee_assessment_unit WHERE assessment_id=target))
 OR(SELECT COALESCE(sum(net),0) FROM claim_fee_assessment_unit WHERE assessment_id=target)<>a.net
 OR(SELECT COALESCE(sum(deducted),0) FROM claim_fee_assessment_unit WHERE assessment_id=target)<>a.net-a.refund
 OR(SELECT COALESCE(sum(increment),0) FROM claim_fee_assessment_bucket WHERE assessment_id=target)<>a.increment
 OR(SELECT COALESCE(sum(additional_due),0) FROM claim_fee_assessment_bucket WHERE assessment_id=target)<>a.additional_due
 OR EXISTS(SELECT item_id,ordinal FROM claim_financial_intent_unit WHERE intent_id=a.intent_id EXCEPT SELECT item_id,ordinal FROM claim_fee_assessment_unit WHERE assessment_id=target)
 OR NOT EXISTS(SELECT 1 FROM claim_fee_consent c JOIN claim_fee_quote q ON q.id=c.quote_id WHERE c.assessment_id=target AND q.id=a.quote_id AND q.fingerprint=a.quote_fingerprint AND c.legacy_consent=q.legacy_consent_required AND c.policy_source=CASE WHEN c.legacy_consent THEN 'LEGACY_QUOTE_EXPLICIT_CONSENT' ELSE 'ORDER_ITEM_SNAPSHOT' END)
 THEN RAISE EXCEPTION 'FEE_ASSESSMENT_CONTENT_MISMATCH' USING ERRCODE='23514';END IF;
 FOR b IN SELECT * FROM claim_fee_assessment_bucket WHERE assessment_id=target LOOP
  IF NOT EXISTS(SELECT 1 FROM claim_fee_assessment_unit WHERE assessment_id=target AND bucket_id=b.bucket_id)
  OR b.selected_maximum<>(SELECT COALESCE(max(policy_fee) FILTER(WHERE responsibility='BUYER'),0) FROM claim_fee_assessment_unit WHERE assessment_id=target AND bucket_id=b.bucket_id)
  OR b.deducted<>(SELECT COALESCE(sum(deducted),0) FROM claim_fee_assessment_unit WHERE assessment_id=target AND bucket_id=b.bucket_id) THEN RAISE EXCEPTION 'FEE_BUCKET_ALLOCATION_MISMATCH' USING ERRCODE='23514';END IF;
  remaining:=b.increment;
  FOR u IN SELECT * FROM claim_fee_assessment_unit WHERE assessment_id=target AND bucket_id=b.bucket_id ORDER BY item_id::text,ordinal LOOP
   share:=CASE WHEN u.responsibility='BUYER' THEN LEAST(remaining,u.net) ELSE 0 END;
   IF u.deducted<>share THEN RAISE EXCEPTION 'FEE_CANONICAL_ALLOCATION_MISMATCH' USING ERRCODE='23514';END IF;remaining:=remaining-share;
  END LOOP;
  IF remaining<>b.additional_due THEN RAISE EXCEPTION 'FEE_ADDITIONAL_DUE_MISMATCH' USING ERRCODE='23514';END IF;
 END LOOP;
END $$;
CREATE FUNCTION check_fee_assessment_row() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN PERFORM check_fee_assessment(COALESCE(NEW.id,OLD.id));RETURN NULL;END $$;
CREATE CONSTRAINT TRIGGER fee_assessment_complete AFTER INSERT OR UPDATE ON claim_fee_assessment DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_fee_assessment_row();
CREATE FUNCTION check_fee_bucket(target uuid) RETURNS void LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE b claim_fee_bucket%ROWTYPE;
BEGIN
 SELECT * INTO b FROM claim_fee_bucket WHERE id=target;IF NOT FOUND THEN RETURN;END IF;
 IF b.assessed_total<>(SELECT COALESCE(sum(x.increment),0) FROM claim_fee_assessment_bucket x JOIN claim_fee_assessment a ON a.id=x.assessment_id WHERE x.bucket_id=target AND a.state='APPLIED')
 OR b.deducted_applied<>(SELECT COALESCE(sum(x.deducted),0) FROM claim_fee_assessment_bucket x JOIN claim_fee_assessment a ON a.id=x.assessment_id WHERE x.bucket_id=target AND a.state='APPLIED')
 OR b.additional_collected<>0
 OR b.additional_outstanding<>(SELECT COALESCE(sum(x.additional_due),0) FROM claim_fee_assessment_bucket x JOIN claim_fee_assessment a ON a.id=x.assessment_id WHERE x.bucket_id=target AND a.state='APPLIED')
 OR(b.active_assessment_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM claim_fee_assessment_bucket x JOIN claim_fee_assessment a ON a.id=x.assessment_id WHERE x.assessment_id=b.active_assessment_id AND x.bucket_id=target AND a.state IN ('RESERVED','BOUND') AND x.baseline=b.assessed_total AND x.increment=b.reserved_increment))
 OR EXISTS(SELECT 1 FROM claim_fee_assessment_bucket x JOIN claim_fee_assessment a ON a.id=x.assessment_id WHERE x.bucket_id=target AND a.state IN ('RESERVED','BOUND') AND x.assessment_id IS DISTINCT FROM b.active_assessment_id)
 THEN RAISE EXCEPTION 'FEE_BUCKET_LEDGER_MISMATCH' USING ERRCODE='23514';END IF;
END $$;
CREATE FUNCTION check_fee_bucket_row() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN PERFORM check_fee_bucket(COALESCE(NEW.id,OLD.id));RETURN NULL;END $$;
CREATE CONSTRAINT TRIGGER fee_bucket_ledger_consistent AFTER INSERT OR UPDATE ON claim_fee_bucket DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_fee_bucket_row();
