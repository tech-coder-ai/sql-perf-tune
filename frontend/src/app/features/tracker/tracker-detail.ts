import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
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
  REQUEST_SOURCES,
  Tracker,
  WORKFLOW_STATUSES,
} from '../../core/models';
import { REQUEST_SOURCE_LABEL, STAGE } from '../../core/stages';
import { minutesText, num, secondsText } from '../../shared/dates';
import { MinutesPipe, TimestampPipe } from '../../shared/format';
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
  protected readonly saving = signal(false);
  protected readonly tab = signal(0);
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
        return 'Analyse the SQL: set a theme and capture the explain plan / profile of the original run on the group page.';
      case 'DIAGNOSTICS_CAPTURED':
        return 'Run the optimization agent on the group page or add a manual iteration with the rewritten SQL.';
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
  }

  load(): void {
    const id = Number(this.id());
    this.api.tracker(id).subscribe((t) => {
      this.saved.set(t);
      this.model.set(this.copy(t));
    });
    this.api.journey(id).subscribe((j) => this.journey.set(j));
    this.api.iterations(id).subscribe((i) => this.iterations.set(i));
    this.api.trackerHistory(id).subscribe((h) => this.history.set(h));
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
    this.tab.set(2);
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
    this.tab.set(1);
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
