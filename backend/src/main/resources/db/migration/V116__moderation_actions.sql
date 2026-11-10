CREATE TABLE operations_legacy_product_hide (
    product_id uuid PRIMARY KEY
);
INSERT INTO operations_legacy_product_hide(product_id)
SELECT id FROM catalog_product WHERE is_hidden_by_admin;

CREATE TABLE operations_moderation_action (
    id uuid PRIMARY KEY,
    actor uuid NOT NULL REFERENCES identity_user(id),
    request_key uuid NOT NULL,
    report_id uuid REFERENCES operations_report(id),
    close_version bigint,
    case_version bigint,
    kind text NOT NULL CHECK(kind IN ('PRODUCT','REVIEW','COMMENT','SELLER_REPLY')),
    target_id uuid NOT NULL,
    parent_id uuid,
    edition bigint NOT NULL CHECK(edition>=0),
    target_version bigint NOT NULL CHECK(target_version>=0),
    before_hidden boolean NOT NULL,
    after_hidden boolean NOT NULL CHECK(after_hidden<>before_hidden),
    source_hide uuid UNIQUE REFERENCES operations_moderation_action(id),
    reason text NOT NULL CHECK(length(trim(reason))>0),
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(actor,request_key),
    CHECK ((report_id IS NULL)=(close_version IS NULL) AND (report_id IS NULL)=(case_version IS NULL)),
    CHECK (report_id IS NOT NULL OR (kind='PRODUCT' AND NOT after_hidden AND source_hide IS NULL)),
    CHECK (source_hide IS NULL OR NOT after_hidden),
    FOREIGN KEY(report_id,close_version) REFERENCES operations_report_event(report_id,version)
);
CREATE INDEX operations_moderation_target ON operations_moderation_action(kind,target_id,target_version DESC);
CREATE TRIGGER moderation_action_immutable BEFORE UPDATE OR DELETE ON operations_moderation_action
FOR EACH ROW EXECUTE FUNCTION protect_report_history();
