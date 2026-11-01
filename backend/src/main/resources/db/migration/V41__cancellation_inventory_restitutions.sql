-- Original COMMITTED reservations are immutable evidence; cancellation adds a separate inbound movement.
ALTER TABLE inventory_reservation ADD CONSTRAINT inventory_restitution_identity UNIQUE(id,checkout_id,order_item_id,sku_id);
CREATE TABLE inventory_restitution (
 cause_id uuid PRIMARY KEY,checkout_id uuid NOT NULL,fingerprint varchar(64) NOT NULL,
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(cause_id,checkout_id)
);
CREATE TABLE inventory_restitution_unit (
 cause_id uuid NOT NULL,reservation_id uuid NOT NULL,item_id uuid NOT NULL,ordinal integer NOT NULL CHECK(ordinal>=0),sku_id uuid NOT NULL,checkout_id uuid NOT NULL,
 PRIMARY KEY(cause_id,item_id,ordinal),UNIQUE(reservation_id,ordinal),
 FOREIGN KEY(cause_id,checkout_id) REFERENCES inventory_restitution(cause_id,checkout_id),
 FOREIGN KEY(reservation_id,checkout_id,item_id,sku_id) REFERENCES inventory_reservation(id,checkout_id,order_item_id,sku_id)
);
CREATE TABLE inventory_restitution_movement (
 cause_id uuid NOT NULL REFERENCES inventory_restitution(cause_id),sku_id uuid NOT NULL REFERENCES inventory_balance(sku_id),quantity bigint NOT NULL CHECK(quantity>0),
 before_on_hand bigint NOT NULL CHECK(before_on_hand>=0),after_on_hand bigint NOT NULL CHECK(after_on_hand=before_on_hand+quantity AND after_on_hand<=999999999),
 reserved bigint NOT NULL CHECK(reserved BETWEEN 0 AND before_on_hand),before_version bigint NOT NULL CHECK(before_version>=0),version bigint NOT NULL CHECK(version=before_version+1 AND version<=9007199254740991),
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),PRIMARY KEY(cause_id,sku_id),UNIQUE(sku_id,version)
);
CREATE FUNCTION check_cancellation_restitution_unit() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM inventory_reservation r WHERE r.id=NEW.reservation_id AND r.status='COMMITTED' AND NEW.ordinal<r.quantity) THEN RAISE EXCEPTION 'CANCELLATION_RESTITUTION_NOT_COMMITTED_OR_OUT_OF_RANGE' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER cancellation_restitution_unit_valid BEFORE INSERT OR UPDATE ON inventory_restitution_unit FOR EACH ROW EXECUTE FUNCTION check_cancellation_restitution_unit();
