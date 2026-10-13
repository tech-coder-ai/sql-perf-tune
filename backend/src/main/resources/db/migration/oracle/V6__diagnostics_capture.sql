-- =====================================================================
-- V6: automatic diagnostics capture
--   When a group is put on the tracker, the app fetches the explain plan, query profile and table DDL
--   from the SQL engine in the background. These columns record where that fetch stands so the screens
--   can say "in progress" instead of showing empty diagnostics.
--   CAPTURE_STATUS: PENDING (queued), RUNNING, DONE, FAILED; NULL for items tracked before V6.
-- =====================================================================
ALTER TABLE SPT_TUNING_TRACKER ADD (
    CAPTURE_STATUS        VARCHAR2(20),
    CAPTURE_REQUESTED_AT  TIMESTAMP,
    CAPTURE_FINISHED_AT   TIMESTAMP,
    CAPTURE_MESSAGE       VARCHAR2(1000),
    CONSTRAINT CK_SPT_TRACKER_CAPTURE CHECK (CAPTURE_STATUS IN ('PENDING', 'RUNNING', 'DONE', 'FAILED'))
);
COMMENT ON COLUMN SPT_TUNING_TRACKER.CAPTURE_STATUS IS 'Automatic explain / profile / DDL fetch: PENDING, RUNNING, DONE, FAILED.';
