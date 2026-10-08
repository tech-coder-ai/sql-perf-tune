import { Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { Api } from '../../core/api';
import { LookupStore } from '../../core/lookups';
import {
  Feedback,
  Iteration,
  OptimizationRun,
  PromptTemplate,
  QueryGroup,
  SqlDiagnostic,
  TableDdl,
} from '../../core/models';
import { MinutesPipe, TimestampPipe } from '../../shared/format';
import { SqlBlock } from '../../shared/sql-block';
import { StatusChip } from '../../shared/status-chip';
import { GroupMembers } from './group-members';

/** One query group with its tuning workflow artefacts (diagram steps 4-12). */
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
    SqlBlock,
    StatusChip,
    GroupMembers,
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

  readonly group = signal<QueryGroup | null>(null);
  readonly diagnostics = signal<SqlDiagnostic[]>([]);
  readonly ddls = signal<TableDdl[]>([]);
  readonly runs = signal<OptimizationRun[]>([]);
  readonly feedback = signal<Feedback[]>([]);
  readonly prompts = signal<PromptTemplate[]>([]);
  readonly iterations = signal<Iteration[]>([]);
  protected readonly lookups = inject(LookupStore);
  readonly busy = signal(false);

  diag = this.emptyDiag();
  ddl = { tableName: '', ddlText: '', rowCount: null as number | null };
  promptTemplateId: number | null = null;
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
      if (r.group.trackerId) {
        this.api.iterations(r.group.trackerId).subscribe((i) => this.iterations.set(i));
      }
    });
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
  startRun(): void {
    this.busy.set(true);
    this.api.startRun(this.groupId, this.promptTemplateId ?? undefined).subscribe({
      next: (r) => {
        this.busy.set(false);
        this.snack.open(
          r.status === 'PENDING' ? 'Prompt prepared - run it in the LLM tool and paste the answer' : 'Agent run ' + r.status.toLowerCase(),
          undefined,
          { duration: 4000 },
        );
        this.reload();
      },
      error: () => this.busy.set(false),
    });
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
