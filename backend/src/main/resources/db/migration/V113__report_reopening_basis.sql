ALTER TABLE operations_report_evidence ADD COLUMN case_version bigint NOT NULL DEFAULT 0 CHECK(case_version>=0);
UPDATE operations_report_evidence e SET case_version=COALESCE((
 SELECT max(v.version) FROM operations_report_event v WHERE v.report_id=e.report_id AND v.occurred_at<=e.received_at
),0);
ALTER TABLE operations_report_evidence ADD UNIQUE(report_id,request_key);
ALTER TABLE operations_report_event ADD COLUMN reopen_basis text CHECK(reopen_basis IN ('NEW_EVIDENCE','JUDGMENT_ERROR'));
ALTER TABLE operations_report_event ADD COLUMN evidence_key uuid;
ALTER TABLE operations_report_event ADD FOREIGN KEY(report_id,evidence_key) REFERENCES operations_report_evidence(report_id,request_key);
ALTER TABLE operations_report_event ADD CHECK(evidence_key IS NULL OR (command='REOPEN' AND reopen_basis='NEW_EVIDENCE'));
