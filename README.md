# SQL Performance & Tuning

Enterprise workbench for finding, grouping, tracking and optimizing long running SQL. First use case:
**Cloudera Impala**.

- **Spring Boot 4.1.1** (Java 21) REST service – `backend/`
- **Angular 22 + Angular Material 3 + AG Grid Community 36** UI with light / dark / system themes – `frontend/`
- **Oracle** (shared environments) and **SQLite** (local development) schemas managed by Flyway
- See [docs/architecture.md](docs/architecture.md) for the workflow mapping, data model, fingerprint rules
  and how to add columns.

## Screens

| Screen | What it shows |
|---|---|
| Dashboard | Logged queries, groups, total run time, top groups, tracker pipeline |
| Query Logs | Raw log rows (seq_id … duration_minutes) with filters; import CSV / Excel or pull from Oracle / Impala |
| Query Groups | Same SQL minus WHERE filters grouped by fingerprint; expand a row to see its log rows; add groups to the tracker |
| Group detail | Metrics, sample + normalized SQL, log rows, diagnostics (explain / profile / exec summary), DDL, optimization runs, feedback |
| Tuning Tracker | All tracking columns, column chooser, drag-to-reorder / resize / pin (layout remembered per user), filters, Excel export; edit page with change history and drill-down |

All grids use **AG Grid Community** (MIT, no license key): server-side paging and sorting via the infinite
row model, expandable group rows via full-width detail rows, and a theme bound to the app's light/dark
tokens (`frontend/src/app/shared/grid.ts`). Excel export is produced by the API (Apache POI), so no AG Grid
Enterprise license is needed. If you later license Enterprise, master/detail, the column tool panel and
the server-side row model can replace the custom pieces.
| Administration | Data sources, runtime custom columns, versioned prompt templates, import history |

## Run locally

Prerequisites: JDK 21+, Maven 3.9+, Node 22.22+ / 24.

```bash
# API on :8080 (SQLite file in backend/data/, created on first start)
cd backend
mvn spring-boot:run

# UI on :4200 (proxies /api to :8080)
cd frontend
npm install
npm start
```

Open http://localhost:4200, go to **Query Logs → Import logs** and upload
[`samples/query_log_sample.csv`](samples/query_log_sample.csv). API docs: http://localhost:8080/swagger-ui.html

### Tests

```bash
cd backend && mvn test        # fingerprinting, profile parser, agent response parser, end-to-end ingestion on SQLite
cd frontend && npx ng test --watch=false
```

## Run against Oracle

1. A DBA runs [`db/oracle/00_create_schema.sql`](db/oracle/00_create_schema.sql) (once).
2. Start the service with the `oracle` profile; Flyway applies
   `backend/src/main/resources/db/migration/oracle/*.sql`:

```bash
export SPT_DB_URL=jdbc:oracle:thin:@//dbhost:1521/SERVICE
export SPT_DB_USER=SPT_OWNER
export SPT_DB_PASSWORD=...
java -jar backend/target/sql-perf-tune-0.1.0-SNAPSHOT.jar --spring.profiles.active=oracle
# shared / production: --spring.profiles.active=oracle,prod  (+ SPT_JWT_ISSUER_URI, SPT_CORS_ORIGINS)
```

The Oracle scripts can also be handed to DBAs as plain DDL. Every schema change needs a migration with
the same version number in both `db/migration/oracle` and `db/migration/sqlite`.

## Input log format

CSV (`,` `;` `|` or tab, auto-detected) or Excel (first sheet) with a header row:

```
seq_id, executed_query, user_query, error_code, error_category, error_message, user_id, start_time, end_time, duration_minutes
```

Header matching ignores case / spaces / underscores and accepts aliases (`useris`, `user`, `sql`, `duration`, …).
`duration_minutes` is derived from start / end time when empty. Rows without any SQL are rejected and
reported on the import (status `COMPLETED_WITH_ERRORS`) without stopping the load.

## Configuration (`spt.*`)

| Property | Default | Meaning |
|---|---|---|
| `spt.fingerprint.strip-where-clause` | `true` | Ignore WHERE filters when grouping |
| `spt.fingerprint.mask-literals` | `true` | Ignore literal values elsewhere (LIMIT, CASE, …) |
| `spt.fingerprint.prefer-user-query` | `false` | Hash `user_query` instead of `executed_query` |
| `spt.ingestion.batch-size` | `500` | Rows per insert transaction |
| `spt.ingestion.max-rejected-rows` | `1000` | Abort an import after this many bad rows |
| `spt.ingestion.jdbc-query-timeout-seconds` | `600` | Statement timeout for log pulls |
| `spt.security.mode` | `NONE` | `JWT` turns on OAuth2 resource-server security |
| `spt.cors.allowed-origins` | `http://localhost:4200` | UI origins |
