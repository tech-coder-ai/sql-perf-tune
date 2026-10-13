-- =====================================================================
-- V6 (SQLite twin of oracle/V6): automatic diagnostics capture status on the tracker
-- =====================================================================
ALTER TABLE SPT_TUNING_TRACKER ADD COLUMN CAPTURE_STATUS TEXT CHECK (CAPTURE_STATUS IN ('PENDING', 'RUNNING', 'DONE', 'FAILED'));
ALTER TABLE SPT_TUNING_TRACKER ADD COLUMN CAPTURE_REQUESTED_AT TIMESTAMP;
ALTER TABLE SPT_TUNING_TRACKER ADD COLUMN CAPTURE_FINISHED_AT TIMESTAMP;
ALTER TABLE SPT_TUNING_TRACKER ADD COLUMN CAPTURE_MESSAGE TEXT;
