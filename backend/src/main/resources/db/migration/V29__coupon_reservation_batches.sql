DO $$ BEGIN
 IF EXISTS(SELECT checkout_id FROM coupon_reservation GROUP BY checkout_id HAVING count(DISTINCT member_id)>1 OR count(DISTINCT request_hash)>1) THEN
  RAISE EXCEPTION 'Conflicting coupon reservation batches require explicit reconciliation';
 END IF;
END $$;
CREATE TABLE coupon_reservation_batch (
 checkout_id uuid PRIMARY KEY, member_id uuid NOT NULL REFERENCES identity_user(id), request_hash varchar(64) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO coupon_reservation_batch(checkout_id,member_id,request_hash)
 SELECT checkout_id,min(member_id::text)::uuid,min(request_hash) FROM coupon_reservation GROUP BY checkout_id;
ALTER TABLE coupon_reservation ADD CONSTRAINT coupon_reservation_batch_fk FOREIGN KEY(checkout_id) REFERENCES coupon_reservation_batch(checkout_id);
