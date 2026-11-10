CREATE TABLE content_review (
    id uuid PRIMARY KEY,
    order_item_id uuid NOT NULL UNIQUE REFERENCES order_item(id),
    member_id uuid NOT NULL REFERENCES identity_user(id),
    product_id uuid NOT NULL,
    store_id uuid NOT NULL,
    rating smallint NOT NULL CHECK (rating BETWEEN 1 AND 5),
    body varchar(2000) NOT NULL CHECK (length(trim(body)) > 0),
    author_deleted boolean NOT NULL DEFAULT false,
    hidden_by_admin boolean NOT NULL DEFAULT false,
    edition bigint NOT NULL DEFAULT 1 CHECK (edition > 0),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0)
);
CREATE INDEX content_review_public_product ON content_review(product_id, id)
    WHERE NOT author_deleted AND NOT hidden_by_admin;
CREATE TABLE media_review_slot (
    review_id uuid NOT NULL,
    edition bigint NOT NULL CHECK(edition>0),
    slot integer NOT NULL CHECK(slot BETWEEN 0 AND 4),
    asset_id uuid NOT NULL UNIQUE REFERENCES media_asset(id),
    PRIMARY KEY(review_id,edition,slot)
);
CREATE TABLE content_review_command (
    member_id uuid NOT NULL,
    order_item_id uuid NOT NULL,
    request_key uuid NOT NULL,
    action varchar(12) NOT NULL,
    payload jsonb NOT NULL,
    review_id uuid NOT NULL REFERENCES content_review(id),
    result_version bigint NOT NULL,
    PRIMARY KEY(member_id, order_item_id, action, request_key)
);
CREATE TABLE content_review_response (
    id uuid PRIMARY KEY,
    review_id uuid NOT NULL REFERENCES content_review(id),
    edition bigint NOT NULL CHECK (edition > 0),
    member_id uuid NOT NULL REFERENCES identity_user(id),
    kind varchar(12) NOT NULL CHECK (kind IN ('COMMENT','SELLER_REPLY')),
    body text NOT NULL CHECK (length(trim(body)) > 0 AND length(body) <= CASE kind WHEN 'COMMENT' THEN 500 ELSE 1000 END),
    author_deleted boolean NOT NULL DEFAULT false,
    hidden_by_admin boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0)
);
CREATE UNIQUE INDEX content_one_seller_reply ON content_review_response(review_id,edition)
    WHERE kind='SELLER_REPLY' AND NOT author_deleted;
CREATE TABLE content_response_command (
    review_id uuid NOT NULL REFERENCES content_review(id),
    member_id uuid NOT NULL REFERENCES identity_user(id),
    request_key uuid NOT NULL,
    payload jsonb NOT NULL,
    response_id uuid NOT NULL REFERENCES content_review_response(id),
    result_version bigint NOT NULL,
    PRIMARY KEY(review_id,member_id,request_key)
);
