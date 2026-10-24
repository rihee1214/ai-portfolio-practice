CREATE TABLE catalog_sku (
 id uuid PRIMARY KEY,
 product_id uuid NOT NULL REFERENCES catalog_product(id),
 code varchar(40) NOT NULL UNIQUE,
 options jsonb NOT NULL DEFAULT '[]' CHECK(jsonb_typeof(options)='array' AND jsonb_array_length(options)<=3),
 price bigint NOT NULL DEFAULT 0 CHECK(price BETWEEN 0 AND 999999999),
 active boolean NOT NULL DEFAULT false,
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(product_id,options)
);
INSERT INTO catalog_sku(id,product_id,code) SELECT id,id,'sku-'||id::text FROM catalog_product;
