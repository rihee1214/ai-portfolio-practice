-- V38 is already evidenced. Strengthen its cross-table invariants without rewriting its checksum.
ALTER TABLE purchase_order ADD CONSTRAINT order_policy_required FOREIGN KEY(id) REFERENCES order_aftercare_policy(order_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE purchase_order ADD CONSTRAINT order_confirmation_state_required FOREIGN KEY(id) REFERENCES order_confirmation_state(order_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE order_unit ADD CONSTRAINT order_unit_lifecycle_required FOREIGN KEY(item_id,ordinal) REFERENCES order_unit_lifecycle(item_id,ordinal) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE order_unit_lifecycle ADD CONSTRAINT order_lifecycle_identity UNIQUE(item_id,ordinal,order_id);
CREATE TABLE order_confirmation_unit (
 item_id uuid NOT NULL,ordinal integer NOT NULL,order_id uuid NOT NULL,confirmation_id uuid NOT NULL,
 PRIMARY KEY(item_id,ordinal),UNIQUE(confirmation_id,item_id,ordinal,order_id),
 FOREIGN KEY(item_id,ordinal,order_id) REFERENCES order_unit_lifecycle(item_id,ordinal,order_id) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(confirmation_id,order_id) REFERENCES order_confirmation(id,order_id) DEFERRABLE INITIALLY DEFERRED
);
INSERT INTO order_confirmation_unit SELECT item_id,ordinal,order_id,confirmation_id FROM order_unit_lifecycle WHERE state='CONFIRMED';
ALTER TABLE order_unit_lifecycle ADD CONSTRAINT order_confirmation_unit_required FOREIGN KEY(confirmation_id,item_id,ordinal,order_id) REFERENCES order_confirmation_unit(confirmation_id,item_id,ordinal,order_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE order_unit_hold ADD CONSTRAINT order_hold_cause_identity UNIQUE(cause_id,order_id);
ALTER TABLE order_unit_lifecycle ADD CONSTRAINT order_refund_hold_required FOREIGN KEY(refund_cause_id,order_id) REFERENCES order_unit_hold(cause_id,order_id) DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION assert_order_active_holds(target uuid) RETURNS void LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF EXISTS (
  SELECT h.id FROM order_unit_hold h LEFT JOIN order_hold_unit u ON u.hold_id=h.id
  LEFT JOIN order_unit_lifecycle l ON l.item_id=u.item_id AND l.ordinal=u.ordinal
  WHERE h.order_id=target AND h.state='ACTIVE' GROUP BY h.id
  HAVING count(u.item_id)=0 OR bool_or(l.state IS DISTINCT FROM 'HELD' OR l.active_hold_id IS DISTINCT FROM h.id OR l.order_id IS DISTINCT FROM h.order_id)
 ) OR EXISTS (
  SELECT 1 FROM order_unit_lifecycle l LEFT JOIN order_unit_hold h ON h.id=l.active_hold_id
  LEFT JOIN order_hold_unit u ON u.hold_id=h.id AND u.item_id=l.item_id AND u.ordinal=l.ordinal
  WHERE l.order_id=target AND ((l.state='HELD' AND (h.state IS DISTINCT FROM 'ACTIVE' OR h.order_id IS DISTINCT FROM target OR u.item_id IS NULL))
   OR (l.state<>'HELD' AND EXISTS(SELECT 1 FROM order_hold_unit x JOIN order_unit_hold a ON a.id=x.hold_id WHERE x.item_id=l.item_id AND x.ordinal=l.ordinal AND a.state='ACTIVE')))
 ) THEN RAISE EXCEPTION 'ORDER_ACTIVE_HOLD_UNIT_MISMATCH' USING ERRCODE='23514'; END IF;
END $$;
CREATE FUNCTION check_order_active_holds() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE target uuid;
BEGIN
 IF TG_TABLE_NAME='order_hold_unit' THEN SELECT order_id INTO target FROM order_unit_hold WHERE id=COALESCE(NEW.hold_id,OLD.hold_id);
 ELSE target:=COALESCE(NEW.order_id,OLD.order_id); END IF;
 PERFORM assert_order_active_holds(target);RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER order_hold_matches_units AFTER INSERT OR UPDATE OR DELETE ON order_unit_hold DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_order_active_holds();
CREATE CONSTRAINT TRIGGER order_hold_membership_matches_units AFTER INSERT OR UPDATE OR DELETE ON order_hold_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_order_active_holds();
CREATE CONSTRAINT TRIGGER order_lifecycle_matches_hold AFTER INSERT OR UPDATE OR DELETE ON order_unit_lifecycle DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_order_active_holds();
CREATE FUNCTION keep_order_unit_terminal() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF OLD.state IN ('CONFIRMED','REFUNDED') AND NEW IS DISTINCT FROM OLD THEN RAISE EXCEPTION 'ORDER_UNIT_TERMINAL_IMMUTABLE' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER order_unit_terminal_immutable BEFORE UPDATE ON order_unit_lifecycle FOR EACH ROW EXECUTE FUNCTION keep_order_unit_terminal();
DO $$ DECLARE target uuid; BEGIN FOR target IN SELECT order_id FROM order_confirmation_state LOOP PERFORM assert_order_active_holds(target); END LOOP; END $$;
