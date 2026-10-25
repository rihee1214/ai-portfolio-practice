CREATE TABLE media_namespace(singleton boolean PRIMARY KEY CHECK(singleton), namespace uuid NOT NULL);
INSERT INTO media_namespace VALUES(true,gen_random_uuid());
CREATE TABLE media_asset (
 id uuid PRIMARY KEY,
 owner_id uuid NOT NULL REFERENCES identity_user(id),
 width integer NOT NULL CHECK(width BETWEEN 1 AND 4096),
 height integer NOT NULL CHECK(height BETWEEN 1 AND 4096),
 byte_size integer NOT NULL CHECK(byte_size BETWEEN 1 AND 5242880),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 retired_at timestamptz,
 CHECK(width::bigint*height<=16000000)
);
CREATE TABLE media_slot (
 kind varchar(8) NOT NULL CHECK(kind IN ('products','stores')),
 product_id uuid REFERENCES catalog_product(id),
 store_id uuid REFERENCES seller_store(id),
 target_id uuid GENERATED ALWAYS AS (coalesce(product_id,store_id)) STORED,
 slot integer NOT NULL CHECK(slot BETWEEN 0 AND 4),
 asset_id uuid UNIQUE REFERENCES media_asset(id),
 alt varchar(120) NOT NULL,
 version bigint NOT NULL CHECK(version>0),
 CHECK((kind='products' AND product_id IS NOT NULL AND store_id IS NULL) OR (kind='stores' AND store_id IS NOT NULL AND product_id IS NULL AND slot=0)),
 CHECK((asset_id IS NULL AND alt='') OR (asset_id IS NOT NULL AND length(btrim(alt))>0)),
 UNIQUE(kind,target_id,slot)
);
CREATE INDEX media_retired ON media_asset(retired_at) WHERE retired_at IS NOT NULL;
