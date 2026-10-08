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
   AI optimization prompts and runs, several **tuning iterations** per SQL, selection of the best iteration,
   and adoption or rejection by the users.
5. **Answers the programme's questions:** bad queries per day and hour, recurring patterns, outstanding
   backlog, adoption, savings (wall clock, Impala CPU, rows and table scans), turnaround, trends, themes and
   users. See §6.

You can drill **down** from a tracker item to its group and the original log rows, and **up** from any log
row to its group and tracker item.

---

## 2. Getting around

| Section | Screen | Purpose |
|---|---|---|
| Overview | **Command center** (start page) | Today's headline numbers, bad queries by hour, where the backlog stands, top patterns |
| Overview | **Insights** | The 15 programme questions, each with a chart and a table view (§6) |
| Tuning | **Pipeline board** | Every SQL being tuned as a card in its stage column, from Triage to Adopted (§7) |
| Tuning | **Tuning tracker** | The full work list with all tracking columns; open an item for its journey, iterations and fields (§8) |
| Data | **Query groups** | SQL grouped by fingerprint; expand a row to see its log rows; add groups to the tracker |
| Data | **Query logs** | All loaded log rows, import button, filters |
| Settings | **Administration** | Dropdown values, users & groups, data sources, custom columns, prompts, loaded files, imports |

The app bar is available everywhere:

- **Search** (*Find a SQL*): type `T-12` (tracker item), `#5` or `G-5` (group), a seq id, or any piece of SQL
  text, and jump straight to it.
- **New tuning request**: register SQL that did not come from the logs (§8.4).
- **Theme**: switches between light, dark and "follow my system".

The menu icon collapses the navigation to icons; on a phone it opens as a drawer.

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

Imports run in the background. The dialog shows a progress bar with the rows read so far; you can close it
and keep working, or press **Cancel import**. Rows loaded before a cancel are kept **and grouped**, so they
appear on Query groups and can be tracked. If the server restarts during an import, the import is marked
*Interrupted* and the rows already loaded are grouped when the server comes back.

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

## 5. Command center

The start page answers "how was today and where do we stand?" Use the date control at the top right
(arrows step one day) to look at another day.

- **Tiles:** bad queries today (Q1, with the change against the previous day), recurring (Q2), query
  patterns (Q3), outstanding patterns (Q6), awaiting adoption (Q15) and time saved this month (Q5). The
  Q-badge shows which Insights question the tile belongs to.
- **Bad queries by hour (Q8)**, **Tuning pipeline** (click a stage to open it on the pipeline board),
  **Top patterns of the day** and **the last 14 days**.
- **All insights** opens the full Insights page.

Every chart has a **table view** (the switch at the top right of the card) with the exact values.

---

## 6. Insights: the 15 questions

The Insights page has an index on the left grouped as *Today*, *Tuning outcomes*, *Backlog & priorities*,
*Users* and *Trends*; click a question to jump to it. The controls at the top set the inputs:

| Control | Used by |
|---|---|
| **Day** | Q1–Q4, Q8 |
| **Period** (7 days, 30 days, month to date, 90 days, custom) | Q5, Q7, Q9, Q11, Q13, Q15 |
| **Trend** (6 / 12 / 24 months) | Q10, Q14 |
| **Theme window** | Q12 |

**Definitions** used by every answer:

- **Bad query:** one row of the injected query log (the daily bad-query extract), dated by its start time.
- **Pattern:** a query group, i.e. the same SQL ignoring its WHERE filters.
- **Recurring:** the pattern had already been seen on an earlier day.
- **Optimized:** the tracker item has at least one candidate iteration (stage *Candidate ready* or later).
- **Outstanding:** a pattern that is not adopted yet: not tracked, in progress, on hold or rejected.
- **Savings:** per-run saving of the adopted iteration (original minus adopted, from the profiles or the
  entered test results) × the pattern's runs per day in the 30 days before adoption × the days since adoption
  that fall in the period. Wall clock, Impala CPU, rows scanned and table scans are reported separately.

| # | Question | Where to look |
|---|---|---|
| Q1 | How many bad queries did we see today? | Count for the day, change vs the previous day, daily bars for context |
| Q2 | How many of them were recurring? | Recurring vs new-pattern queries |
| Q3 | How many instances per query pattern? | Distribution of instances per pattern and the busiest patterns |
| Q4 | Was the prior day's bad SQL optimized, and by which theme? | Optimized / not optimized (categorised, not categorised, not tracked), and the not-optimized ones by theme (e.g. *Trade all*, *Teardown*) |
| Q5 | How many tuned SQL were adopted, and what did we save? | Adoptions in the period, adoption rate, wall clock / CPU / rows / table scans saved, savings per week |
| Q6 | How many bad queries / patterns are outstanding? | Outstanding patterns and their queries, by stage |
| Q7 | Which user groups / users have the most bad queries? | Ranking by user group and by user (groups come from Administration → Users & groups) |
| Q8 | Which hour has the most bad queries? | Hour-of-day chart for the selected day |
| Q9 | What is our end-to-end turnaround? | Average / median days from detection to adoption, and time spent per stage |
| Q10 | Month-over-month trend of outstanding queries and patterns | Lines for patterns and queries, month by month |
| Q11 | Impact of a fixed project / theme after adoption | Per theme: adopted items, average gain, time saved, runs per day before vs after |
| Q12 | Which theme should we work on next? | Outstanding themes ranked by the time they cost in the window (the biggest saving potential) |
| Q13 | Which users always have bad SQL? | Users ranked by how consistently they produce bad SQL; a **repeat user** had bad SQL on at least half the days of the period (5+ queries) |
| Q14 | Proactive (UAT) tuning vs user requests | Items per month by source: detected in logs, proactive UAT, user request |
| Q15 | Which queries wait for adoption, and which optimizations were rejected and why? | Waiting list (oldest first), rejections by reason and the latest rejections |

---

## 7. Pipeline board

Every tracker item is a card in the column of its **stage**:

| Stage | Meaning |
|---|---|
| **Triage** | On the tracker, waiting for analysis |
| **Diagnosed** | Explain plan / profile of the original SQL captured |
| **Tuning** | Optimization in progress (agent run or manual rewrite) |
| **Candidate ready** | At least one candidate iteration exists and needs testing |
| **Tested** | Candidate(s) tested; pick the best iteration |
| **Awaiting adoption** | The selected iteration is with the users / SMEs |
| **Adopted** | Implemented by the users |
| **Rejected** / **On hold** | Closed without adoption / parked |

A card shows the item id, priority, source (e.g. *proactive UAT*), theme, the SQL, its runs and total
time, the number of iterations, the lead, and how long it has been in the stage (the clock turns amber and
red as it ages). Filter by text, theme, priority, source or lead; **Show adopted & rejected** adds the closed
columns. **Table view** opens the same items in the tracker grid.

---

## 8. Tuning tracker

Each tracker item (`T-1`, `T-2`, …) belongs to one query group, or to a SQL registered by request.
Group metrics (size, users, durations, sample query) are always current; the rest is entered by the teams.
The grid supports drill down (open an item, then its group and log rows) and up (from a log row to its group
and item).

### 8.1 The item page

- **Header:** id, stage, priority, group, theme, runs and users, plus the actions that fit the stage, e.g.
  *Diagnostics & AI agent*, *Add iteration*, *Select best*, *Mark adopted*, *Reject*.
- **Journey:** the stages from Triage to Adopted with the date each was reached and the days spent in it.
  Skipped stages are shown as such. Below it, **Next step** says what to do now.
- **Tabs:**
  1. **Overview:** before / after of run time, execution, teardown, Impala CPU, rows and tables scanned and
     peak memory, from the original run and the selected iteration.
  2. **Iterations:** every tuning attempt (§8.2).
  3. **Tracking:** all tracking fields (§8.3).
  4. **Queries:** raw, formatted, cleansed (comments removed, still runnable) and optimized SQL.
  5. **Log rows:** the underlying executions.
  6. **History:** who changed which field, when, from what to what, including every stage change.

### 8.2 Tuning iterations

Tuning usually takes several attempts. Each attempt is an **iteration** (`#1`, `#2`, …), created either by
the AI agent (its answer is pasted on the group page) or manually (**New iteration**: paste the rewritten
SQL and what changed).

1. **Test** an iteration: on the group page, capture the post-run profile for the *Optimized* phase and pick
   the iteration it belongs to; or press the pencil on the iteration and enter the results (run time,
   execution, teardown, CPU, rows / tables scanned, memory, whether the results match the original).
2. The **★ best** iteration is the fastest tested iteration whose results match the original (CPU breaks
   ties). The iterations table shows each iteration's gain against the original.
3. **Select** an iteration for adoption (usually the best). Its SQL and results are copied to the tracker
   and the item moves to **Awaiting adoption**.
4. Record the users' decision with **Mark adopted** or **Reject** (choose the iteration, your role, a
   reason from the list and a comment):
   - *Adopted*: the item is closed as Adopted; savings count from this date (Insights Q5, Q11).
   - *Rejected* with an iteration: that iteration is marked rejected (e.g. *Inaccurate results*) and the item
     goes back to **Tuning** so another iteration can be tried.
   - *Rejected* without an iteration: the whole item is closed as Rejected.

### 8.3 Tracking fields

Fields with a fixed set of values are **dropdowns**: Stage, Priority, Theme, Request source, Environment,
Dev team status, Optimized SQL, Cloudera post-run validation, SME validation, Install, Execute and
Validation. Administrators maintain the values (§10). The other fields are: Dev / Cloudera / SME team
leads; before/after metrics; Problem, Changes, Recommendations; and any **custom columns**.

- *OG / post-run duration (min)*: total run time before / after tuning; the improvement % is calculated.
- *Execution time (s)*: time until the last row was fetched.
- *Teardown time (s)*: time from the last row fetched until the query was released.
- *Teardown %*: leave empty and it is calculated as teardown ÷ run duration.

Press **Save**. If someone else saved the item in the meantime you get a message and the latest version is
reloaded, so nobody's changes are overwritten silently. Stage changes made here are recorded like the
automatic ones.

### 8.4 New tuning request (proactive UAT / user request)

Use **New tuning request** in the app bar when SQL needs tuning before it shows up in the logs (proactive
tuning in UAT) or when a user asks for help. Paste the SQL and choose the source, who requested it, the
environment, priority and theme. If the same SQL (ignoring filters) already has a group, the request joins
it; otherwise a new group is created. Insights Q14 compares the sources.

---

## 9. Tuning workflow on the group page

Open a group (from Query Groups or *Diagnostics & AI agent* on a tracker item).

| Tab | Workflow step | What to do |
|---|---|---|
| **Diagnostics** | 4–6, 10 | Paste or attach the explain plan and Impala query profile for the **original** SQL; later do the same for each **optimized** iteration (pick the iteration). The profile is parsed automatically: run duration, execution and teardown times, Impala CPU, rows / tables scanned and peak memory. |
| **DDL** | 8 | Add `SHOW CREATE TABLE` output and row counts for every table the SQL reads. |
| **Optimization** | 7–9 | Choose a prompt template and press **Run optimization agent**. The full prompt is built from the SQL, DDL, explain, profile and execution summary. Run it in your approved AI tool and paste the answer back; it becomes a new iteration. |
| **Feedback** | 12 | Record whether an iteration was **adopted** or **rejected** (reason required for rejections) or add a comment. The tracker stage follows (§8.2). |

---

## 10. Administration

- **Dropdown values:** the values offered in the tracker's dropdowns: Theme, Dev team status, Optimized
  SQL, Cloudera post-run validation, SME validation, Install, Execute, Validation, Environment and Rejection
  reason. Add a value, rename it, set its colour (tone) and order, or deactivate it. A deactivated value stays
  on the items that already use it but is no longer offered.
- **Users & groups:** maps log user ids to a display name, a **user group** (team / desk) and a department.
  Insights Q7 and Q13 report by user group. Edit in the grid or upload a CSV with the header
  `user_id,user_group[,display_name][,department]`; existing users are updated
  (example: `samples/users_sample.csv`).
- **Data sources:** JDBC connections used to pull logs from Oracle / Impala. The password is never typed
  here; enter the *name* of the environment variable that holds it. **Test** checks the connection.
- **Custom columns:** add new columns to the tracker (or groups / logs) without a release: text, long text,
  number, date, yes/no or a list of options, optionally required. They appear in the grid, the edit form
  and the Excel export.
- **Prompt templates:** edit the AI prompt. Saving creates a new version and activates it; older versions
  stay for reproducibility and can be re-activated.
- **Loaded files / Import history:** see §3.

---

## 11. FAQ

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

**I uploaded 500 rows and cancelled. Where are they?**
On Query logs, and grouped on Query groups. Cancelling keeps what was loaded and groups it.

**Why does an item say "Awaiting adoption" when it was rejected yesterday?**
A rejection of one iteration sends the item back to *Tuning*. Once the team selects another iteration
it is with the users again. The rejection and its reason remain in History and in Insights Q15.

**Can we change the stages or dropdown values?**
Dropdown values: yes, in Administration → Dropdown values. The workflow stages are fixed in the application
because Insights and the pipeline board depend on them.
