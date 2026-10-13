import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormsModule, NgForm } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { Api } from '../../core/api';
import { LookupStore } from '../../core/lookups';
import {
  AuditEvent,
  CustomField,
  Iteration,
  Journey,
  PRIORITIES,
  SqlDiagnostic,
  TableDdl,
  REQUEST_SOURCES,
  Tracker,
  WORKFLOW_STATUSES,
  WorkflowStatus,
} from '../../core/models';
import { REQUEST_SOURCE_LABEL, STAGE } from '../../core/stages';
import { minutesText, num, secondsText } from '../../shared/dates';
import { MinutesPipe, TimestampPipe } from '../../shared/format';
import { CaptureStatusBanner } from '../../shared/capture-status';
import { deltaText } from '../../shared/grid';
import { JourneyView } from '../../shared/journey';
import { SqlBlock } from '../../shared/sql-block';
import { SqlDiff } from '../../shared/sql-diff';
import { StatusChip } from '../../shared/status-chip';
import { GroupMembers } from '../groups/group-members';
import { DecisionDialog, DecisionData } from './decision-dialog';

/** A SQL that can be compared: the original, an iteration, or the tracker's optimized query. */
interface CompareOption {
  key: string;
  label: string;
  /** short name, e.g. "#2" */
  short: string;
  sql: string | null;
  narrative: string | null;
}

interface MetricRow {
  label: string;
  og: number | null;
  post: number | null;
  fmt: (v: number | null) => string;
  /** lower is better */
  lower: boolean;
}

/** Tabs in the order the work progresses: overview -> triage -> diagnostics -> tune & test -> compare -> results. */
const TAB = { overview: 0, tracking: 1, diagnostics: 2, iterations: 3, compare: 4, results: 5, logRows: 6, history: 7 } as const;

/** How often an item whose diagnostics are still being fetched is re-read. */
const CAPTURE_POLL_MS = 2500;

/** The tab an item opens on: where its current stage's work happens. */
const STAGE_TAB: Record<WorkflowStatus, number> = {
  NEW: TAB.tracking,
  DIAGNOSTICS_CAPTURED: TAB.diagnostics,
  OPTIMIZATION_REQUESTED: TAB.iterations,
  OPTIMIZED: TAB.iterations,
  POST_RUN_VALIDATED: TAB.iterations,
  SME_VALIDATION: TAB.compare,
  ADOPTED: TAB.results,
  REJECTED: TAB.results,
  ON_HOLD: TAB.results,
};

/** Dropdown-backed tracker fields: property -> lookup category + label. */
const DROPDOWNS: { field: keyof Tracker; category: string; label: string }[] = [
  { field: 'devTeamStatus', category: 'DEV_TEAM_STATUS', label: 'Dev team status' },
  { field: 'optimizedSqlStatus', category: 'OPTIMIZED_SQL_STATUS', label: 'Optimized SQL' },
  { field: 'clouderaPostRunValidation', category: 'CLOUDERA_POST_RUN_VALIDATION', label: 'Cloudera post-run validation' },
  { field: 'smeValidation', category: 'SME_VALIDATION', label: 'SME validation' },
  { field: 'installStatus', category: 'INSTALL_STATUS', label: 'Install' },
  { field: 'executeStatus', category: 'EXECUTE_STATUS', label: 'Execute' },
  { field: 'validationStatus', category: 'VALIDATION_STATUS', label: 'Validation' },
];

/**
 * One SQL on its way to adoption: journey, before/after, tuning iterations (best one selected and adopted),
 * tracking fields, queries, log rows and history.
 */
@Component({
  selector: 'app-tracker-detail',
  imports: [
    FormsModule,
    RouterLink,
    MatTabsModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatCheckboxModule,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule,
    SqlBlock,
    SqlDiff,
    CaptureStatusBanner,
    StatusChip,
    GroupMembers,
    JourneyView,
    MinutesPipe,
    TimestampPipe,
  ],
  templateUrl: './tracker-detail.html',
  styleUrl: './tracker-detail.scss',
})
export class TrackerDetail implements OnInit {
  readonly id = input.required<string>();
  private readonly api = inject(Api);
  private readonly snack = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly lookups = inject(LookupStore);

  protected readonly statuses = WORKFLOW_STATUSES;
  protected readonly priorities = PRIORITIES;
  protected readonly sources = REQUEST_SOURCES;
  protected readonly sourceLabel = REQUEST_SOURCE_LABEL;
  protected readonly stage = STAGE;
  protected readonly dropdowns = DROPDOWNS;
  protected readonly num = num;
  protected readonly deltaText = deltaText;

  /** iteration improvement (positive = better) as a change from the original (negative = less) */
  protected negate(v: number | null): number | null {
    return v === null ? null : -v;
  }

  protected deltaOf(og: number | null, post: number | null): number | null {
    return og === null || post === null || og === 0 ? null : Math.round(((post - og) / og) * 1000) / 10;
  }

  protected readonly saved = signal<Tracker | null>(null);
  protected readonly journey = signal<Journey | null>(null);
  protected readonly iterations = signal<Iteration[]>([]);
  protected readonly history = signal<AuditEvent[]>([]);
  protected readonly customFields = signal<CustomField[]>([]);
  protected readonly diagnostics = signal<SqlDiagnostic[]>([]);
  protected readonly ddls = signal<TableDdl[]>([]);
  /** latest captured diagnostic of the original SQL */
  protected readonly originalDiag = computed(
    () => this.diagnostics().find((d) => d.phase === 'ORIGINAL' && d.status === 'CAPTURED') ?? null,
  );
  /** explain / profile / DDL still being fetched in the background */
  protected readonly captureBusy = computed(() => {
    const s = this.saved()?.captureStatus;
    return s === 'PENDING' || s === 'RUNNING';
  });
  private pollTimer: ReturnType<typeof setTimeout> | null = null;
  protected readonly saving = signal(false);
  protected readonly TAB = TAB;
  protected readonly tab = signal<number>(TAB.tracking);
  /** the first load opens the tab of the item's current stage; later reloads keep the user's tab */
  private stageTabChosen = false;
  /** working copy bound to the form (fields mutated in place by ngModel) */
  protected readonly model = signal<Tracker | null>(null);

  /** iteration being edited in the Iterations tab (copy) */
  protected readonly editing = signal<Iteration | null>(null);
  protected newIteration: { optimizedSql: string; changeNarrative: string; notes: string } | null = null;

  protected readonly best = computed(() => this.iterations().find((i) => i.best) ?? null);

  // ---- Compare SQL tab: original vs an iteration (or any two)
  protected readonly compareLeft = signal<string | null>(null);
  protected readonly compareRight = signal<string | null>(null);

  protected readonly compareOptions = computed<CompareOption[]>(() => {
    const t = this.saved();
    if (!t) return [];
    const options: CompareOption[] = [
      { key: 'original', label: 'Original query', short: 'the original', sql: t.sampleQueryRaw, narrative: null },
    ];
    for (const i of this.iterations()) {
      if (!i.optimizedSql) continue;
      const tags = [i.selected ? 'selected' : '', i.best ? 'best' : '', i.status === 'REJECTED' ? 'rejected' : '']
        .filter(Boolean)
        .join(', ');
      options.push({
        key: 'it:' + i.id,
        label: `Iteration #${i.iterationNo}${tags ? ' (' + tags + ')' : ''}`,
        short: '#' + i.iterationNo,
        sql: i.optimizedSql,
        narrative: i.changeNarrative,
      });
    }
    // optimized SQL entered directly on the tracker (no iteration carries it)
    if (t.optimizedQuery && !this.iterations().some((i) => i.optimizedSql === t.optimizedQuery)) {
      options.push({ key: 'tracker', label: 'Optimized query (tracker)', short: 'the optimized query', sql: t.optimizedQuery, narrative: null });
    }
    return options;
  });

  /** Defaults: original vs the selected iteration, else the best, else the latest. */
  protected readonly leftKey = computed(() => this.validKey(this.compareLeft()) ?? 'original');
  protected readonly rightKey = computed(() => {
    const chosen = this.validKey(this.compareRight());
    if (chosen) return chosen;
    const its = this.iterations().filter((i) => i.optimizedSql);
    const pick = its.find((i) => i.selected) ?? its.find((i) => i.best) ?? its[its.length - 1];
    return pick ? 'it:' + pick.id : (this.compareOptions().find((o) => o.key === 'tracker')?.key ?? 'original');
  });
  protected readonly leftOption = computed(() => this.compareOptions().find((o) => o.key === this.leftKey()) ?? null);
  protected readonly rightOption = computed(() => this.compareOptions().find((o) => o.key === this.rightKey()) ?? null);
  protected readonly selected = computed(() => this.iterations().find((i) => i.selected) ?? null);

  protected readonly metrics = computed<MetricRow[]>(() => {
    const t = this.saved();
    if (!t) return [];
    return [
      { label: 'Run duration', og: t.ogRunDurationMinutes, post: t.postRunDurationMinutes, fmt: minutesText, lower: true },
      { label: 'Execution time', og: t.ogExecutionTimeSeconds, post: t.postRunExecutionTimeSeconds, fmt: secondsText, lower: true },
      { label: 'Teardown time', og: t.ogTeardownTimeSeconds, post: t.postRunTeardownTimeSeconds, fmt: secondsText, lower: true },
      { label: 'Impala CPU time', og: t.ogCpuSeconds, post: t.postRunCpuSeconds, fmt: secondsText, lower: true },
      { label: 'Rows scanned', og: t.ogRowsScanned, post: t.postRunRowsScanned, fmt: num, lower: true },
      { label: 'Tables scanned', og: t.ogTablesScanned, post: t.postRunTablesScanned, fmt: num, lower: true },
      {
        label: 'Peak memory',
        og: t.ogPeakMemoryMb,
        post: t.postRunPeakMemoryMb,
        fmt: (v) => (v === null ? '—' : `${num(Math.round(v))} MB`),
        lower: true,
      },
    ];
  });

  /** What the user should do next, by stage. */
  protected readonly nextStep = computed(() => {
    const t = this.saved();
    if (!t) return null;
    const best = this.best();
    switch (t.workflowStatus) {
      case 'NEW':
        return t.captureStatus === 'PENDING' || t.captureStatus === 'RUNNING'
          ? 'Triage while the explain plan, profile and DDL are fetched: set priority, theme and owners on the Tracking tab.'
          : 'Analyse the SQL: set a theme and capture the explain plan / profile of the original run on the group page.';
      case 'DIAGNOSTICS_CAPTURED':
        return 'Review the diagnostics, then run the optimization agent on the group page or add a manual iteration with the rewritten SQL.';
      case 'OPTIMIZATION_REQUESTED':
        return 'Waiting for a candidate SQL: paste the agent answer or add a manual iteration.';
      case 'OPTIMIZED':
        return 'Test the candidate iteration(s): capture the post-run profile or enter the test results.';
      case 'POST_RUN_VALIDATED':
        return best
          ? `Iteration #${best.iterationNo} is the best so far (${best.durationImprovementPct ?? '—'}% faster). Select it for adoption.`
          : 'Compare the tested iterations and select the best one for adoption.';
      case 'SME_VALIDATION':
        return 'With the users / SMEs: record whether the selected iteration was adopted, or reject it with a reason.';
      case 'ADOPTED':
        return 'Adopted. Savings are counted in Insights (Q5) from the adoption date.';
      case 'REJECTED':
        return 'Closed without adoption.';
      default:
        return 'On hold.';
    }
  });

  ngOnInit(): void {
    this.load();
    this.api.customFields('TRACKER').subscribe((f) => this.customFields.set(f.filter((x) => x.active)));
    this.destroyRef.onDestroy(() => this.stopPolling());
  }

  load(): void {
    const id = Number(this.id());
    this.api.tracker(id).subscribe((t) => {
      this.saved.set(t);
      this.model.set(this.copy(t));
      if (!this.stageTabChosen) {
        this.stageTabChosen = true;
        this.tab.set(STAGE_TAB[t.workflowStatus] ?? TAB.overview);
      }
      this.loadDiagnostics(t.groupId);
      this.pollWhileCapturing(t);
    });
    this.api.journey(id).subscribe((j) => this.journey.set(j));
    this.api.iterations(id).subscribe((i) => this.iterations.set(i));
    this.api.trackerHistory(id).subscribe((h) => this.history.set(h));
  }

  private loadDiagnostics(groupId: number): void {
    this.api.diagnostics(groupId).subscribe((d) => this.diagnostics.set(d));
    this.api.ddls(groupId).subscribe((d) => this.ddls.set(d));
  }

  // ------------------------------------------------------------------ automatic diagnostics capture

  /** Fetch explain plan, profile and DDL again (after a failure, or to refresh them). */
  recapture(): void {
    const t = this.saved();
    if (!t) return;
    this.api.recapture(t.trackerId).subscribe((u) => {
      this.saved.set({ ...t, captureStatus: u.captureStatus, captureRequestedAt: u.captureRequestedAt, captureMessage: null });
      this.pollWhileCapturing(u);
    });
  }

  /** While the capture runs, re-read the item; when it ends, bring in what it filled without losing unsaved edits. */
  private pollWhileCapturing(t: Tracker): void {
    this.stopPolling();
    if (t.captureStatus !== 'PENDING' && t.captureStatus !== 'RUNNING') return;
    this.pollTimer = setTimeout(() => {
      this.api.tracker(t.trackerId).subscribe((fresh) => {
        const before = this.saved();
        if (!before) return;
        if (fresh.captureStatus === 'PENDING' || fresh.captureStatus === 'RUNNING') {
          this.saved.set({ ...before, captureStatus: fresh.captureStatus, captureRequestedAt: fresh.captureRequestedAt });
          this.pollWhileCapturing(fresh);
          return;
        }
        this.merge(before, fresh);
        this.loadDiagnostics(fresh.groupId);
        this.api.journey(fresh.trackerId).subscribe((j) => this.journey.set(j));
        this.api.trackerHistory(fresh.trackerId).subscribe((h) => this.history.set(h));
        if (fresh.captureStatus === 'DONE') {
          this.snack.open('Diagnostics captured: explain plan, profile and DDL are in', 'Show', { duration: 5000 })
            .onAction()
            .subscribe(() => this.tab.set(TAB.diagnostics));
        }
      });
    }, CAPTURE_POLL_MS);
  }

  /**
   * Three-way merge into the form: fields the user has not touched take the server's new values (metrics, stage),
   * fields the user changed keep the user's value; the version moves on so the next save is not a conflict.
   */
  private merge(before: Tracker, fresh: Tracker): void {
    const m = this.model();
    this.saved.set(fresh);
    if (!m) {
      this.model.set(this.copy(fresh));
      return;
    }
    const mine = m as unknown as Record<string, unknown>;
    const was = before as unknown as Record<string, unknown>;
    const now = fresh as unknown as Record<string, unknown>;
    for (const k of Object.keys(now)) {
      if (k === 'customFields') continue;
      if (JSON.stringify(mine[k]) === JSON.stringify(was[k])) mine[k] = now[k];
    }
    m.version = fresh.version;
    this.model.set(m);
  }

  private stopPolling(): void {
    if (this.pollTimer) clearTimeout(this.pollTimer);
    this.pollTimer = null;
  }

  protected engineLabel(t: Tracker): string {
    return t.sqlEngine === 'IMPALA' ? 'Impala' : t.sqlEngine;
  }

  // ------------------------------------------------------------------ tracking form

  save(form: NgForm): void {
    const m = this.model();
    if (!m || form.invalid) return;
    this.saving.set(true);
    this.api.updateTracker(m.trackerId, m).subscribe({
      next: () => {
        this.saving.set(false);
        form.form.markAsPristine();
        this.load();
        this.snack.open('Saved', undefined, { duration: 2000 });
      },
      error: (e: HttpErrorResponse) => {
        this.saving.set(false);
        if (e.status === 409) {
          this.snack.open('Someone else changed this item. Reloaded the latest version.', 'OK', { duration: 6000 });
          this.load();
        }
      },
    });
  }

  discard(form: NgForm): void {
    const t = this.saved();
    if (t) this.model.set(this.copy(t));
    form.form.markAsPristine();
  }

  options(f: CustomField): string[] {
    return (f.optionsCsv ?? '').split(',').map((s) => s.trim()).filter(Boolean);
  }

  get(m: Tracker, field: keyof Tracker): string | null {
    return m[field] as string | null;
  }

  set(m: Tracker, field: keyof Tracker, v: string | null): void {
    (m as unknown as Record<string, unknown>)[field] = v;
  }

  // ------------------------------------------------------------------ iterations

  compareWith(i: Iteration): void {
    this.compareLeft.set('original');
    this.compareRight.set('it:' + i.id);
    this.tab.set(TAB.compare);
  }

  swapCompare(): void {
    const l = this.leftKey();
    const r = this.rightKey();
    this.compareLeft.set(r);
    this.compareRight.set(l);
  }

  private validKey(key: string | null): string | null {
    return key && this.compareOptions().some((o) => o.key === key) ? key : null;
  }

  startNewIteration(): void {
    this.tab.set(TAB.iterations);
    this.newIteration = { optimizedSql: '', changeNarrative: '', notes: '' };
  }

  addIteration(): void {
    const t = this.saved();
    if (!t || !this.newIteration) return;
    this.api.addIteration(t.trackerId, this.newIteration).subscribe((i) => {
      this.newIteration = null;
      this.snack.open(`Iteration #${i.iterationNo} added`, undefined, { duration: 2500 });
      this.load();
      this.edit(i);
    });
  }

  edit(i: Iteration): void {
    this.editing.set(structuredClone(i));
  }

  saveIteration(): void {
    const t = this.saved();
    const i = this.editing();
    if (!t || !i) return;
    this.api.updateIteration(t.trackerId, i.id, i).subscribe((u) => {
      this.snack.open(`Iteration #${u.iterationNo} saved`, undefined, { duration: 2000 });
      this.editing.set(null);
      this.load();
    });
  }

  select(i: Iteration): void {
    const t = this.saved();
    if (!t) return;
    this.api.selectIteration(t.trackerId, i.id).subscribe(() => {
      this.snack.open(`Iteration #${i.iterationNo} selected for adoption`, undefined, { duration: 3000 });
      this.load();
    });
  }

  decide(decision: 'ADOPTED' | 'REJECTED', iteration?: Iteration): void {
    const t = this.saved();
    if (!t) return;
    const data: DecisionData = {
      decision,
      groupId: t.groupId,
      iterations: this.iterations(),
      iterationId: iteration?.id ?? this.selected()?.id ?? this.best()?.id ?? null,
    };
    this.dialog
      .open(DecisionDialog, { data, width: '620px', maxWidth: '95vw' })
      .afterClosed()
      .subscribe((ok) => {
        if (ok) {
          this.snack.open(decision === 'ADOPTED' ? 'Marked as adopted' : 'Rejection recorded', undefined, { duration: 3000 });
          this.load();
        }
      });
  }

  // ------------------------------------------------------------------ helpers

  change(r: MetricRow): { text: string; good: boolean | null } {
    if (r.og === null || r.post === null || r.og === 0) return { text: '—', good: null };
    const d = this.deltaOf(r.og, r.post)!;
    return { text: deltaText(d), good: d === 0 ? null : r.lower ? d < 0 : d > 0 };
  }

  fieldLabel(name: string | null): string {
    if (!name) return '';
    if (name.startsWith('custom.')) {
      const key = name.substring(7);
      return this.customFields().find((f) => f.fieldKey === key)?.label ?? key;
    }
    return name.replace(/([A-Z])/g, ' $1').toLowerCase();
  }

  historyValue(field: string | null, v: string | null): string {
    if (v === null) return '∅';
    if (field === 'workflowStatus') return STAGE[v as keyof typeof STAGE]?.label ?? v;
    return v;
  }

  private copy(t: Tracker): Tracker {
    return structuredClone({ ...t, customFields: { ...(t.customFields ?? {}) } });
  }
}
