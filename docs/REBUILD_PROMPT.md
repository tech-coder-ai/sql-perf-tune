# Rebuild prompt: SQL Performance & Tuning Workbench

Copy everything below the line into another LLM / coding agent (or hand it to a team) to rebuild this
application from scratch on another system. It describes the **complete target system**, including the
features developed after this file was added to `main` (insights, tuning iterations, pipeline board, Δ %
columns; see branch `sql-tuner-updated`). Adjust the stack versions in section 2 if your platform differs.

---

## 1. Goal

Build an **enterprise-ready SQL performance tuning workbench**. The first use case is **Cloudera Impala**:
find long-running ("bad") SQL in query logs, group equivalent statements, track the tuning work for each
pattern through several tuning iterations until the best rewrite is **adopted** by the users, and report on
the programme (bad queries per day, backlog, adoption, savings, turnaround, trends).

The process follows the *Impala SQL – Performance & Tuning – Tactical Workflow*:

| Step | Workflow box | What the app does |
|---|---|---|
| 1 | SQL execution by business users | source systems; the app ingests their query logs |
| 2 | Identify long-running SQL | import logs (CSV / Excel / JDBC pull) and group them |
| 3 | SQL DB | application schema (Oracle in shared environments, SQLite locally) |
| 4–6 | Retrieve bad SQL diagnostics: run, explain plan, query profile, exec summary; parse the profile | capture diagnostics per group (phase ORIGINAL), parse the Impala profile into metrics |
| 7 | Optimization prompts | versioned prompt templates |
| 8 | Optimization agent (gets bad SQL, DDL, row counts, explain, exec summary) | render the prompt; table DDL / row counts captured per group |
| 9 | Agent output: change narrative + optimized SQL | stored as a run and as a new **tuning iteration** |
| 10 | Run the new SQL (post-run diagnostics) | diagnostics phase OPTIMIZED for an iteration = its test results |
| 11 | Load output | before / after metrics on the tracker |
| 12 | Tuning diagnostic tool: inventory and adoption | tracker, pipeline board, insights, adoption / rejection feedback |
| Note | Failed optimization feedback | rejection with a reason; the item goes back to tuning |

## 2. Stack and non-functional requirements

- **Backend:** Spring Boot **4.1.x**, Java **21**, Spring Data JPA (Hibernate 7), Flyway, Jackson 3, springdoc
  OpenAPI (Swagger UI at `/swagger-ui.html`), Apache POI (Excel read / write), Apache Commons CSV, Lombok
  for entities only, actuator health probes. Port 8080.
- **Databases:**
  - **SQLite** for local development (file `backend/data/sql-perf-tune.db`, created on first start; use the
    Hibernate community `SQLiteDialect`).
  - **Oracle 19c+** for shared environments (profile `oracle`; credentials only from environment variables
    `SPT_DB_URL`, `SPT_DB_USER`, `SPT_DB_PASSWORD`, `SPT_DB_SCHEMA`).
  - Flyway owns the schema: `db/migration/sqlite` and `db/migration/oracle` with the **same version numbers**
    in both. Upper-case DDL, `SPT_` prefix, named constraints (`PK_`, `FK_`, `UK_`, `CK_`, `IX_`), identity
    columns, CLOB for long text.
  - Also deliver `db/oracle/00_create_schema.sql` (DBA: owner + app user + grants) and
    `db/oracle/01_drop_and_create_schema.sql` (drop every SPT table / view and Flyway history, re-create the
    full schema, load initial values; afterwards start once with Flyway baseline-on-migrate at the latest
    version).
- **Frontend:** Angular **22** (standalone components, signals, zoneless change detection: anything set from
  an async callback must be a signal), Angular Material 3, **AG Grid Enterprise 36** for every data grid
  (licence key served at runtime from `SPT_AG_GRID_LICENSE_KEY` via `GET /api/ui-config`, never committed;
  without it the grids run with a watermark). Dev server on 4200 proxying `/api` to 8080.
- **Security:** `spt.security.mode=NONE` locally; `prod` profile = OAuth2 resource server (JWT from
  `SPT_JWT_ISSUER_URI`); current user written to every `*_BY` audit column. CORS origins configurable.
- Optimistic locking (`VERSION` column, HTTP 409 on conflict) on editable entities; field-level audit trail.
- RFC 9457 problem details for errors. Paged list endpoints (`page`, `size`, `sort=property,dir`).
- Everything configurable under `spt.*` (fingerprint options, ingestion batch size, row identity mode,
  max rejected rows, JDBC timeout, AG Grid licence, CORS).

## 3. Query logs (input)

Columns (header names case / space / underscore insensitive; `useris` and `user` accepted for `user_id`):

`seq_id, executed_query, user_query, error_code, error_category, error_message, user_id, start_time, end_time, duration_minutes`

- At least one of `executed_query` / `user_query` is required; `duration_minutes` is derived from start / end
  when empty. Dates: ISO, `yyyy-MM-dd HH:mm:ss`, `MM/dd/yyyy HH:mm`, Excel dates.
- Sources: **CSV** (comma, semicolon, pipe or tab) or **Excel .xlsx** upload, or a **JDBC pull** from a
  configured data source (Oracle or Impala) whose SELECT aliases the standard columns and may use `:since`
  (newest start time already loaded) for incremental pulls. Passwords are never stored: the data source holds
  the *name* of an environment variable.
- The Logs screen shows these columns plus loads count, last seen, group link; filters: SQL text, user,
  error category, start from / to, min duration, errors only, re-loaded only.

### Load each row only once

Logs are loaded once or several times a day; files may overlap or be uploaded twice.

- **Row identity:** `ROW_KEY` = SHA-256 of engine + seq_id + user + start + end + executed SQL + user SQL
  (mode `CONTENT`, default) or engine + seq_id (mode `SEQ_ID`). Unique index.
- A row whose key already exists is **not** inserted, fingerprinted or regrouped again; the load only adds a
  **sighting** (`SPT_QUERY_LOG_SIGHTING`: log row × import, is-first flag) and increments `SEEN_COUNT` /
  `LAST_SEEN_AT` / `LAST_SEEN_BATCH_ID`.
- **Identical file:** SHA-256 of the file content in `SPT_SOURCE_FILE` with `LOAD_COUNT`. A repeated upload is
  not parsed; its import row is flagged `DUPLICATE_OF_BATCH_ID` with `FILE_LOAD_NUMBER` (nth load) and the
  sightings are copied. Administration shows loaded files with "times loaded".
- Each import (`SPT_INGESTION_BATCH`) records rows read / new / already loaded / rejected (with reasons) and
  groups affected.
- Imports run **in the background** (single-thread executor that also serializes imports) with progress
  committed per chunk, a **cancel** endpoint, and polling from the UI. Rows inserted before a cancel or a
  failure are **always grouped**. On start-up, imports still RUNNING become FAILED ("Interrupted") and any
  ungrouped rows are grouped.

## 4. Grouping (query patterns)

- **Fingerprint:** tokenize the SQL, drop comments, lower-case, **remove every WHERE clause at any depth**
  (up to GROUP BY / ORDER BY / HAVING / LIMIT / UNION / closing parenthesis), replace literals with `?`,
  collapse `IN (?, ?, …)` to `IN (?)`, drop trailing semicolons, SHA-256. Hash `executed_query`, falling back
  to `user_query` (configurable).
- `SPT_QUERY_GROUP` columns: `group_id, group_size, user_id(s) + distinct_users, duration_count,
  avg / min / max / total_duration_minutes, sample_query (the longest-running member), row_indices (member
  seq_ids), fingerprint`, plus normalized query, error count, first / last seen.
- Groups screen: AG Grid master/detail; expanding a group shows its log rows (paged). Filters: SQL text,
  user, min group size, min avg duration, tracked yes / no. "Add to tracker", "Rebuild" (re-fingerprint all).
- Drill **down** tracker → group → log rows and **up** log row → group → tracker everywhere.

## 5. Tuning tracker

One tracker item (`T-n`) per group being tuned (or per SQL registered by request). Group metrics are read
**live** from the group (never copied). Columns (keep this order, all available in the grid, Excel export
and the reporting view `SPT_TRACKER_V`):

`group_id, group_size, distinct_users, fingerprint, avg / min / max / total_duration_minutes,
sample_query_seq_id, sample_query_raw, sample_query_formatted, row_indices, cleansed_query, optimized_query,
Dev Team Lead, Dev Team Status, Cloudera Team Lead, SME Team Lead, Optimized SQL (status), Cloudera Post Run
Validation, OG / Post Run Duration Minutes, OG / Post Run Execution Time, OG / Post Run Teardown Time,
OG / Post Run Teardown %, Theme, SME Validation, Install, Execute, Validation, Changes, Problem,
Recommendations`

plus: stage, priority (LOW / MEDIUM / HIGH / CRITICAL), request source, requested by, environment, selected
iteration, OG / post-run CPU seconds, rows scanned, tables scanned, bytes scanned, peak memory MB, stage
changed at, adopted at, closed at, days in stage, days open, iteration count, audit columns, version.

- **Δ % for every before / after pair** (run duration, execution, teardown, CPU, rows scanned, tables scanned,
  peak memory; teardown share in percentage points): `(post − original) / original × 100`, negative = less.
- Teardown % is derived as teardown ÷ run duration when left empty. Cleansed query = comments / extra
  whitespace removed but still runnable; formatted query = pretty-printed.
- **Extensible columns:** runtime custom fields (`SPT_CUSTOM_FIELD(_VALUE)`: text, long text, number, date,
  boolean, enum; for tracker, group or log) appear in grid, form and export without a release. Real columns
  are added by migration (entity, DTO and update request share the property name; updates copy by name and
  audit every changed field).
- **Dropdowns:** every field with a fixed value set is a dropdown backed by `SPT_LOOKUP (category, value,
  sort order, tone ok / warn / bad / info / muted, active)`, editable in Administration and validated
  server-side (a deactivated value stays valid on old items). Initial values:

| Category | Values |
|---|---|
| THEME | Partition pruning, Trade all, Teardown, Join strategy, Missing statistics, Data skew, Wide scans / SELECT *, Spill to disk, Small files, Complex views, Other |
| DEV_TEAM_STATUS | Not started, In analysis, In progress, In review, Blocked, Done |
| OPTIMIZED_SQL_STATUS | Not started, In progress, Candidate ready, Validated, Not possible |
| CLOUDERA_POST_RUN_VALIDATION | Pending, Passed, Failed, Not required |
| SME_VALIDATION | Pending, Approved, Rejected, Not required |
| INSTALL_STATUS | Pending, Scheduled, Installed, Rolled back, Not applicable |
| EXECUTE_STATUS | Pending, Running, Succeeded, Failed, Not applicable |
| VALIDATION_STATUS | Pending, Passed, Failed, Not applicable |
| ENVIRONMENT | PROD, UAT, DEV |
| REJECTION_REASON | Inaccurate results, No performance gain, Business logic changed, Not feasible to deploy, Other |

### Stages (the SQL's journey until adoption)

| Status | UI label | Entered when |
|---|---|---|
| NEW | Triage | item created |
| DIAGNOSTICS_CAPTURED | Diagnosed | original diagnostics captured |
| OPTIMIZATION_REQUESTED | Tuning | agent run started, or an iteration was rejected |
| OPTIMIZED | Candidate ready | an iteration exists |
| POST_RUN_VALIDATED | Tested | an iteration has test results |
| SME_VALIDATION | Awaiting adoption | an iteration was selected |
| ADOPTED | Adopted | users adopted it |
| REJECTED / ON_HOLD | Rejected / On hold | closed without adoption / parked |

One service method changes the stage: it audits the change and maintains stage-changed / adopted / closed
timestamps. Automatic transitions only move forward. The journey (date reached and days spent per stage)
is rebuilt from the audit trail.

### Tuning iterations

Tuning takes several attempts; each is an iteration (`#1, #2, …` per item) with SQL, change narrative,
source (AI agent / manual) and test metrics (run minutes, execution / teardown seconds, CPU seconds, rows /
bytes / tables scanned, peak memory, result row count, **results match original** yes / no).

- Status: PROPOSED → TESTED (or FAILED) → SELECTED → ADOPTED or REJECTED.
- **Best iteration** = fastest tested iteration whose results match (ties broken by CPU).
- **Select** copies its SQL and metrics to the tracker (post-run columns) and moves the item to Awaiting
  adoption. **Adopt** closes the item as Adopted. **Reject an iteration** (reason required) marks it rejected
  and sends the item back to Tuning; rejecting without an iteration closes the item as Rejected.
- Feedback records role (business user, client dev, Cloudera, SME), decision, reason, comments.

### Diagnostics and the optimization agent

- Per group: explain plan, raw Impala profile, exec summary, row count, phase ORIGINAL or OPTIMIZED (+
  iteration). The **profile parser** extracts: run duration and timeline (execution = until last row fetched,
  teardown = last row fetched → unregister), CPU from *Per Node User / System Time*, rows / tables scanned and
  peak memory from the ExecSummary (SCAN HDFS operators), plus a compact JSON summary.
- Table DDL (`SHOW CREATE TABLE`) and row counts per table.
- Versioned prompt templates (saving creates a new active version). The agent is a port: the default
  implementation stores the fully rendered prompt as a PENDING run for an analyst to run in the approved LLM
  and paste back; a bean calling an LLM gateway can replace it. The answer must contain `### CHANGE NARRATIVE`
  and `### OPTIMIZED SQL` with a ```` ```sql ```` block. Default Impala prompt:

```
You are a senior Cloudera Impala performance engineer. Rewrite the SQL below so it returns exactly the same
result set while running faster and using fewer cluster resources.
Consider: partition pruning, predicate push-down, join order and join strategy (BROADCAST vs SHUFFLE),
missing or stale statistics (COMPUTE STATS), avoiding SELECT *, avoiding functions on partition columns,
replacing correlated sub-queries, reducing data skew, and spilling to disk.
## Bad SQL {{bad_sql}}   ## Table DDL {{ddl}}   ## Table row counts {{row_counts}}
## Explain plan {{explain}}   ## Parsed profile {{profile_summary}}   ## Execution summary {{exec_summary}}
Respond with two sections exactly: ### CHANGE NARRATIVE (numbered list of every change and why it helps)
and ### OPTIMIZED SQL (a single ```sql block with only the rewritten query).
```

- **Tuning requests:** register SQL that did not come from logs (source PROACTIVE_UAT or USER_REQUEST,
  requested by, environment, priority, theme); it is fingerprinted and joins or creates a group.
- **User directory:** user id → display name, **user group**, department (CSV upload
  `user_id,user_group[,display_name][,department]`), used to report bad queries by user group.

## 6. Insights: the 15 questions

Definitions: a **bad query** is one row of the injected log dated by its start time (load time if missing);
a **pattern** is a group; **recurring** = pattern seen on an earlier day; **optimized** = item at Candidate
ready or later; **outstanding** = pattern not adopted (untracked, in progress, on hold or rejected);
**estimated savings** = per-run saving of the adopted iteration × the pattern's runs per day in the 30 days
before adoption × days since adoption within the period (wall clock, Impala CPU, rows scanned, table scans).
Group by calendar day with a dialect-aware date function (do not rely on the SQLite dialect's `day()`).

| # | Question |
|---|---|
| Q1 | How many bad queries today (vs previous day, run time)? |
| Q2 | How many of them were recurring? |
| Q3 | How many instances per query pattern? |
| Q4 | Was the prior day's bad SQL optimized or not, and categorised by theme (e.g. Trade all, Teardown)? |
| Q5 | How many tuned SQL were adopted, and the week / month savings in Impala CPU, wall clock, table scans avoided, rows scanned reduced |
| Q6 | How many bad queries / patterns are outstanding at any time? |
| Q7 | Which user groups / users have the most bad queries? |
| Q8 | Which hour has the most bad queries? |
| Q9 | End-to-end turnaround (detection → adoption; average, median, p90, time per stage) |
| Q10 | Month-over-month trend of outstanding queries and patterns |
| Q11 | Positive impact after a project / theme (e.g. Trade all) is fixed and adopted (runs per day before vs after, saved time) |
| Q12 | Prioritised report of outstanding themes for the most savings |
| Q13 | Bad SQL by user; which users always have bad SQL (bad SQL on ≥ half the days of the period, ≥ 5 queries) |
| Q14 | Proactively tuned (UAT) vs user tuning requests vs detected in logs, per month |
| Q15 | Queries waiting for adoption (oldest first); optimizations rejected and why |

Endpoints: `/api/insights/daily?date`, `/pipeline?priorDay&from&to`, `/savings?from&to`, `/themes?days`,
`/users?from&to`, `/trends?months`. Compute on the fly from logs, groups, tracker, iterations, feedback and
audit events (no reporting tables needed at this scale).

## 7. Screens and UX

Professional enterprise look, **light-blue light theme and navy dark theme** (plus "follow system"), built on
Material 3 system tokens overridden with app tokens (`--spt-*`); define colours with CSS `light-dark()` (note:
it accepts colours only, so build gradients / shadows from colour tokens). Responsive down to phone width
(no horizontal page scroll). Flow-oriented: every screen tells the user the next step.

- **App bar:** blue gradient, product name, engine badge, **global search** (`T-12`, `#5` / `G-5`, seq id or
  SQL text), **New tuning request**, theme switch, API docs link.
- **Navigation** (collapsible to icons; drawer on phones): *Overview* – Command center, Insights; *Tuning* –
  Pipeline board, Tuning tracker; *Data* – Query groups, Query logs; *Settings* – Administration.
- **Command center** (start page): day picker; tiles for Q1, Q2, Q3, Q6, Q15, Q5 (each with its Q badge);
  bad queries by hour; pipeline counts per stage (click → board); top patterns; last 14 days.
- **Insights:** sticky controls (day; period 7 / 30 days, month to date, 90 days, custom; trend 6 / 12 / 24
  months; theme window), an index of the 15 questions grouped Today / Tuning outcomes / Backlog & priorities
  / Users / Trends, and one card per question with a one-line answer, a chart and a **table view** toggle.
- **Pipeline board:** kanban column per stage with count and average age; cards show id, priority, source,
  theme, SQL, runs, total time, iterations, lead, days in stage (amber / red when ageing); filters; option to
  show adopted / rejected; link to table view.
- **Tracker grid:** all columns; columns tool panel, drag / resize / pin, layout remembered per user, reset
  layout; server-side paging and sorting; filters (text, stage, priority, theme, lead); Excel export of all
  matching rows. **Original** (orange header), **post-implementation** (green header) and **Δ %** (violet
  header) columns side by side with a legend; Δ cells green when lower, red when higher. Statuses and dropdown
  values as **coloured pills**.
- **Tracker item page:** header with stage pill and stage-specific actions (Diagnostics & AI agent, Add
  iteration, Select best, Mark adopted, Reject); **journey stepper** (date reached, days per stage, skipped
  stages); **Next step** banner; tabs: Overview (before / after table with the same header colours and Δ %,
  facts), Iterations (original baseline row, each iteration with metrics, Δ %, results match, best ★,
  edit test results, select, reject), Tracking (form with dropdowns, save / discard, conflict handling,
  custom fields), Queries, Log rows, History.
- **Group page:** metrics, SQL, log rows, Diagnostics, DDL, Optimization (prompt template, run, paste answer
  → iteration), Feedback (adopt / reject with iteration, role, reason).
- **Logs and groups:** error code / category / message and error counts in **red** (light red cell).
- **Import dialog:** drop file or choose data source, background progress bar, cancel, result summary
  (new / already loaded / rejected / groups refreshed) and rejected-row reasons.
- **Administration:** Dropdown values, Users & groups (grid + CSV upload), Data sources (with test
  connection), Custom columns, Prompt templates (versions), Loaded files, Import history.
- **Charts:** small dependency-free SVG components (column, bar list, line, stat tile, chart card). Use a
  validated colour-blind-safe categorical palette (blue, orange, green), one axis only, hover tooltips,
  legends for ≥ 2 series, and a table view on every chart.

## 8. API sketch

`/api/logs` (+ `/{id}/history`), `/api/groups` (+ `/{id}`, `/{id}/logs`, `/rebuild`, `/{id}/diagnostics`,
`/{id}/ddls`, `/{id}/optimization-runs`, `/{id}/optimization-runs/{runId}/response`,
`/{id}/optimization-runs/{runId}/outcome`, `/{id}/feedback`),
`/api/tracker` (list, `POST` from groups, `/{id}`, `PUT /{id}`, `/{id}/history`, `/{id}/journey`, `/board`,
`/requests`, `/export`), `/api/tracker/{id}/iterations` (+ `PUT /{iterationId}`, `POST /{iterationId}/select`),
`/api/ingestion/upload`, `/pull/{dataSourceId}`, `/batches`, `/batches/{id}`, `/batches/{id}/cancel`, `/files`,
`/api/insights/*`, `/api/lookups`, `/api/users` (+ `/upload`), `/api/search?q=`, `/api/custom-fields`,
`/api/prompt-templates` (+ `/{id}/activate`), `/api/data-sources` (+ `/{id}/test`), `/api/dashboard`, `/api/ui-config`.

## 9. Deliverables and quality gates

- Source with backend tests (fingerprinting, profile parsing, agent answer parsing, end-to-end ingestion and
  de-duplication on SQLite, full tuning lifecycle and every insight, recovery of an interrupted import) and a
  frontend build within bundle budgets (load AG Grid Enterprise lazily if needed).
- Docs: README, user manual (every screen, the 15 questions with definitions, stages, iterations,
  dropdowns, imports, FAQ), developer guide (setup, layout, migrations, ingestion design, lifecycle,
  insights, lookups, AG Grid, charts, configuration, testing, deployment), architecture (workflow mapping, ER
  diagram, stage diagram, fingerprint rules).
- Samples: `samples/query_log_sample.csv` (one day), `samples/query_log_sample_day2.csv` (overlaps day 1 to
  show de-duplication), `samples/users_sample.csv` (user directory). Quote CSV fields that contain commas.
- Acceptance checks:
  1. Uploading the same file twice processes nothing the second time and shows load count 2.
  2. Uploading an overlapping file processes only the new rows.
  3. Same SQL with different WHERE filters lands in one group; a different select list does not.
  4. Cancelling an import keeps and groups the rows loaded so far.
  5. A tracker item can go Triage → … → Awaiting adoption → Adopted with two iterations, one rejected for
     "Inaccurate results"; the journey, Δ % values, Q5 savings and Q15 rejection reasons reflect it.
  6. Light and dark themes are both legible (including the app bar); no horizontal scroll at 390 px width.
