# SQL Performance & Tuning – Developer Guide

Companion documents: [architecture.md](architecture.md) (workflow mapping, data model, fingerprint rules)
and [user-manual.md](user-manual.md).

---

## 1. Stack

| Layer | Technology |
|---|---|
| API | Java 21, Spring Boot 4.1.1 (Web MVC, Data JPA / Hibernate 7, Validation, Security, Actuator), springdoc |
| Persistence | Flyway 12; **Oracle 19c+** for shared environments, **SQLite** for local development |
| Ingestion | Apache Commons CSV, Apache POI (Excel read + tracker export), JDBC (Oracle `ojdbc11`, Impala driver supplied by you) |
| UI | Angular 22 (standalone components, signals, zoneless), Angular Material 3, **AG Grid Enterprise 36** |
| Tests | JUnit 5 / AssertJ / Spring Boot Test (backend), Vitest (frontend) |

---

## 2. Local setup

Prerequisites: JDK 21+, Maven 3.9+, Node **22.22+ or 24** (Angular 22 requirement).

```bash
# backend - http://localhost:8080 (Swagger UI at /swagger-ui.html)
cd backend
mvn spring-boot:run            # creates backend/data/sql-perf-tune.db and runs Flyway

# frontend - http://localhost:4200, proxies /api to :8080 (proxy.conf.json)
cd frontend
npm install
npm start
```

Load sample data: Query Logs → Import logs → `samples/query_log_sample.csv`, then
`samples/query_log_sample_day2.csv` to see de-duplication.

Optional environment variables (local):

| Variable | Purpose |
|---|---|
| `SPT_SQLITE_PATH` | SQLite file (default `./data/sql-perf-tune.db`) |
| `SPT_AG_GRID_LICENSE_KEY` | AG Grid Enterprise key (empty = evaluation watermark) |

---

## 3. Repository layout

```
backend/
  src/main/java/com/techcoder/sqlperf/
    config/        properties (spt.*), security, OpenAPI, UI config endpoint, Hibernate function contributor
    common/        paging, specs, problem-detail handler, CurrentUser
    fingerprint/   SqlTokenizer, SqlFingerprinter (grouping key), SqlPrettyPrinter
    ingestion/     file parser, JDBC puller, IngestionService (de-duplication), RowKeys, SourceFile, RowKeyBackfill
    log/           QueryLog, LogSighting (load history), controller
    group/         QueryGroup, GroupingService, controller
    tracker/       TuningTracker, TrackerService (stages, journey, board, requests, audited updates), Excel export
    iteration/     TuningIteration, IterationService (best / select / adopt / reject)
    workflow/      diagnostics, Impala profile parser, prompt templates, optimization runs, feedback
    insights/      InsightsService (Q1-Q15), controller
    lookup/        dropdown values (SPT_LOOKUP)
    users/         user directory: user id -> user group (SPT_USER_DIRECTORY)
    customfield/   runtime custom columns
    audit/         field-level change history
    dashboard/     global search
  src/main/resources/
    application*.yml
    db/migration/oracle/V*.sql     <- Oracle DDL (also the DBA deliverable)
    db/migration/sqlite/V*.sql     <- SQLite twins, same version numbers
frontend/
  src/app/core/        API client, models, theme, interceptor, prefs, stages.ts, lookups.ts (LookupStore)
  src/app/shared/      grid.ts (AG Grid setup), charts.ts, journey stepper, request dialog, SQL viewer, chips
  src/app/features/    home (command center), insights, pipeline, logs, groups, tracker, admin
db/oracle/00_create_schema.sql    one-off DBA script (users / grants)
db/oracle/01_drop_and_create_schema.sql   drop + re-create all tables / views + initial values
samples/                          sample input logs and user directory
docs/                             architecture, developer guide, user manual
```

---

## 4. Database & migrations

- Flyway owns the schema (`spring.jpa.hibernate.ddl-auto=none`). Profiles pick the location:
  default → `db/migration/sqlite`, `oracle` → `db/migration/oracle`.
- **Every change needs two scripts with the same version**, e.g. `V4__add_x.sql` in both folders.
  Type mapping: `NUMBER(19)` → `INTEGER`, `NUMBER(p,s)` → `NUMERIC`, `VARCHAR2`/`CLOB` → `TEXT`,
  identity → `INTEGER PRIMARY KEY AUTOINCREMENT`. SQLite `ALTER TABLE` adds one column per statement and
  cannot change constraints, so prefer additive changes.
- Never edit an applied migration; add a new one.
- Text columns longer than 4000 chars are `CLOB` in Oracle and mapped in JPA with
  `@JdbcTypeCode(SqlTypes.LONG32VARCHAR)` (not `@Lob`) so they bind as strings and can be searched.
- Custom HQL functions (`SptFunctionContributor`, dialect-aware): `spt_lower(...)` for case-insensitive search
  on Oracle CLOBs and SQLite, and `spt_date(ts)` for grouping by calendar day (§9).
- `db/oracle/01_drop_and_create_schema.sql` drops and re-creates every table and view and loads the initial
  values (prompt template, dropdown values), equivalent to V1-V4 in one script for DBAs. It also drops the
  Flyway history; start the service once with `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` and
  `SPRING_FLYWAY_BASELINE_VERSION=4` afterwards. Keep it in step when adding a migration.
- Oracle scripts were written for 19c+ (identity columns). Run them against your target version in CI
  before the first shared deployment.

### Tables

`SPT_INGESTION_BATCH`, `SPT_SOURCE_FILE`, `SPT_QUERY_LOG`, `SPT_QUERY_LOG_SIGHTING`, `SPT_QUERY_GROUP`,
`SPT_TUNING_TRACKER` (+ view `SPT_TRACKER_V`), `SPT_SQL_DIAGNOSTIC`, `SPT_TABLE_DDL`,
`SPT_PROMPT_TEMPLATE`, `SPT_OPTIMIZATION_RUN`, `SPT_TUNING_ITERATION`, `SPT_FEEDBACK`,
`SPT_CUSTOM_FIELD(_VALUE)`, `SPT_AUDIT_EVENT`, `SPT_DATA_SOURCE`, `SPT_LOOKUP`, `SPT_USER_DIRECTORY`.

Migrations: V1 core schema, V2 prompt seed, V3 load de-duplication, V4 analytics and iterations (lookups,
iterations, stage timestamps, CPU / scan metrics, user directory, request source). See [architecture.md](architecture.md) for the ER diagram.

---

## 5. Ingestion and "process each row once"

`IngestionService` is the only writer of log rows.

```
upload ──► SHA-256 of file ──► SPT_SOURCE_FILE has hash?
              │ yes: create batch (DUPLICATE_OF_BATCH_ID, FILE_LOAD_NUMBER = n),
              │      copy sightings of the processing batch, SEEN_COUNT += 1, LOAD_COUNT += 1  ── done
              │ no
              ▼
          parse rows ──► RowKeys.of(row) ──► seen earlier in this file? ──► rowsDuplicate++
                                    │
                         chunk (spt.ingestion.batch-size)
                                    ▼
                     findByRowKeyIn(keys) ─► existing: SEEN_COUNT += 1, sighting(is_first = 0)
                                          └► new: insert + fingerprint, sighting(is_first = 1)
                                    ▼
                     GroupingService.recompute(fingerprints of NEW rows only)
                                    ▼
                     register SPT_SOURCE_FILE (only if the batch did not FAIL)
```

- **Row identity** (`spt.ingestion.row-identity`):
  - `CONTENT` (default): SHA-256 of engine + seq_id + user + start + end + executed SQL + user SQL.
  - `SEQ_ID`: engine + seq_id; use only when the source guarantees unique, stable seq_ids.
- `SPT_QUERY_LOG.ROW_KEY` has a **unique index**, so the rule also holds across several service instances
  (a concurrent duplicate makes that chunk fail rather than double count). Within one instance imports are
  serialized by a lock.
- A failed import does not register its file, so the same file can be retried and is then parsed again
  (already-inserted rows are still skipped by row key).
- `RowKeyBackfill` computes keys for rows loaded before V3 at start-up; duplicates that already existed keep
  a null key and are logged.
- JDBC pulls use the same row-level logic; `:since` in the source query limits the pull to new rows.

**Background processing.** Upload and pull return the batch immediately (`RUNNING`) and run on the
single-thread `ingestionExecutor` (`AsyncConfig`), which also serializes imports. Progress (`ROWS_READ`,
`ROWS_LOADED`, …) is committed per chunk; the UI polls `GET /api/ingestion/batches/{id}`.
`POST /api/ingestion/batches/{id}/cancel` stops the import after the current chunk. Whether a batch completes,
fails or is cancelled, the fingerprints of the rows it inserted are regrouped, so loaded rows always appear
on the groups screen. `IngestionRecovery` runs at start-up: batches still `RUNNING` (server stopped mid-import)
become `FAILED` ("Interrupted…") and any log rows without a group are grouped.

Endpoints: `POST /api/ingestion/upload`, `POST /api/ingestion/pull/{id}`, `GET /api/ingestion/batches`,
`GET /api/ingestion/batches/{id}`, `POST /api/ingestion/batches/{id}/cancel`, `GET /api/ingestion/files`,
`GET /api/logs/{id}/history`.

---

## 6. Grouping

`SqlFingerprinter` tokenizes the SQL, drops every WHERE clause (any depth), masks literals, collapses
`IN (?, ?)` lists and hashes the result. `GroupingService.recompute` rebuilds a group's aggregates from all
its member rows (idempotent). `POST /api/groups/rebuild` re-fingerprints everything after you change
`spt.fingerprint.*`. Unit tests: `SqlFingerprinterTest`.

---

## 7. Tracker updates and audit

`PUT /api/tracker/{id}` is a full replacement of the editable fields and must carry the `version` the client
loaded (optimistic locking → HTTP 409 on conflict). `TrackerService.update` copies every component of
`TrackerUpdateRequest` onto the entity **by name** and writes an `SPT_AUDIT_EVENT` row per changed field, so a
new column only needs the same property name in entity, DTO and request.

### Adding a tracker column (permanent)

1. `V<n>__*.sql` in both migration folders (`ALTER TABLE SPT_TUNING_TRACKER ADD ...`; update
   `SPT_TRACKER_V` if reporting needs it).
2. Field in `TuningTracker`, `TrackerDto` (+ `of(...)`) and `TrackerUpdateRequest`.
3. Frontend: `core/models.ts` (`Tracker`), `features/tracker/tracker-columns.ts`, and the edit form in
   `tracker-detail.html`.
4. The Excel export picks it up automatically (it reflects over `TrackerDto`).

For business-owned columns that may change, prefer **custom columns** (Administration) – no release needed.

---

## 8. Tuning lifecycle: stages and iterations

### Stages

`WORKFLOW_STATUS` is the stage. The UI labels (`frontend/src/app/core/stages.ts`) are:

| Status | Label | Entered when |
|---|---|---|
| `NEW` | Triage | item created (from a group, or `POST /api/tracker/requests`) |
| `DIAGNOSTICS_CAPTURED` | Diagnosed | original-phase diagnostic saved |
| `OPTIMIZATION_REQUESTED` | Tuning | agent run started, or an iteration was rejected |
| `OPTIMIZED` | Candidate ready | an iteration exists (agent answer or manual) |
| `POST_RUN_VALIDATED` | Tested | an iteration got test results |
| `SME_VALIDATION` | Awaiting adoption | an iteration was selected |
| `ADOPTED` | Adopted | feedback `ADOPTED` |
| `REJECTED` / `ON_HOLD` | Rejected / On hold | feedback `REJECTED` without an iteration / manual |

`TrackerService.changeStatus` is the **only** setter: it writes the audit event and maintains
`STAGE_CHANGED_AT`, `ADOPTED_AT` and `CLOSED_AT`. Automatic transitions use `advanceTo`, which only moves
forward (a late diagnostic never pulls an item back). The journey endpoint (`GET /api/tracker/{id}/journey`)
rebuilds the timeline from the `workflowStatus` audit events; the board (`GET /api/tracker/board`) returns
one `BoardCard` per item with `daysInStage`.

`REQUEST_SOURCE` is `LOG_DETECTED` (default), `PROACTIVE_UAT` or `USER_REQUEST`. A request fingerprints the
SQL and joins an existing group or creates one.

### Iterations (`SPT_TUNING_ITERATION`, package `iteration/`)

```
agent answer / manual ──► PROPOSED ──test──► TESTED (or FAILED) ──select──► SELECTED ──feedback──► ADOPTED
                                                                                       └──────────► REJECTED
```

- Created by `WorkflowService` (agent answer, `SOURCE=AI_AGENT`) or `POST /api/tracker/{id}/iterations`
  (`MANUAL`). Numbered per tracker (`ITERATION_NO`).
- Test results come from an `OPTIMIZED`-phase diagnostic with `iterationId` (parsed by
  `ImpalaProfileParser`: duration, execution, teardown, CPU from *Per Node User/System Time*, rows / tables /
  peak memory from the ExecSummary) or from `PUT .../iterations/{id}` (manual entry).
- **Best** = fastest tested iteration (with a run duration) whose `RESULT_MATCHES` is not false, ties broken by CPU
  (`IterationService.best`).
- `POST .../iterations/{id}/select` copies the iteration's SQL and post-run metrics to the tracker
  (`SELECTED_ITERATION_ID`) and moves the item to `SME_VALIDATION`.
- `POST /api/groups/{id}/feedback` with `decision=ADOPTED` adopts the selected (or given) iteration and closes
  the item. `REJECTED` + `iterationId` rejects that iteration (with `REJECTION_REASON`) and sends the item back
  to `OPTIMIZATION_REQUESTED`; `REJECTED` without an iteration closes the item.

`TuningLifecycleTest` walks an item through the whole lifecycle and checks every insight.

---

## 9. Insights (`insights/`)

`InsightsService` answers the 15 programme questions; the class Javadoc holds the definitions (bad query,
pattern, recurring, optimized, outstanding, estimated savings).

| Endpoint | Questions | Parameters |
|---|---|---|
| `GET /api/insights/daily` | Q1, Q2, Q3, Q8, last 14 days | `date` |
| `GET /api/insights/pipeline` | Q4, Q6, Q9, Q15, stage counts | `priorDay`, `from`, `to` |
| `GET /api/insights/savings` | Q5, Q11 | `from`, `to` |
| `GET /api/insights/themes` | Q12 | `days` (default 30) |
| `GET /api/insights/users` | Q7, Q13 | `from`, `to` |
| `GET /api/insights/trends` | Q10, Q14 | `months` (default 12) |

Implementation notes:

- A query is dated by `coalesce(START_TIME, CREATED_AT)`. Days are grouped with the custom HQL function
  `spt_date(ts)` (`'yyyy-MM-dd'`): SQLite `substr(ts,1,10)`, Oracle `to_char(ts,'YYYY-MM-DD')`. Do **not** use
  HQL `day()`: the SQLite community dialect renders it as `strftime('%d')+1`.
- Aggregation is done in HQL with group-by projections and finished in Java; results are small (per day /
  hour / group / user), so no reporting tables are needed. For very large logs add a daily summary table.
- Savings use the run rate of the 30 days before adoption (runs per day) × per-run saving × the adopted days
  within the period. CPU, rows and table scans need the metric on both sides; an item without it contributes 0.
- User groups come from `SPT_USER_DIRECTORY` (`/api/users`, CSV upload); unmapped users fall into
  *Unassigned*.

Global search: `GET /api/search?q=` matches `T-12`, `#5` / `G-5`, a number (tracker, group or seq id) or SQL
text.

---

## 10. Dropdown values (`lookup/`)

`SPT_LOOKUP (CATEGORY, LOOKUP_VALUE, SORT_ORDER, TONE, ACTIVE)` holds the values of the tracker dropdowns.
`LookupService.TRACKER_FIELDS` maps each tracker property to its category:

| Category | Tracker field |
|---|---|
| `THEME` | `theme` |
| `DEV_TEAM_STATUS` | `devTeamStatus` |
| `OPTIMIZED_SQL_STATUS` | `optimizedSqlStatus` |
| `CLOUDERA_POST_RUN_VALIDATION` | `clouderaPostRunValidation` |
| `SME_VALIDATION` | `smeValidation` |
| `INSTALL_STATUS` / `EXECUTE_STATUS` / `VALIDATION_STATUS` | `installStatus` / `executeStatus` / `validationStatus` |
| `ENVIRONMENT` | `environment` |
| `REJECTION_REASON` | feedback / iteration `rejectionReason` |

Updates are validated against the category's values (active or not; an empty category accepts anything),
so deactivating a value only hides it from the dropdowns and never blocks saving old items. `TONE` (`ok`,
`warn`, `bad`, `info`, `muted`) colours the chips. Seed values are in `V4__analytics_and_iterations.sql`; change them in
Administration → Dropdown values. To make another field a dropdown: add a category to the map, seed it, and
use `LookupStore.options(category, current)` in the form.

---

## 11. Frontend

- Standalone components, signals for state, zoneless change detection. Anything rendered from an async
  callback must live in a `signal` (plain fields only refresh on user events).
- `core/api.ts` is the single HTTP client; `errorInterceptor` shows RFC 9457 problem details in a snackbar.
- Theme: Material 3 tokens with CSS `light-dark()`; `ThemeService` sets `color-scheme` on `<html>`.
  App tokens (`--spt-*`) live in `styles.scss`. Never hard-code colours in components. `light-dark()` only
  accepts colours: build gradients and shadows from colour tokens (see `--spt-appbar`).
- Navigation: Overview (Command center `home`, `insights`), Tuning (`pipeline`, `tracker`), Data (`groups`,
  `logs`), Settings (`admin`). The app bar holds the global search and *New tuning request*.
- Dropdowns read their values through `LookupStore` (`core/lookups.ts`), cached once per session and reloaded
  after edits in Administration. Stage labels and order come from `core/stages.ts`.

### Charts

`shared/charts.ts` has small SVG components with no chart library: `ColumnChart`, `BarList`, `LineChart`,
`StatTile` and `ChartCard`. Rules:

- Colours are the validated categorical slots `--chart-1..3` (blue, orange, green), checked for colour-vision
  separation and contrast against both surfaces (`#ffffff`, `#13223a`). Slot 3 is below 3:1 in light mode, so
  **every `ChartCard` has a table view** with the exact values; pass `[table]`.
- One series per chart where possible; a legend for 2+ series; values and labels use text tokens, not
  series colours; every mark has a hover tooltip; never two y-axes.
- Charts resize with their container (`ResizeObserver` in the `Responsive` base directive).

### AG Grid Enterprise

All grid setup lives in `src/app/shared/grid.ts`:

- **Modules:** only the needed community + enterprise modules are registered
  (`ServerSideRowModelModule`, `MasterDetailModule`, `SideBarModule`, `ColumnsToolPanelModule`,
  `ColumnMenuModule`, `ContextMenuModule`, `CellSelectionModule`, `ClipboardModule`, `ExcelExportModule`, …).
  In dev builds `ValidationModule` logs a console error if a feature is used without its module; add the
  module there.
- **License:** the key is served by `GET /api/ui-config` from `SPT_AG_GRID_LICENSE_KEY` and applied by an app
  initializer before any grid is created (`app.config.ts`). Do not commit the key. Without it the grids work
  with a watermark and console notice.
- **Theme:** `gridTheme` maps AG Grid params to the app's CSS variables with `browserColorScheme: 'inherit'`,
  so grids follow light / dark automatically.
- **Server-side data:** `serverGridOptions()` + `pagedDatasource(fetch, sortMap)` adapt the SSRM block
  requests to the API's `page/size/sort` parameters. Filters are form fields outside the grid; call
  `api.refreshServerSide({ purge: true })` after changing them.
- **Master/detail:** Query Groups use `masterDetail` with `GroupDetailRow` as detail renderer (it embeds the
  `GroupMembers` grid).
- **Renderers:** `LinkCell` (router links that don't trigger the row click), `StatusCell`.
- Tracker layout (order / width / visibility / pinning) is stored in `localStorage` under `tracker.grid.v2`
  (bump the key when the default columns change).

---

## 12. Configuration reference (`spt.*`)

| Property | Default | |
|---|---|---|
| `spt.security.mode` | `NONE` | `JWT` = OAuth2 resource server (`prod` profile) |
| `spt.fingerprint.strip-where-clause` | `true` | |
| `spt.fingerprint.mask-literals` | `true` | |
| `spt.fingerprint.prefer-user-query` | `false` | |
| `spt.ingestion.batch-size` | `500` | rows per transaction |
| `spt.ingestion.max-rejected-rows` | `1000` | |
| `spt.ingestion.default-sql-engine` | `IMPALA` | |
| `spt.ingestion.jdbc-query-timeout-seconds` | `600` | |
| `spt.ingestion.row-identity` | `CONTENT` | or `SEQ_ID` |
| `spt.ui.ag-grid-license-key` | `${SPT_AG_GRID_LICENSE_KEY:}` | |
| `spt.cors.allowed-origins` | `http://localhost:4200` | |

Oracle profile variables: `SPT_DB_URL`, `SPT_DB_USER`, `SPT_DB_PASSWORD`, `SPT_DB_SCHEMA`, `SPT_DB_POOL_SIZE`.
Prod profile: `SPT_JWT_ISSUER_URI`, `SPT_CORS_ORIGINS`, `SPT_API_DOCS_ENABLED`.

---

## 13. Testing & quality gates

```bash
cd backend && mvn test                     # 19 tests incl. ingestion / de-duplication and the tuning lifecycle on SQLite
cd frontend && npx ng test --watch=false   # Vitest
cd frontend && npx ng build                # production build + bundle budgets
```

`IngestionFlowTest` covers: header aliases, derived durations, rejected rows, grouping, tracker creation,
identical-file re-load, overlapping files and in-file duplicates. Add a case there for any ingestion change.
`TuningLifecycleTest` covers request → diagnostics → iterations → select → reject → adopt, every insight
endpoint, and recovery of rows left ungrouped by an interrupted import.

---

## 14. Build & deploy

```bash
cd frontend && npm ci && npx ng build      # static files in frontend/dist/frontend/browser
cd backend && mvn -DskipTests package      # backend/target/sql-perf-tune-0.1.0-SNAPSHOT.jar
java -jar backend/target/sql-perf-tune-*.jar --spring.profiles.active=oracle,prod
```

Serve the Angular build from your web tier / CDN with an `index.html` fallback for client-side routes, and
route `/api` to the service. Health: `/actuator/health/{liveness,readiness}`.

Impala log pulls need the Cloudera Impala JDBC driver on the classpath (internal Maven repository or
`-Dloader.path` with a ZIP-layout build).

---

## 15. Conventions

- Java: constructor injection, records for DTOs, Lombok only for entity getters/setters, problem details
  for errors, no business logic in controllers beyond mapping.
- SQL: upper-case DDL, `SPT_` prefix, named constraints (`PK_`, `FK_`, `UK_`, `CK_`, `IX_`).
- Angular: one feature folder per screen, typed API models in `core/models.ts`, no colours outside tokens.
- Commits: imperative subject line, body explaining *why*.
