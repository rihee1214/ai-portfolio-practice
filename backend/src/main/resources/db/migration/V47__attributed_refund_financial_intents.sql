-- Common identity is separate from CANCEL workflow; RETURN does not imply return approval/completion.
ALTER TABLE order_unit_hold ADD CONSTRAINT order_financial_hold_identity UNIQUE(id,cause_id,order_id,kind);
CREATE TABLE claim_financial_intent (
 id uuid PRIMARY KEY,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,hold_id uuid NOT NULL UNIQUE,cause_id uuid NOT NULL UNIQUE,
 kind varchar(10) NOT NULL CHECK(kind IN ('CANCEL','RETURN')),fingerprint varchar(64) NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(seller_order_id,order_id) REFERENCES seller_order(id,order_id),
 FOREIGN KEY(hold_id,cause_id,order_id,kind) REFERENCES order_unit_hold(id,cause_id,order_id,kind),
 UNIQUE(id,seller_order_id,order_id),UNIQUE(id,seller_order_id,order_id,kind),UNIQUE(id,hold_id,seller_order_id,order_id)
);
CREATE TABLE claim_financial_intent_unit (
 intent_id uuid NOT NULL,hold_id uuid NOT NULL,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,item_id uuid NOT NULL,ordinal integer NOT NULL,
 PRIMARY KEY(intent_id,item_id,ordinal),
 FOREIGN KEY(intent_id,hold_id,seller_order_id,order_id) REFERENCES claim_financial_intent(id,hold_id,seller_order_id,order_id),
 FOREIGN KEY(hold_id,item_id,ordinal) REFERENCES order_hold_unit(hold_id,item_id,ordinal),
 FOREIGN KEY(item_id,seller_order_id,order_id) REFERENCES order_item(id,seller_order_id,order_id),FOREIGN KEY(item_id,ordinal) REFERENCES order_unit(item_id,ordinal)
);
-- Backfill only actual existing customer cancellation/hold evidence. No amounts/results are rewritten.
INSERT INTO claim_financial_intent(id,order_id,seller_order_id,hold_id,cause_id,kind,fingerprint,created_at)
 SELECT c.id,c.order_id,c.seller_order_id,c.hold_id,h.cause_id,'CANCEL',
 encode(sha256(convert_to(c.id||'|'||c.order_id||'|'||c.seller_order_id||'|'||c.hold_id||'|'||h.cause_id||'|CANCEL|'||
 (SELECT '['||string_agg('Unit[itemId='||u.item_id||', ordinal='||u.ordinal||']',', ' ORDER BY u.item_id::text,u.ordinal)||']' FROM claim_unit u WHERE u.claim_id=c.id),'UTF8')),'hex'),c.created_at
 FROM claim c JOIN order_unit_hold h ON h.id=c.hold_id AND h.order_id=c.order_id AND h.cause_id=c.id AND h.kind='CANCEL';
INSERT INTO claim_financial_intent_unit SELECT c.id,c.hold_id,c.order_id,c.seller_order_id,u.item_id,u.ordinal FROM claim_financial_intent c JOIN claim_unit u ON u.claim_id=c.id;
ALTER TABLE claim ADD CONSTRAINT cancellation_financial_intent_required FOREIGN KEY(id,seller_order_id,order_id) REFERENCES claim_financial_intent(id,seller_order_id,order_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE claim ADD COLUMN financial_intent_kind varchar(10) NOT NULL DEFAULT 'CANCEL' CHECK(financial_intent_kind='CANCEL');
ALTER TABLE claim ADD CONSTRAINT cancellation_intent_kind_required FOREIGN KEY(id,seller_order_id,order_id,financial_intent_kind) REFERENCES claim_financial_intent(id,seller_order_id,order_id,kind) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE payment_refund_attempt ADD CONSTRAINT refund_financial_intent_required FOREIGN KEY(claim_id,seller_order_id,order_id) REFERENCES claim_financial_intent(id,seller_order_id,order_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE payment_refund_attempt ADD CONSTRAINT refund_binding_identity UNIQUE(id,claim_id,order_id,seller_order_id,payment_id,purpose,amount,original_reference);
CREATE TABLE claim_financial_refund_binding (
 attempt_id uuid PRIMARY KEY,intent_id uuid NOT NULL,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,intent_kind varchar(10) NOT NULL,payment_id uuid NOT NULL,
 purpose varchar(10) NOT NULL CHECK(purpose IN ('ITEM','SHIPPING')),amount bigint NOT NULL CHECK(amount>=0),original_reference varchar(200) NOT NULL,fingerprint varchar(64) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),CHECK(intent_kind='CANCEL' OR(intent_kind='RETURN' AND purpose='ITEM')),
 FOREIGN KEY(intent_id,seller_order_id,order_id,intent_kind) REFERENCES claim_financial_intent(id,seller_order_id,order_id,kind),
 FOREIGN KEY(attempt_id,intent_id,order_id,seller_order_id,payment_id,purpose,amount,original_reference) REFERENCES payment_refund_attempt(id,claim_id,order_id,seller_order_id,payment_id,purpose,amount,original_reference) DEFERRABLE INITIALLY DEFERRED
);
INSERT INTO claim_financial_refund_binding(attempt_id,intent_id,order_id,seller_order_id,intent_kind,payment_id,purpose,amount,original_reference,fingerprint)
 SELECT a.id,a.claim_id,a.order_id,a.seller_order_id,i.kind,a.payment_id,a.purpose,a.amount,a.original_reference,
 encode(sha256(convert_to(a.id||'|'||a.payment_id||'|'||a.order_id||'|'||a.seller_order_id||'|'||a.claim_id||'|'||a.purpose||'|'||a.sequence||'|'||a.amount||'|'||a.original_reference,'UTF8')),'hex')
 FROM payment_refund_attempt a JOIN claim_financial_intent i ON i.id=a.claim_id AND i.order_id=a.order_id AND i.seller_order_id=a.seller_order_id;
ALTER TABLE payment_refund_attempt ADD CONSTRAINT refund_intent_binding_required FOREIGN KEY(id) REFERENCES claim_financial_refund_binding(attempt_id) DEFERRABLE INITIALLY DEFERRED;
CREATE FUNCTION financial_intent_units_exact(target uuid) RETURNS void LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE owned_hold uuid;
BEGIN
 SELECT hold_id INTO owned_hold FROM claim_financial_intent WHERE id=target;
 IF owned_hold IS NULL THEN RETURN; END IF;
 IF NOT EXISTS(SELECT 1 FROM claim_financial_intent_unit WHERE intent_id=target)
 OR EXISTS(SELECT item_id,ordinal FROM order_hold_unit WHERE hold_id=owned_hold EXCEPT SELECT item_id,ordinal FROM claim_financial_intent_unit WHERE intent_id=target)
 THEN RAISE EXCEPTION 'FINANCIAL_INTENT_HOLD_UNIT_MISMATCH' USING ERRCODE='23514'; END IF;
END $$;
CREATE FUNCTION financial_intent_units_row() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF TG_TABLE_NAME='claim_financial_intent' THEN PERFORM financial_intent_units_exact(COALESCE(NEW.id,OLD.id));
 ELSE PERFORM financial_intent_units_exact(COALESCE(NEW.intent_id,OLD.intent_id));END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER financial_intent_units_required AFTER INSERT OR UPDATE OR DELETE ON claim_financial_intent DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION financial_intent_units_row();
CREATE CONSTRAINT TRIGGER financial_intent_units_consistent AFTER INSERT OR UPDATE OR DELETE ON claim_financial_intent_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION financial_intent_units_row();
CREATE FUNCTION immutable_financial_intent() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN RAISE EXCEPTION 'IMMUTABLE_FINANCIAL_INTENT' USING ERRCODE='23514';END $$;
CREATE TRIGGER financial_intent_immutable BEFORE UPDATE OR DELETE ON claim_financial_intent FOR EACH ROW EXECUTE FUNCTION immutable_financial_intent();
CREATE TRIGGER financial_intent_unit_immutable BEFORE UPDATE OR DELETE ON claim_financial_intent_unit FOR EACH ROW EXECUTE FUNCTION immutable_financial_intent();
CREATE TRIGGER financial_refund_binding_immutable BEFORE UPDATE OR DELETE ON claim_financial_refund_binding FOR EACH ROW EXECUTE FUNCTION immutable_financial_intent();
