CREATE TABLE notification_event (
    id uuid PRIMARY KEY,
    kind text NOT NULL CHECK (kind IN ('PAYMENT_BUYER','PAYMENT_SELLER','PURCHASE_CONFIRMED','SHIPPED',
        'EXCHANGE_SHIPPED','PRODUCT_ANSWER','CASE_REPLY','MODERATION_ACTION','MODERATION_RESTORED','REPORT_CLOSED')),
    cause_id uuid NOT NULL,
    recipient_id uuid NOT NULL REFERENCES identity_user(id),
    target_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (kind,cause_id,recipient_id)
);

CREATE TABLE notification_inbox (
    event_id uuid PRIMARY KEY REFERENCES notification_event(id),
    read_at timestamptz
);

CREATE INDEX notification_event_recipient_idx ON notification_event(recipient_id,created_at DESC,id DESC);
