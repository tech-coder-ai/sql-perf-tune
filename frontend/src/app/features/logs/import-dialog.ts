import { Component, OnDestroy, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { Subscription, interval, switchMap, takeWhile } from 'rxjs';
import { Api } from '../../core/api';
import { DataSource, IngestionBatch } from '../../core/models';
import { StatusChip } from '../../shared/status-chip';

/**
 * Load query logs from a CSV / Excel file or pull them from a configured Oracle / Impala source.
 * Imports run in the background on the server: this dialog shows live progress and can cancel; closing it
 * does not stop the import.
 */
@Component({
  selector: 'app-import-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatSelectModule,
    MatIconModule,
    MatProgressBarModule,
    StatusChip,
  ],
  template: `
    <h2 mat-dialog-title>Import query logs</h2>
    <mat-dialog-content>
      @if (!batch()) {
        <mat-button-toggle-group [(ngModel)]="mode" aria-label="Import source" class="mode">
          <mat-button-toggle value="file"><mat-icon>upload_file</mat-icon> File (CSV / Excel)</mat-button-toggle>
          <mat-button-toggle value="db"><mat-icon>database</mat-icon> Database</mat-button-toggle>
        </mat-button-toggle-group>

        @if (mode === 'file') {
          <div
            class="drop"
            [class.over]="dragOver()"
            (dragover)="$event.preventDefault(); dragOver.set(true)"
            (dragleave)="dragOver.set(false)"
            (drop)="onDrop($event)"
          >
            <mat-icon>cloud_upload</mat-icon>
            <p>
              Drop a <b>.csv</b> or <b>.xlsx</b> file here or
              <button mat-button type="button" (click)="picker.click()">browse</button>
            </p>
            <input #picker type="file" hidden accept=".csv,.txt,.xlsx,.xls" (change)="onPick($event)" />
            @if (file()) {
              <div class="file"><mat-icon>description</mat-icon> {{ file()!.name }} ({{ (file()!.size / 1024).toFixed(0) }} KB)</div>
            }
          </div>
          <p class="muted hint">
            Header: seq_id, executed_query, user_query, error_code, error_category, error_message, user_id, start_time,
            end_time, duration_minutes. Rows already loaded are skipped; an identical file is recognised and not re-processed.
          </p>
          <!-- a file does not say which engine ran its SQL; the engine is part of the grouping key, default Impala -->
          @if (changeEngine()) {
            <mat-form-field>
              <mat-label>SQL engine of this file</mat-label>
              <mat-select [(ngModel)]="engine">
                @for (e of engines; track e) {
                  <mat-option [value]="e">{{ e }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
          } @else {
            <p class="muted engine">
              SQL engine: <b>{{ engine }}</b>
              <button mat-button type="button" (click)="changeEngine.set(true)">change</button>
            </p>
          }
        } @else {
          @if (sources().length === 0) {
            <p class="muted">No data sources configured yet. Add one under Administration → Data sources.</p>
          } @else {
            <mat-form-field class="full">
              <mat-label>Data source</mat-label>
              <mat-select [(ngModel)]="sourceId">
                @for (s of sources(); track s.id) {
                  <mat-option [value]="s.id" [disabled]="!s.active">
                    {{ s.name }} · {{ s.sourceType }} {{ s.lastWatermark ? '(since ' + s.lastWatermark.replace('T', ' ') + ')' : '' }}
                  </mat-option>
                }
              </mat-select>
            </mat-form-field>
            @if (sourceEngine(); as e) {
              <p class="muted engine">SQL engine: <b>{{ e }}</b> (set on the data source in Administration)</p>
            }
          }
        }
      }

      @if (batch(); as b) {
        <div class="progress">
          <div class="row-gap">
            <app-status [value]="b.status === 'FAILED' && cancelled(b) ? 'ON_HOLD' : b.status" [text]="statusText(b)" />
            <b>#{{ b.id }}</b> <span class="muted">{{ b.sourceName }}</span>
          </div>
          @if (b.status === 'RUNNING') {
            <mat-progress-bar mode="indeterminate" />
          }
          <div class="counts">
            <div><span>Read</span><b>{{ b.rowsRead }}</b></div>
            <div><span>New, processed</span><b>{{ b.rowsLoaded }}</b></div>
            <div><span>Already loaded</span><b>{{ b.rowsDuplicate }}</b></div>
            <div><span>Rejected</span><b>{{ b.rowsRejected }}</b></div>
            <div><span>Groups refreshed</span><b>{{ b.groupsAffected }}</b></div>
          </div>
          @if (b.duplicateOfBatchId) {
            <div class="dup">
              <mat-icon>content_copy</mat-icon>
              This exact file was already loaded (import #{{ b.duplicateOfBatchId }}). This is load {{ b.fileLoadNumber }} of the file;
              nothing was re-processed.
            </div>
          }
          @if (b.message) {
            <pre>{{ b.message }}</pre>
          }
          @if (b.status === 'RUNNING') {
            <p class="muted hint">You can close this dialog; the import continues on the server (see Administration → Import history).</p>
          }
        </div>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      @if (batch()?.status === 'RUNNING') {
        <button mat-stroked-button class="danger" (click)="cancel()" [disabled]="cancelling()"><mat-icon>stop_circle</mat-icon> Cancel import</button>
      }
      <button mat-button [mat-dialog-close]="batch()">Close</button>
      @if (!batch()) {
        <button mat-flat-button (click)="run()" [disabled]="busy() || (mode === 'file' ? !file() : !sourceId)">
          <mat-icon>play_arrow</mat-icon> Import
        </button>
      } @else if (batch()!.status !== 'RUNNING') {
        <button mat-flat-button (click)="again()"><mat-icon>upload</mat-icon> Import another</button>
      }
    </mat-dialog-actions>
  `,
  styles: `
    .mode { margin-bottom: 16px; }
    .drop {
      border: 2px dashed var(--spt-border);
      border-radius: 12px;
      padding: 20px;
      text-align: center;
      color: var(--spt-muted);
      &.over { border-color: var(--mat-sys-primary); background: var(--spt-row-hover); }
      mat-icon { font-size: 36px; width: 36px; height: 36px; }
    }
    .file { display: inline-flex; gap: 6px; align-items: center; color: var(--mat-sys-on-surface); }
    .hint { font-size: 12px; }
    .full { width: 100%; }
    .progress { display: flex; flex-direction: column; gap: 10px; }
    .counts { display: grid; grid-template-columns: repeat(5, 1fr); gap: 8px; }
    .counts div { display: flex; flex-direction: column; background: var(--spt-panel-2); border: 1px solid var(--spt-border); border-radius: 8px; padding: 6px 8px; }
    .counts span { font-size: 11px; color: var(--spt-muted); }
    .counts b { font-size: 18px; font-variant-numeric: tabular-nums; }
    .dup { display: flex; gap: 8px; align-items: center; color: var(--spt-warn); }
    pre { max-height: 160px; overflow: auto; font-size: 12px; background: var(--spt-code-bg); padding: 8px; border-radius: 8px; white-space: pre-wrap; margin: 0; }
    .danger { color: var(--spt-bad); }
    .engine { display: flex; align-items: center; gap: 4px; margin: 4px 0 0; }
  `,
})
export class ImportDialog implements OnDestroy {
  private readonly api = inject(Api);

  mode: 'file' | 'db' = 'file';
  engine = 'IMPALA';
  sourceId: number | null = null;
  readonly engines = ['IMPALA', 'HIVE', 'ORACLE', 'SPARK', 'OTHER'];
  readonly file = signal<File | null>(null);
  /** the engine picker for files stays folded until asked for */
  readonly changeEngine = signal(false);
  readonly dragOver = signal(false);
  readonly busy = signal(false);
  readonly cancelling = signal(false);
  readonly batch = signal<IngestionBatch | null>(null);
  readonly sources = signal<DataSource[]>([]);
  private poll?: Subscription;

  constructor() {
    this.api.dataSources().subscribe((s) => this.sources.set(s));
  }

  sourceEngine(): string | null {
    return this.sources().find((s) => s.id === this.sourceId)?.sqlEngine ?? null;
  }

  onPick(e: Event): void {
    const f = (e.target as HTMLInputElement).files?.[0];
    if (f) this.file.set(f);
  }

  onDrop(e: DragEvent): void {
    e.preventDefault();
    this.dragOver.set(false);
    const f = e.dataTransfer?.files?.[0];
    if (f) this.file.set(f);
  }

  run(): void {
    this.busy.set(true);
    const call = this.mode === 'file' ? this.api.upload(this.file()!, this.engine) : this.api.pull(this.sourceId!);
    call.subscribe({
      next: (b) => {
        this.busy.set(false);
        this.batch.set(b);
        this.watch(b.id);
      },
      error: () => this.busy.set(false),
    });
  }

  private watch(id: number): void {
    this.poll?.unsubscribe();
    this.poll = interval(1000)
      .pipe(
        switchMap(() => this.api.batch(id)),
        takeWhile((b) => b.status === 'RUNNING', true),
      )
      .subscribe((b) => this.batch.set(b));
  }

  cancel(): void {
    const b = this.batch();
    if (!b) return;
    this.cancelling.set(true);
    this.api.cancelBatch(b.id).subscribe({ error: () => this.cancelling.set(false) });
  }

  again(): void {
    this.batch.set(null);
    this.file.set(null);
    this.cancelling.set(false);
  }

  cancelled(b: IngestionBatch): boolean {
    return (b.message ?? '').startsWith('Cancelled') || (b.message ?? '').startsWith('Interrupted');
  }

  statusText(b: IngestionBatch): string {
    if (b.status === 'RUNNING') return 'running';
    if (b.status === 'FAILED' && this.cancelled(b)) return 'cancelled';
    return b.status.replaceAll('_', ' ').toLowerCase();
  }

  ngOnDestroy(): void {
    this.poll?.unsubscribe();
  }
}
