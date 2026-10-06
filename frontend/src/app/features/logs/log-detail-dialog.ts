import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { Api } from '../../core/api';
import { LoadHistoryEntry, QueryLog } from '../../core/models';
import { StatusChip } from '../../shared/status-chip';
import { MinutesPipe, TimestampPipe } from '../../shared/format';
import { SqlBlock } from '../../shared/sql-block';

@Component({
  selector: 'app-log-detail-dialog',
  imports: [MatDialogModule, MatButtonModule, MatIconModule, RouterLink, SqlBlock, StatusChip, MinutesPipe, TimestampPipe],
  template: `
    <h2 mat-dialog-title>Log row · seq {{ log.seqId ?? log.id }}</h2>
    <mat-dialog-content>
      <div class="kv-grid">
        <div class="kv"><div class="k">User</div><div class="v">{{ log.userId || '—' }}</div></div>
        <div class="kv"><div class="k">Start</div><div class="v">{{ log.startTime | ts }}</div></div>
        <div class="kv"><div class="k">End</div><div class="v">{{ log.endTime | ts }}</div></div>
        <div class="kv"><div class="k">Duration</div><div class="v">{{ log.durationMinutes | minutes }}</div></div>
        <div class="kv"><div class="k">Error</div><div class="v">{{ log.errorCode || '—' }} {{ log.errorCategory ? '· ' + log.errorCategory : '' }}</div></div>
        <div class="kv"><div class="k">Group</div><div class="v">{{ log.groupId ?? '—' }}</div></div>
        <div class="kv"><div class="k">Loads</div><div class="v">{{ log.seenCount }}×</div></div>
        <div class="kv"><div class="k">First loaded</div><div class="v">{{ log.createdAt | ts }}</div></div>
      </div>
      <h3 class="section-title">Load history</h3>
      <p class="muted">The row was processed (fingerprinted and grouped) only by its first load; later loads just recorded that it was seen again.</p>
      <table class="history">
        <tr><th>Import</th><th>Seen at</th><th>Source</th><th></th></tr>
        @for (h of history(); track h.batchId) {
          <tr>
            <td>#{{ h.batchId }}</td>
            <td>{{ h.seenAt | ts }}</td>
            <td>{{ h.sourceKind }} · {{ h.sourceName }}</td>
            <td>
              @if (h.first) {
                <app-status value="PROCESSED" />
              } @else if (h.duplicateOfBatchId) {
                <app-status value="DUPLICATE_FILE" /> <span class="muted">same file as #{{ h.duplicateOfBatchId }}</span>
              } @else {
                <app-status value="ALREADY_LOADED" /> <span class="muted">skipped</span>
              }
            </td>
          </tr>
        }
      </table>
      @if (log.errorMessage) {
        <h3 class="section-title">Error message</h3>
        <app-sql-block [text]="log.errorMessage" maxHeight="140px" />
      }
      <h3 class="section-title">Executed query</h3>
      <app-sql-block [text]="log.executedQuery" label="executed_query" />
      @if (log.userQuery && log.userQuery !== log.executedQuery) {
        <h3 class="section-title">User query</h3>
        <app-sql-block [text]="log.userQuery" label="user_query" />
      }
      <p class="muted mono">fingerprint {{ log.fingerprint || '—' }}</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      @if (log.groupId) {
        <a mat-stroked-button [routerLink]="['/groups', log.groupId]" mat-dialog-close>
          <mat-icon>arrow_upward</mat-icon> Drill up to group
        </a>
      }
      <button mat-flat-button mat-dialog-close>Close</button>
    </mat-dialog-actions>
  `,
  styles: `
    .history { width: 100%; border-collapse: collapse; font-size: 13px; }
    .history th { text-align: left; color: var(--spt-muted); font-weight: 600; }
    .history th, .history td { padding: 6px 8px; border-bottom: 1px solid var(--spt-border); }
  `,
})
export class LogDetailDialog {
  readonly log = inject<QueryLog>(MAT_DIALOG_DATA);
  readonly history = signal<LoadHistoryEntry[]>([]);

  constructor() {
    inject(Api).logHistory(this.log.id).subscribe((h) => this.history.set(h));
  }
}
