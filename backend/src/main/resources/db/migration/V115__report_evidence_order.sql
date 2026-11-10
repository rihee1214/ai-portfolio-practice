-- V113 inferred old evidence versions from an event. A version issued by the
-- locked evidence writer cannot also belong to an event in the same case.
CREATE VIEW operations_report_ordered_evidence AS
SELECT e.report_id,e.request_key,e.reason,e.asset_id,e.outcome,e.received_at,
       CASE WHEN v.version IS NULL THEN e.case_version ELSE 0 END AS case_version
FROM operations_report_evidence e
LEFT JOIN operations_report_event v ON v.report_id=e.report_id AND v.version=e.case_version;
