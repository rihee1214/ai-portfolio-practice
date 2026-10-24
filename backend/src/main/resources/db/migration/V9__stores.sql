CREATE TABLE seller_store (
 id uuid PRIMARY KEY,
 owner_id uuid NOT NULL UNIQUE REFERENCES identity_user(id),
 slug varchar(63) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$'),
 name varchar(80) NOT NULL CHECK(length(btrim(name))>0),
 introduction varchar(1000) NOT NULL CHECK(length(btrim(introduction))>0),
 address varchar(200) NOT NULL CHECK(length(btrim(address))>0),
 notice varchar(1000) NOT NULL,
 image_key varchar(100) NOT NULL,
 image_alt varchar(120) NOT NULL,
 visible boolean NOT NULL DEFAULT false,
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 CHECK ((image_key='' AND image_alt='') OR (image_key ~ '^[A-Za-z0-9_-]{1,100}$' AND length(btrim(image_alt))>0))
);
