ALTER TABLE guest_attempt ADD COLUMN link_mode varchar(8) NOT NULL DEFAULT 'NEW' CHECK (link_mode IN ('NEW','EXISTING'));
ALTER TABLE guest_attempt ADD COLUMN link_session_hash varchar(64) REFERENCES guest_access_session(token_hash);
ALTER TABLE guest_attempt ADD COLUMN link_revision integer NOT NULL DEFAULT 0 CHECK (link_revision >= 0);
ALTER TABLE guest_attempt ADD CONSTRAINT guest_link_mode_binding CHECK (
 (link_mode='NEW' AND link_session_hash IS NULL) OR
 (link_mode='EXISTING' AND link_session_hash IS NOT NULL AND prepared_password_hash IS NULL)
);
