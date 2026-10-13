import { Component, DestroyRef, computed, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { Api } from '../../core/api';
import { LookupStore } from '../../core/lookups';
import {
  Feedback,
  Iteration,
  OptimizationRun,
  PromptPreview,
  PromptTemplate,
  QueryGroup,
  SqlDiagnostic,
  TableDdl,
  Tracker,
  AgentInfo,
} from '../../core/models';
import { CaptureStatusBanner } from '../../shared/capture-status';
import { MinutesPipe, TimestampPipe } from '../../shared/format';
import { SqlBlock } from '../../shared/sql-block';
import { StatusChip } from '../../shared/status-chip';
import { GroupMembers } from './group-members';

/** One query group with its tuning workflow artefacts (diagram steps 4-12). */
/** What a missing prompt input is called on screen, and where it is captured. */
const MISSING_LABELS: Record<string, string> = {
  bad_sql: 'bad SQL',
  ddl: 'table DDL (DDL tab)',
  row_counts: 'row counts (DDL tab)',
  explain: 'explain plan (Diagnostics tab)',
  profile_summary: 'query profile (Diagnostics tab)',
  exec_summary: 'execution summary (Diagnostics tab)',
  result_row_count: 'result row count (Diagnostics tab)',
};

@Component({
  selector: 'app-group-detail',
  imports: [
    FormsModule,
    RouterLink,
    MatTabsModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatExpansionModule,
    MatTooltipModule,
    MatProgressSpinnerModule,
    SqlBlock,
    StatusChip,
    GroupMembers,
    CaptureStatusBanner,
    MinutesPipe,
    TimestampPipe,
  ],
  templateUrl: './group-detail.html',
  styleUrl: './group-detail.scss',
})
export class GroupDetail implements OnInit {
  /** route param */
  readonly id = input.required<string>();

  private readonly api = inject(Api);
  private readonly snack = inject(MatSnackBar);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  /** the group's tracker item: carries the status of the automatic diagnostics capture */
  readonly tracker = signal<Tracker | null>(null);
  readonly captureBusy = computed(() => {
    const s = this.tracker()?.captureStatus;
    return s === 'PENDING' || s === 'RUNNING';
  });
  private pollTimer: ReturnType<typeof setTimeout> | null = null;
  /** the agent answering new runs (an automatic one answers by itself; the manual one needs a pasted answer) */
  readonly agent = signal<AgentInfo | null>(null);
  private runTimer: ReturnType<typeof setTimeout> | null = null;

  readonly group = signal<QueryGroup | null>(null);
  readonly diagnostics = signal<SqlDiagnostic[]>([]);
  readonly ddls = signal<TableDdl[]>([]);
  readonly runs = signal<OptimizationRun[]>([]);
  readonly feedback = signal<Feedback[]>([]);
  readonly prompts = signal<PromptTemplate[]>([]);
  readonly iterations = signal<Iteration[]>([]);
  protected readonly lookups = inject(LookupStore);
  readonly busy = signal(false);
  /** prompt of the chosen template, rendered for this group (shown before a run is started) */
  readonly preview = signal<PromptPreview | null>(null);
  /** the run waiting for the LLM answer, if any: its prompt is shown instead of the preview */
  readonly pendingRun = computed(() => this.runs().find((r) => r.status === 'PENDING') ?? null);

  diag = this.emptyDiag();
  ddl = { tableName: '', ddlText: '', rowCount: null as number | null };
  /** 0 = the active template for the group's engine (a null option would render the select as empty) */
  promptTemplateId = 0;
  responses: Record<number, { text: string; model: string }> = {};
  fb = {
    sourceRole: 'CLIENT_DEV',
    decision: 'COMMENT',
    comments: '',
    optimizationRunId: null as number | null,
    iterationId: null as number | null,
    rejectionReason: null as string | null,
  };

  readonly roles = ['BUSINESS_USER', 'CLIENT_DEV', 'CLOUDERA', 'SME'];
  readonly decisions = ['COMMENT', 'ADOPTED', 'REJECTED'];

  get groupId(): number {
    return Number(this.id());
  }

  ngOnInit(): void {
    this.destroyRef.onDestroy(() => {
      if (this.pollTimer) clearTimeout(this.pollTimer);
      if (this.runTimer) clearTimeout(this.runTimer);
    });
    this.api.agent().subscribe((a) => this.agent.set(a));
    this.reload();
    this.api.prompts().subscribe((p) => this.prompts.set(p));
  }

  reload(): void {
    const id = this.groupId;
    forkJoin({
      group: this.api.group(id),
      diagnostics: this.api.diagnostics(id),
      ddls: this.api.ddls(id),
      runs: this.api.runs(id),
      feedback: this.api.feedback(id),
    }).subscribe((r) => {
      this.group.set(r.group);
      this.diagnostics.set(r.diagnostics);
      this.ddls.set(r.ddls);
      this.runs.set(r.runs);
      this.feedback.set(r.feedback);
      this.watchRuns();
      if (r.group.trackerId) {
        this.api.iterations(r.group.trackerId).subscribe((i) => this.iterations.set(i));
        this.watchCapture(r.group.trackerId);
      }
    });
    this.loadPreview();
  }

  /** While the automatic capture runs, re-read the tracker item; reload the page's data when it ends. */
  private watchCapture(trackerId: number): void {
    if (this.pollTimer) clearTimeout(this.pollTimer);
    this.api.tracker(trackerId).subscribe((t) => {
      const wasBusy = this.captureBusy();
      this.tracker.set(t);
      if (this.captureBusy()) {
        this.pollTimer = setTimeout(() => this.watchCapture(trackerId), 2500);
      } else if (wasBusy) {
        this.reload();
      }
    });
  }

  recapture(): void {
    const t = this.tracker();
    if (t) this.api.recapture(t.trackerId).subscribe(() => this.watchCapture(t.trackerId));
  }

  /** Renders the chosen template for this group (called on load and whenever the template changes). */
  loadPreview(): void {
    this.api.promptPreview(this.groupId, this.promptTemplateId || null).subscribe({
      next: (p) => this.preview.set(p),
      error: () => this.preview.set(null),
    });
  }

  missingLabel(key: string): string {
    return MISSING_LABELS[key] ?? key.replaceAll('_', ' ');
  }

  track(): void {
    this.api.trackGroups([this.groupId]).subscribe((t) => this.router.navigate(['/tracker', t[0].trackerId]));
  }

  // ---- diagnostics
  private emptyDiag() {
    return {
      phase: 'ORIGINAL',
      iterationId: null as number | null,
      queryId: '',
      sqlText: '',
      rowCount: null as number | null,
      runDurationSeconds: null as number | null,
      explainPlan: '',
      profileRaw: '',
      execSummary: '',
      status: 'CAPTURED',
      errorMessage: '',
    };
  }

  saveDiagnostic(): void {
    this.busy.set(true);
    this.api.captureDiagnostic(this.groupId, this.diag).subscribe({
      next: () => {
        this.snack.open('Diagnostics captured', undefined, { duration: 2500 });
        this.diag = this.emptyDiag();
        this.busy.set(false);
        this.reload();
      },
      error: () => this.busy.set(false),
    });
  }

  loadFile(e: Event, field: 'profileRaw' | 'explainPlan'): void {
    const f = (e.target as HTMLInputElement).files?.[0];
    if (!f) return;
    f.text().then((t) => (this.diag[field] = t));
  }

  // ---- DDL
  saveDdl(): void {
    this.api.addDdl(this.groupId, this.ddl).subscribe(() => {
      this.ddl = { tableName: '', ddlText: '', rowCount: null };
      this.reload();
    });
  }

  deleteDdl(d: TableDdl): void {
    this.api.deleteDdl(this.groupId, d.id).subscribe(() => this.reload());
  }

  // ---- optimization
  /** True for a run the automatic agent is still answering (the UI waits instead of asking for a paste). */
  waitingForAgent(r: OptimizationRun): boolean {
    const a = this.agent();
    return r.status === 'PENDING' && !!a?.automatic && r.modelName === a.modelName;
  }

  startRun(): void {
    this.busy.set(true);
    this.api.startRun(this.groupId, this.promptTemplateId || undefined).subscribe({
      next: (r) => {
        this.busy.set(false);
        this.snack.open(
          this.waitingForAgent(r)
            ? `Prompt sent to ${r.modelName}; its answer becomes a new iteration`
            : r.status === 'PENDING'
              ? 'Prompt prepared - run it in the LLM tool and paste the answer'
              : 'Agent run ' + r.status.toLowerCase(),
          undefined,
          { duration: 4000 },
        );
        this.reload();
      },
      error: () => this.busy.set(false),
    });
  }

  /** While the agent works on a run, re-read the runs; when it answers, reload (new iteration, tracker stage). */
  private watchRuns(): void {
    if (this.runTimer) clearTimeout(this.runTimer);
    if (!this.runs().some((r) => this.waitingForAgent(r))) return;
    this.runTimer = setTimeout(() => {
      this.api.runs(this.groupId).subscribe((runs) => {
        const done = runs.filter((r) => r.status !== 'PENDING' && this.runs().some((o) => o.id === r.id && this.waitingForAgent(o)));
        if (done.length) {
          const r = done[0];
          this.snack.open(
            r.status === 'COMPLETED' ? `${r.modelName} answered run #${r.id}: new iteration on the tracker` : `Run #${r.id} failed: ${r.errorMessage}`,
            undefined,
            { duration: 5000 },
          );
          this.reload();
        } else {
          this.runs.set(runs);
          this.watchRuns();
        }
      });
    }, 2000);
  }

  responseFor(run: OptimizationRun): { text: string; model: string } {
    return (this.responses[run.id] ??= { text: '', model: '' });
  }

  submitResponse(run: OptimizationRun): void {
    const r = this.responseFor(run);
    this.api.submitRunResponse(this.groupId, run.id, r.text, r.model).subscribe((res) => {
      this.snack.open(res.status === 'COMPLETED' ? 'Optimized SQL stored on the tracker' : 'Response stored, but no SQL block was found', undefined, {
        duration: 4000,
      });
      this.reload();
    });
  }

  // ---- feedback
  saveFeedback(): void {
    this.api.addFeedback(this.groupId, this.fb).subscribe(() => {
      this.fb = { sourceRole: 'CLIENT_DEV', decision: 'COMMENT', comments: '', optimizationRunId: null, iterationId: null, rejectionReason: null };
      this.reload();
    });
  }
}
