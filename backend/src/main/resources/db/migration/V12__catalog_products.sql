CREATE TABLE catalog_product (
    id uuid PRIMARY KEY,
    store_id uuid NOT NULL REFERENCES seller_store(id),
    category_id uuid NOT NULL REFERENCES catalog_category(id),
    slug varchar(63) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$'),
    title varchar(120) NOT NULL CHECK (length(btrim(title)) > 0),
    description varchar(4000) NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('DRAFT','ACTIVE','STOPPED','ARCHIVED')),
    is_hidden_by_admin boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0)
);
CREATE INDEX catalog_product_store_idx ON catalog_product(store_id,id);
CREATE INDEX catalog_product_public_idx ON catalog_product(category_id,id) WHERE state='ACTIVE' AND NOT is_hidden_by_admin;
