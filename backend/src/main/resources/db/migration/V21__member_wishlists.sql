CREATE TABLE wishlist (
 user_id uuid NOT NULL REFERENCES identity_user(id),
 product_id uuid NOT NULL,
 PRIMARY KEY(user_id,product_id)
);
-- Retain unavailable product identifiers so members can remove their saved entries.
