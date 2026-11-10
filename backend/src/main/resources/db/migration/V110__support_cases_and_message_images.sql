CREATE TABLE operations_support_case (
    id uuid PRIMARY KEY,
    kind varchar(20) NOT NULL CHECK(kind IN ('SUPPORT','ORDER','LOST_ACCESS')),
    member_id uuid REFERENCES identity_user(id),
    guest_id uuid,
    order_number text,
    seller_order_id uuid,
    request_key uuid NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK(version>0),
    CHECK((kind='LOST_ACCESS' AND member_id IS NULL AND guest_id IS NULL)
       OR (kind<>'LOST_ACCESS' AND ((member_id IS NULL) <> (guest_id IS NULL)))),
    CHECK((kind='ORDER' AND order_number IS NOT NULL AND seller_order_id IS NOT NULL)
       OR (kind<>'ORDER' AND order_number IS NULL AND seller_order_id IS NULL))
);
CREATE UNIQUE INDEX operations_case_member_key ON operations_support_case(member_id,request_key) WHERE member_id IS NOT NULL;
CREATE UNIQUE INDEX operations_case_guest_key ON operations_support_case(guest_id,request_key) WHERE guest_id IS NOT NULL;
CREATE UNIQUE INDEX operations_case_loss_key ON operations_support_case(request_key) WHERE kind='LOST_ACCESS';
CREATE TABLE operations_case_message (
    id uuid PRIMARY KEY,
    case_id uuid NOT NULL REFERENCES operations_support_case(id),
    version bigint NOT NULL,
    sender text NOT NULL CHECK(sender IN ('CUSTOMER','SELLER','OPERATOR')),
    member_id uuid REFERENCES identity_user(id),
    guest_id uuid,
    request_key uuid NOT NULL,
    body varchar(2000) NOT NULL CHECK(length(trim(body))>0),
    UNIQUE(case_id,version)
);
CREATE UNIQUE INDEX operations_msg_member_key ON operations_case_message(case_id,member_id,request_key) WHERE member_id IS NOT NULL;
CREATE UNIQUE INDEX operations_msg_guest_key ON operations_case_message(case_id,guest_id,request_key) WHERE guest_id IS NOT NULL;

ALTER TABLE media_asset ADD COLUMN inquiry_guest_id uuid;
ALTER TABLE media_asset ALTER COLUMN owner_id DROP NOT NULL;
ALTER TABLE media_asset ADD CONSTRAINT media_asset_single_uploader CHECK ((owner_id IS NULL) <> (inquiry_guest_id IS NULL));
CREATE TABLE media_message_slot (
    kind varchar(24) NOT NULL CHECK(kind IN ('PRODUCT_QUESTION','PRODUCT_ANSWER','CASE_MESSAGE')),
    message_id uuid NOT NULL,
    slot integer NOT NULL CHECK(slot BETWEEN 0 AND 2),
    asset_id uuid NOT NULL UNIQUE REFERENCES media_asset(id),
    PRIMARY KEY(kind,message_id,slot)
);
CREATE TABLE media_message_receipt (
    kind varchar(24) NOT NULL,
    message_id uuid NOT NULL,
    request_key uuid NOT NULL,
    fingerprint text NOT NULL,
    result_version bigint NOT NULL,
    asset_id uuid,
    PRIMARY KEY(kind,message_id,request_key)
);
