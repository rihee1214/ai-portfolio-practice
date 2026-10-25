-- Existing products share the migration timestamp; UUID resolves ties deterministically.
ALTER TABLE catalog_product ADD COLUMN created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP;

