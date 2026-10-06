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
    tracker/       TuningTracker, TrackerService (audited updates), Excel exporter, controller
    workflow/      diagnostics, Impala profile parser, prompt templates, optimization runs, feedback
    customfield/   runtime custom columns
    audit/         field-level change history
    dashboard/
  src/main/resources/
    application*.yml
    db/migration/oracle/V*.sql     <- Oracle DDL (also the DBA deliverable)
    db/migration/sqlite/V*.sql     <- SQLite twins, same version numbers
frontend/
  src/app/core/        API client, models, theme, interceptor, prefs
  src/app/shared/      grid.ts (AG Grid setup), SQL viewer, status chip, formatters
  src/app/features/    dashboard, logs, groups, tracker, admin
db/oracle/00_create_schema.sql    one-off DBA script (users / grants)
samples/                          sample input logs
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
- Case-insensitive search uses the custom HQL function `spt_lower(...)` (see `SptFunctionContributor`),
  which works on Oracle CLOBs and with the SQLite dialect.
- Oracle scripts were written for 19c+ (identity columns). Run them against your target version in CI
  before the first shared deployment.

### Tables

`SPT_INGESTION_BATCH`, `SPT_SOURCE_FILE`, `SPT_QUERY_LOG`, `SPT_QUERY_LOG_SIGHTING`, `SPT_QUERY_GROUP`,
`SPT_TUNING_TRACKER` (+ view `SPT_TRACKER_V`), `SPT_SQL_DIAGNOSTIC`, `SPT_TABLE_DDL`,
`SPT_PROMPT_TEMPLATE`, `SPT_OPTIMIZATION_RUN`, `SPT_FEEDBACK`, `SPT_CUSTOM_FIELD(_VALUE)`,
`SPT_AUDIT_EVENT`, `SPT_DATA_SOURCE`. See [architecture.md](architecture.md) for the ER diagram.

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

Endpoints: `POST /api/ingestion/upload`, `POST /api/ingestion/pull/{id}`, `GET /api/ingestion/batches`,
`GET /api/ingestion/files`, `GET /api/logs/{id}/history`.

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

## 8. Frontend

- Standalone components, signals for state, zoneless change detection. Anything rendered from an async
  callback must live in a `signal` (plain fields only refresh on user events).
- `core/api.ts` is the single HTTP client; `errorInterceptor` shows RFC 9457 problem details in a snackbar.
- Theme: Material 3 tokens with CSS `light-dark()`; `ThemeService` sets `color-scheme` on `<html>`.
  App tokens (`--spt-*`) live in `styles.scss`. Never hard-code colours in components.

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
- Tracker layout (order / width / visibility / pinning) is stored in `localStorage` under `spt.tracker.grid`.

---

## 9. Configuration reference (`spt.*`)

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

## 10. Testing & quality gates

```bash
cd backend && mvn test                     # 16 tests incl. end-to-end ingestion / de-duplication on SQLite
cd frontend && npx ng test --watch=false   # Vitest
cd frontend && npx ng build                # production build + bundle budgets
```

`IngestionFlowTest` covers: header aliases, derived durations, rejected rows, grouping, tracker creation,
identical-file re-load, overlapping files and in-file duplicates. Add a case there for any ingestion change.

---

## 11. Build & deploy

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

## 12. Conventions

- Java: constructor injection, records for DTOs, Lombok only for entity getters/setters, problem details
  for errors, no business logic in controllers beyond mapping.
- SQL: upper-case DDL, `SPT_` prefix, named constraints (`PK_`, `FK_`, `UK_`, `CK_`, `IX_`).
- Angular: one feature folder per screen, typed API models in `core/models.ts`, no colours outside tokens.
- Commits: imperative subject line, body explaining *why*.
