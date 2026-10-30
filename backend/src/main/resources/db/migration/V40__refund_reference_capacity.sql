-- Preserve V38/V39 history and align persisted outcome with the trusted 200-character refund reference contract.
ALTER TABLE order_unit_hold ALTER COLUMN outcome TYPE varchar(200);
