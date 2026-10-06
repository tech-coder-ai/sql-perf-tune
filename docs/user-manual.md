# SQL Performance & Tuning – User Manual

This manual is for the people who use the application day to day: platform / Cloudera engineers, client
development teams, SMEs and business users who own SQL. The first supported engine is **Cloudera Impala**.

---

## 1. What the application does

1. **Collects query logs:** every SQL statement that ran, who ran it, how long it took and whether it failed.
2. **Groups equivalent SQL:** the same statement run with different filter values (WHERE clause) becomes
   one *query group*, so you can see which logic costs the most in total.
3. **Tracks tuning work:** groups worth fixing go onto the *Tuning Tracker*, where owners, statuses,
   before/after metrics, the optimized SQL and the outcome are recorded.
4. **Supports the tuning workflow:** diagnostics (explain plan, query profile, execution summary), table DDL,
   AI optimization prompts and runs, post-run validation and adoption feedback.

You can drill **down** from a tracker item to its group and the original log rows, and **up** from any log
row to its group and tracker item.

---

## 2. Getting around

| Area | Purpose |
|---|---|
| **Dashboard** | Totals, the most expensive query groups and how many tracker items are in each status |
| **Query Logs** | All loaded log rows, import button, filters |
| **Query Groups** | SQL grouped by fingerprint; click a row to see its log rows; add groups to the tracker |
| **Group page** | Everything about one group: metrics, SQL, log rows, diagnostics, DDL, optimization, feedback |
| **Tuning Tracker** | The work list with all tracking columns; open an item to edit it |
| **Administration** | Database sources, custom columns, prompt templates, loaded files, import history |

**Theme:** the icon at the top right switches between light, dark and "follow my system".

### Working with grids

All lists are data grids:

- **Sort:** click a column header (click again to reverse).
- **Paging:** controls at the bottom right; choose 10–100 rows per page.
- **Columns:** the **Columns** tab on the right edge of a grid shows / hides / reorders columns. You can also
  drag headers to reorder, drag header edges to resize, and use the ⋮ header menu to pin or auto-size.
  On the Tuning Tracker your layout is remembered in your browser; **Reset layout** restores the default.
- **Copy:** select cells with the mouse (shift-click for a range), then <kbd>Ctrl</kbd>+<kbd>C</kbd> or
  right-click → *Copy* / *Copy with Headers*. The result pastes straight into Excel.
- **Export:** right-click → *Export loaded rows to Excel* exports what is currently loaded in the grid. The
  tracker's **Export Excel** button exports *all* rows that match the filter, including custom columns.
- **Filters:** use the form above each grid and press **Apply**.

---

## 3. Loading query logs

Go to **Query Logs → Import logs**.

### From a file (CSV or Excel)

1. Drop a `.csv` or `.xlsx` file on the dialog (or click *browse*).
2. Choose the SQL engine (Impala by default).
3. Click **Import**.

The first row must be a header. Expected columns (case, spaces and underscores do not matter):

| Column | Required | Notes |
|---|---|---|
| `seq_id` | recommended | Sequence / query number from the source |
| `executed_query` | yes* | SQL that was executed |
| `user_query` | yes* | SQL as typed by the user (*at least one of the two*) |
| `error_code`, `error_category`, `error_message` | no | Leave empty for successful runs |
| `user_id` | no | `useris` and `user` are also accepted |
| `start_time`, `end_time` | no | e.g. `2026-09-01 10:00:00`, ISO, `MM/dd/yyyy HH:mm`, Excel dates |
| `duration_minutes` | no | Calculated from start / end time when empty |

CSV files may use comma, semicolon, pipe or tab as the separator. Two ready-made examples are in the
`samples/` folder of the repository:

- `query_log_sample.csv` (60 rows, one day of activity)
- `query_log_sample_day2.csv` (35 rows: 20 rows repeated from day 1 + 15 new rows), useful for seeing how
  repeated loads behave

### From a database

If an administrator configured a data source (Oracle or Impala), switch the dialog to **Database**, pick the
source and click **Import**. Only rows newer than the previous pull are requested.

### Reading the result

After an import the dialog shows, for example:

> **completed** · 15 new · 20 already loaded (not re-processed) · 0 rejected · 3 groups refreshed

- **new**: rows seen for the first time; they were fingerprinted and added to their groups.
- **already loaded**: rows that an earlier import already brought in (or that appear twice in the same
  file). They are **not processed again**; the application only records that they were seen again.
- **rejected**: rows that could not be read (e.g. no SQL text, invalid date). The reason per row is shown
  below the summary and stays available in *Administration → Import history*.

### Loading several times a day

Logs can be loaded as often as you like, including cumulative exports that repeat earlier rows. **Every SQL
row is processed exactly once**:

- **Same file again.** If the exact same file (byte for byte) is uploaded again, it is not even opened. The
  import is flagged as *duplicate of import #N*, the file's load counter goes up, and every row of the file
  is marked as seen once more.
- **Overlapping file.** If a new file contains some rows that were already loaded, only the new rows are
  processed; the others are counted as *already loaded*.

Where to see it:

- **Query Logs → Loads** column: how many loads contained the row (1 = loaded once). Values above 1 are
  highlighted. Tick **Re-loaded only** to list just those rows.
- **Click a log row → Load history**: every import that contained it, marked *processed* (the first),
  *already loaded* (skipped) or *duplicate file* (identical file re-uploaded).
- **Administration → Loaded files**: each distinct file, how many times it was loaded, when, and which
  import processed it.
- **Administration → Import history**: per import *Read / New / Already loaded / File load # / Duplicate of*.

Group sizes and durations are therefore never inflated by repeated loads.

---

## 4. Query groups

A **query group** is all log rows whose SQL is the same once you ignore:

- the **WHERE clause** (different filter values or extra filters),
- literal values elsewhere (e.g. `LIMIT 100` vs `LIMIT 10`),
- comments, upper / lower case and spacing.

Different selected columns, joins or GROUP BY make a different group.

| Column | Meaning |
|---|---|
| Group ID | Click to open the group page |
| Group size | Number of log rows in the group |
| User ID(s) | Number of distinct users · their IDs |
| Duration count | Rows that have a duration |
| Avg / Min / Max / Total | Run time statistics (Total = cluster time spent on this SQL) |
| Sample query | The **longest-running** execution, the "bad SQL" candidate |
| Row indices | `seq_id`s of the member rows |
| Fingerprint | Technical grouping key |
| Tracker | Tracker status, or **Track** to add the group |

- **Click a row** (or its chevron) to expand it: you see the normalized SQL and the group's log rows.
- Tick several groups and press **Add N to tracker** to put them on the work list.
- **Rebuild** is only needed after an administrator changes the grouping rules.

---

## 5. Tuning Tracker

Each tracker item (`T-1`, `T-2`, …) belongs to one query group. Group metrics (size, users, durations,
sample query) are always current; the rest is entered by the teams.

Open an item by clicking its row. The edit page has four tabs:

1. **Tracking**: workflow status, priority, theme, team leads and statuses (Dev, Cloudera, SME), Optimized SQL
   status, Cloudera post-run validation, SME validation, install / execute / validation, and before/after
   metrics:
   - *OG / post-run duration (min)*: total run time before / after tuning; the improvement % is calculated.
   - *Execution time (s)*: time until the last row was fetched.
   - *Teardown time (s)*: time from the last row fetched until the query was released.
   - *Teardown %*: leave empty and it is calculated as teardown ÷ run duration.
   - Problem, Changes, Recommendations, plus any **custom columns** your administrator added.
2. **Queries**: raw, formatted, cleansed (comments removed, still runnable) and optimized SQL.
3. **Log rows**: the underlying executions.
4. **History**: who changed which field, when, from what to what.

Press **Save**. If someone else saved the item in the meantime you get a message and the latest version is
reloaded, so nobody's changes are overwritten silently.

**Workflow statuses:** New → Diagnostics captured → Optimization requested → Optimized → Post-run validated
→ SME validation → Adopted / Rejected (or On hold). Several steps update the status automatically (see below).

---

## 6. Tuning workflow on the group page

Open a group (from Query Groups or *Group workflow* on a tracker item).

| Tab | Workflow step | What to do |
|---|---|---|
| **Diagnostics** | 4–6, 10 | Paste or attach the explain plan and Impala query profile for the **original** SQL; later do the same for the **optimized** SQL. The profile is parsed automatically and fills the tracker's OG / post-run duration, execution and teardown times. |
| **DDL** | 8 | Add `SHOW CREATE TABLE` output and row counts for every table the SQL reads. |
| **Optimization** | 7–9 | Choose a prompt template and press **Run optimization agent**. The full prompt is built from the SQL, DDL, explain, profile and execution summary. Run it in your approved AI tool and paste the answer back; the change narrative and optimized SQL are stored and copied to the tracker. |
| **Feedback** | 12 | Record whether the optimized SQL was **adopted** or **rejected** (a reason is required for rejections) or add a comment. The tracker status follows. |

---

## 7. Administration

- **Data sources:** JDBC connections used to pull logs from Oracle / Impala. The password is never typed
  here; enter the *name* of the environment variable that holds it. **Test** checks the connection.
- **Custom columns:** add new columns to the tracker (or groups / logs) without a release: text, long text,
  number, date, yes/no or a list of options, optionally required. They appear in the grid, the edit form
  and the Excel export.
- **Prompt templates:** edit the AI prompt. Saving creates a new version and activates it; older versions
  stay for reproducibility and can be re-activated.
- **Loaded files / Import history:** see §3.

---

## 8. FAQ

**I uploaded the same file twice by mistake. Do I need to clean up?**
No. The second upload is flagged as a duplicate and nothing is processed again.

**Yesterday's export also contains part of today's data. Is that a problem?**
No. Rows already loaded are recognised and skipped; only new rows are added.

**Why is a query I know is slow not in a group with its siblings?**
The groups differ in more than their WHERE clause (e.g. a different column list or join). Search for the
table name on Query Groups.

**The grid shows a "trial / license" watermark.**
The AG Grid Enterprise license key is not configured on the server. Ask the administrator to set
`SPT_AG_GRID_LICENSE_KEY`; everything works meanwhile.
