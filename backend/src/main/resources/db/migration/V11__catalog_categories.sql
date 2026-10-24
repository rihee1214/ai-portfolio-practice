CREATE TABLE catalog_category (
    id uuid PRIMARY KEY,
    name varchar(80) NOT NULL UNIQUE CHECK (length(btrim(name)) > 0),
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0)
);
