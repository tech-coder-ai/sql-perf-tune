-- =====================================================================
-- SQL Performance & Tuning (SPT) - ORACLE
-- The Command center metrics as plain SQL, for checking the screen against the database.
--
-- Each query mirrors InsightsService (backend/src/main/java/.../insights/InsightsService.java).
-- They use today's date: TRUNC(SYSDATE) = midnight today on the database server.
-- For another day replace every TRUNC(SYSDATE) with a literal, e.g. DATE '2026-10-07'.
-- If the database runs in UTC but the logs are in local time, use the literal form.
--
-- A bad query is one row of SPT_QUERY_LOG, dated by START_TIME (load time when START_TIME is empty).
-- Differences from the screen by design: query 1 sums run time only for rows that belong to a pattern
-- (as the app does); queries 7 and 8 omit hours / days without rows (the app shows them as 0).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Bad queries today (Q1), previous day, change % and run time
-- ---------------------------------------------------------------------
SELECT bad_today,
       bad_prev_day,
       ROUND((bad_today - bad_prev_day) * 100 / NULLIF(bad_prev_day, 0), 1) AS change_pct,
       ROUND(run_minutes_today / 60, 1)                                    AS run_time_hours
FROM (
  SELECT SUM(CASE WHEN ts >= TRUNC(SYSDATE) THEN 1 ELSE 0 END)                AS bad_today,
         SUM(CASE WHEN ts <  TRUNC(SYSDATE) THEN 1 ELSE 0 END)                AS bad_prev_day,
         SUM(CASE WHEN ts >= TRUNC(SYSDATE) AND GROUP_ID IS NOT NULL
                  THEN DURATION_MINUTES END)                                  AS run_minutes_today
  FROM (SELECT COALESCE(START_TIME, CREATED_AT) AS ts, GROUP_ID, DURATION_MINUTES FROM SPT_QUERY_LOG)
  WHERE ts >= TRUNC(SYSDATE) - 1 AND ts < TRUNC(SYSDATE) + 1
);

-- ---------------------------------------------------------------------
-- 2. Recurring (Q2): today's rows whose pattern had already run on an earlier day
-- ---------------------------------------------------------------------
SELECT COUNT(*)                   AS recurring_queries,
       COUNT(DISTINCT l.GROUP_ID) AS recurring_patterns
FROM   SPT_QUERY_LOG l
JOIN   SPT_QUERY_GROUP g ON g.ID = l.GROUP_ID
WHERE  COALESCE(l.START_TIME, l.CREATED_AT) >= TRUNC(SYSDATE)
AND    COALESCE(l.START_TIME, l.CREATED_AT) <  TRUNC(SYSDATE) + 1
AND    g.FIRST_SEEN < TRUNC(SYSDATE);

-- ---------------------------------------------------------------------
-- 3. Query patterns (Q3): patterns that ran today, new ones, ones that ran more than once
-- ---------------------------------------------------------------------
SELECT COUNT(*)                                                                               AS patterns,
       SUM(CASE WHEN g.FIRST_SEEN IS NULL OR g.FIRST_SEEN >= TRUNC(SYSDATE) THEN 1 ELSE 0 END) AS new_patterns,
       SUM(CASE WHEN d.cnt > 1 THEN 1 ELSE 0 END)                                             AS ran_more_than_once
FROM  (SELECT GROUP_ID, COUNT(*) AS cnt
       FROM   SPT_QUERY_LOG
       WHERE  COALESCE(START_TIME, CREATED_AT) >= TRUNC(SYSDATE)
       AND    COALESCE(START_TIME, CREATED_AT) <  TRUNC(SYSDATE) + 1
       AND    GROUP_ID IS NOT NULL
       GROUP BY GROUP_ID) d
LEFT JOIN SPT_QUERY_GROUP g ON g.ID = d.GROUP_ID;

-- ---------------------------------------------------------------------
-- 4a. Outstanding patterns (Q6), active: not adopted, with bad queries in the 30 days up to today.
--     Also the "Not tracked yet" bar of the tuning pipeline.
-- ---------------------------------------------------------------------
WITH recent AS (
  SELECT GROUP_ID, COUNT(*) AS cnt
  FROM   SPT_QUERY_LOG
  WHERE  COALESCE(START_TIME, CREATED_AT) >= TRUNC(SYSDATE) + 1 - 30
  AND    COALESCE(START_TIME, CREATED_AT) <  TRUNC(SYSDATE) + 1
  AND    GROUP_ID IS NOT NULL
  GROUP BY GROUP_ID)
SELECT COUNT(*)                                                                      AS outstanding_patterns,
       SUM(r.cnt)                                                                    AS outstanding_bad_queries,
       SUM(CASE WHEN t.ID IS NULL THEN 1 ELSE 0 END)                                 AS not_tracked_yet,
       SUM(CASE WHEN t.WORKFLOW_STATUS = 'SME_VALIDATION' THEN 1 ELSE 0 END)          AS awaiting_adoption,
       SUM(CASE WHEN t.WORKFLOW_STATUS IN ('ON_HOLD', 'REJECTED') THEN 1 ELSE 0 END)  AS on_hold_or_rejected,
       SUM(CASE WHEN t.ID IS NOT NULL
                 AND t.WORKFLOW_STATUS NOT IN ('SME_VALIDATION', 'ON_HOLD', 'REJECTED') THEN 1 ELSE 0 END) AS in_progress
FROM   recent r
JOIN   SPT_QUERY_GROUP g ON g.ID = r.GROUP_ID AND g.GROUP_SIZE > 0
LEFT JOIN SPT_TUNING_TRACKER t ON t.GROUP_ID = g.ID
WHERE  t.ID IS NULL OR t.WORKFLOW_STATUS <> 'ADOPTED';

-- ---------------------------------------------------------------------
-- 4b. Outstanding patterns (Q6), all-time (the "N all-time" figure under the tile)
-- ---------------------------------------------------------------------
SELECT COUNT(*)                                      AS all_time_patterns,
       SUM(g.GROUP_SIZE)                             AS all_time_bad_queries,
       SUM(CASE WHEN t.ID IS NULL THEN 1 ELSE 0 END) AS all_time_not_tracked
FROM   SPT_QUERY_GROUP g
LEFT JOIN SPT_TUNING_TRACKER t ON t.GROUP_ID = g.ID
WHERE  g.GROUP_SIZE > 0
AND   (t.ID IS NULL OR t.WORKFLOW_STATUS <> 'ADOPTED');

-- ---------------------------------------------------------------------
-- 5. Awaiting adoption (Q15) and rejections in the last 90 days
-- ---------------------------------------------------------------------
SELECT (SELECT COUNT(*) FROM SPT_TUNING_TRACKER WHERE WORKFLOW_STATUS = 'SME_VALIDATION') AS awaiting_adoption,
       (SELECT COUNT(*) FROM SPT_FEEDBACK
        WHERE  DECISION = 'REJECTED'
        AND    CREATED_AT >= TRUNC(SYSDATE) - 89
        AND    CREATED_AT <  TRUNC(SYSDATE) + 1)                                         AS rejections_90_days
FROM dual;

-- ---------------------------------------------------------------------
-- 6. Saved this month (Q5): saving per run x runs per day in the 30 days before adoption
--    x days adopted within this month
-- ---------------------------------------------------------------------
WITH a AS (
  SELECT ID, GROUP_ID,
         CAST(ADOPTED_AT AS DATE)                            AS adopted_at,
         OG_RUN_DURATION_MINUTES - POST_RUN_DURATION_MINUTES AS saved_per_run_min
  FROM   SPT_TUNING_TRACKER
  WHERE  ADOPTED_AT IS NOT NULL),
r AS (
  SELECT a.ID, COUNT(l.ID) / 30 AS runs_per_day_before
  FROM   a
  LEFT JOIN SPT_QUERY_LOG l
         ON l.GROUP_ID = a.GROUP_ID
        AND COALESCE(l.START_TIME, l.CREATED_AT) >= TRUNC(a.adopted_at) - 30
        AND COALESCE(l.START_TIME, l.CREATED_AT) <  TRUNC(a.adopted_at)
  GROUP BY a.ID)
SELECT SUM(CASE WHEN a.adopted_at >= TRUNC(SYSDATE, 'MM') THEN 1 ELSE 0 END) AS adopted_this_month,
       COUNT(*)                                                             AS adopted_total,
       ROUND(SUM(NVL(a.saved_per_run_min, 0) * r.runs_per_day_before
                 * GREATEST(0, SYSDATE - GREATEST(a.adopted_at, TRUNC(SYSDATE, 'MM')))), 1)        AS minutes_saved_this_month,
       ROUND(SUM(NVL(a.saved_per_run_min, 0) * r.runs_per_day_before
                 * GREATEST(0, SYSDATE - GREATEST(a.adopted_at, TRUNC(SYSDATE, 'MM')))) / 1440, 1) AS days_saved_this_month
FROM   a JOIN r ON r.ID = a.ID;

-- ---------------------------------------------------------------------
-- 7. Bad queries by hour (Q8)
-- ---------------------------------------------------------------------
SELECT TO_CHAR(COALESCE(START_TIME, CREATED_AT), 'HH24') AS hour,
       COUNT(*)                                          AS bad_queries
FROM   SPT_QUERY_LOG
WHERE  COALESCE(START_TIME, CREATED_AT) >= TRUNC(SYSDATE)
AND    COALESCE(START_TIME, CREATED_AT) <  TRUNC(SYSDATE) + 1
GROUP BY TO_CHAR(COALESCE(START_TIME, CREATED_AT), 'HH24')
ORDER BY hour;

-- ---------------------------------------------------------------------
-- 8. Last 14 days
-- ---------------------------------------------------------------------
SELECT TRUNC(COALESCE(START_TIME, CREATED_AT)) AS day,
       COUNT(*)                                AS bad_queries
FROM   SPT_QUERY_LOG
WHERE  COALESCE(START_TIME, CREATED_AT) >= TRUNC(SYSDATE) - 13
AND    COALESCE(START_TIME, CREATED_AT) <  TRUNC(SYSDATE) + 1
GROUP BY TRUNC(COALESCE(START_TIME, CREATED_AT))
ORDER BY day;

-- ---------------------------------------------------------------------
-- 9. Tuning pipeline: items per stage ("Not tracked yet" = not_tracked_yet from query 4a)
-- ---------------------------------------------------------------------
SELECT WORKFLOW_STATUS, COUNT(*) AS items
FROM   SPT_TUNING_TRACKER
GROUP BY WORKFLOW_STATUS
ORDER BY DECODE(WORKFLOW_STATUS, 'NEW', 1, 'DIAGNOSTICS_CAPTURED', 2, 'OPTIMIZATION_REQUESTED', 3,
                'OPTIMIZED', 4, 'POST_RUN_VALIDATED', 5, 'SME_VALIDATION', 6, 'ADOPTED', 7,
                'ON_HOLD', 8, 'REJECTED', 9);

-- ---------------------------------------------------------------------
-- 10. Top patterns of the day
-- ---------------------------------------------------------------------
SELECT d.GROUP_ID,
       d.cnt                                                         AS bad_queries_today,
       g.GROUP_SIZE                                                  AS total_instances,
       CASE WHEN g.FIRST_SEEN < TRUNC(SYSDATE) THEN 'Y' ELSE 'N' END AS recurring,
       t.ID                                                          AS tracker_id,
       t.WORKFLOW_STATUS,
       t.THEME,
       DBMS_LOB.SUBSTR(g.SAMPLE_QUERY, 120, 1)                       AS sample_sql
FROM  (SELECT GROUP_ID, COUNT(*) AS cnt
       FROM   SPT_QUERY_LOG
       WHERE  COALESCE(START_TIME, CREATED_AT) >= TRUNC(SYSDATE)
       AND    COALESCE(START_TIME, CREATED_AT) <  TRUNC(SYSDATE) + 1
       AND    GROUP_ID IS NOT NULL
       GROUP BY GROUP_ID) d
JOIN   SPT_QUERY_GROUP g ON g.ID = d.GROUP_ID
LEFT JOIN SPT_TUNING_TRACKER t ON t.GROUP_ID = g.ID
ORDER BY d.cnt DESC
FETCH FIRST 10 ROWS ONLY;
