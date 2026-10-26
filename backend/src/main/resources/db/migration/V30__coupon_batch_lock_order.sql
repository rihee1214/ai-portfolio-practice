-- Batch identity is immutable metadata. The reservation's member FK is checked
-- only after the service acquires all account locks in canonical order.
ALTER TABLE coupon_reservation_batch DROP CONSTRAINT coupon_reservation_batch_member_id_fkey;
ALTER TABLE coupon_reservation_batch ADD CONSTRAINT coupon_batch_identity UNIQUE(checkout_id,member_id,request_hash);
ALTER TABLE coupon_reservation DROP CONSTRAINT coupon_reservation_batch_fk;
ALTER TABLE coupon_reservation ADD CONSTRAINT coupon_reservation_batch_fk
 FOREIGN KEY(checkout_id,member_id,request_hash) REFERENCES coupon_reservation_batch(checkout_id,member_id,request_hash);
