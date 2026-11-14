SET search_path TO marketplace,public;

CREATE TABLE site_shipping_policy (
    version bigint PRIMARY KEY CHECK (version > 0),
    base_fee bigint NOT NULL CHECK (base_fee BETWEEN 0 AND 2147483647),
    free_threshold bigint NOT NULL CHECK (free_threshold BETWEEN 0 AND 2147483647),
    actor_id uuid,
    request_key uuid,
    reason text NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 500),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (actor_id,request_key),
    CHECK ((version=1 AND actor_id IS NULL AND request_key IS NULL AND base_fee=3000 AND free_threshold=50000)
        OR (version>1 AND actor_id IS NOT NULL AND request_key IS NOT NULL))
);
CREATE TABLE site_shipping_head (
    singleton boolean PRIMARY KEY CHECK (singleton),
    version bigint NOT NULL REFERENCES site_shipping_policy(version)
);
INSERT INTO site_shipping_policy(version,base_fee,free_threshold,reason) VALUES (1,3000,50000,'P07-C-1');
INSERT INTO site_shipping_head VALUES (true,1);
CREATE FUNCTION site_keep_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'site history is immutable' USING ERRCODE='23514';
END $$;
CREATE TRIGGER site_shipping_policy_immutable BEFORE UPDATE OR DELETE ON site_shipping_policy
FOR EACH ROW EXECUTE FUNCTION site_keep_history();
