import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { Api } from '../../core/api';
import { DataSource, IngestionBatch } from '../../core/models';
import { StatusChip } from '../../shared/status-chip';

/** Load query logs from a CSV / Excel file or pull them from a configured Oracle / Impala source. */
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
          Expected header: seq_id, executed_query, user_query, error_code, error_category, error_message, user_id,
          start_time, end_time, duration_minutes. Missing durations are derived from start/end time.
        </p>
        <mat-form-field>
          <mat-label>SQL engine</mat-label>
          <mat-select [(ngModel)]="engine">
            @for (e of engines; track e) {
              <mat-option [value]="e">{{ e }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
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
        }
      }

      @if (busy()) {
        <mat-progress-bar mode="indeterminate" />
      }
      @if (result(); as r) {
        <div class="result">
          <app-status [value]="r.status" />
          <span>
            {{ r.rowsLoaded }} new · {{ r.rowsDuplicate }} already loaded (not re-processed) · {{ r.rowsRejected }} rejected ·
            {{ r.groupsAffected }} groups refreshed
          </span>
          @if (r.duplicateOfBatchId) {
            <div class="dup">
              <mat-icon>content_copy</mat-icon>
              This exact file was already loaded (import #{{ r.duplicateOfBatchId }}). This is load {{ r.fileLoadNumber }} of the file;
              nothing was re-processed.
            </div>
          }
          @if (r.message) {
            <pre>{{ r.message }}</pre>
          }
        </div>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button [mat-dialog-close]="result()">Close</button>
      <button mat-flat-button (click)="run()" [disabled]="busy() || (mode === 'file' ? !file() : !sourceId)">
        <mat-icon>play_arrow</mat-icon> Import
      </button>
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
    .result { margin-top: 12px; display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
    .dup { width: 100%; display: flex; gap: 8px; align-items: center; color: var(--spt-warn); }
    .result pre { width: 100%; max-height: 160px; overflow: auto; font-size: 12px; background: var(--spt-code-bg); padding: 8px; border-radius: 8px; }
  `,
})
export class ImportDialog {
  private readonly api = inject(Api);
  private readonly ref = inject(MatDialogRef<ImportDialog>);

  mode: 'file' | 'db' = 'file';
  engine = 'IMPALA';
  sourceId: number | null = null;
  readonly engines = ['IMPALA', 'HIVE', 'ORACLE', 'SPARK', 'OTHER'];
  readonly file = signal<File | null>(null);
  readonly dragOver = signal(false);
  readonly busy = signal(false);
  readonly result = signal<IngestionBatch | null>(null);
  readonly sources = signal<DataSource[]>([]);

  constructor() {
    this.api.dataSources().subscribe((s) => this.sources.set(s));
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
    this.result.set(null);
    const call = this.mode === 'file' ? this.api.upload(this.file()!, this.engine) : this.api.pull(this.sourceId!);
    call.subscribe({
      next: (r) => {
        this.result.set(r);
        this.busy.set(false);
      },
      error: () => this.busy.set(false),
    });
  }
}
