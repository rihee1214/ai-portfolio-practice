ALTER TABLE catalog_product ADD COLUMN description_blocks jsonb
    CHECK (description_blocks IS NULL OR jsonb_typeof(description_blocks) = 'array');

CREATE TABLE media_product_description (
    asset_id uuid PRIMARY KEY REFERENCES media_asset(id),
    product_id uuid NOT NULL REFERENCES catalog_product(id)
);

CREATE INDEX media_product_description_product ON media_product_description(product_id);

-- A retained application can still save the plain description after rollback.
CREATE FUNCTION retire_legacy_product_description() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, marketplace AS $$
BEGIN
    IF OLD.description_blocks IS NOT NULL
        AND NEW.description IS DISTINCT FROM OLD.description
        AND NEW.description_blocks IS NOT DISTINCT FROM OLD.description_blocks THEN
        WITH detached AS (
            DELETE FROM marketplace.media_product_description WHERE product_id = OLD.id RETURNING asset_id
        )
        UPDATE marketplace.media_asset SET retired_at = clock_timestamp()
        WHERE id IN (SELECT asset_id FROM detached);
        NEW.description_blocks := NULL;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER retire_legacy_product_description
    BEFORE UPDATE OF description ON catalog_product
    FOR EACH ROW EXECUTE FUNCTION retire_legacy_product_description();
