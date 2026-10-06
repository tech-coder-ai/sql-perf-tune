import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { QueryLog } from '../../core/models';
import { MinutesPipe, TimestampPipe } from '../../shared/format';
import { SqlBlock } from '../../shared/sql-block';

@Component({
  selector: 'app-log-detail-dialog',
  imports: [MatDialogModule, MatButtonModule, MatIconModule, RouterLink, SqlBlock, MinutesPipe, TimestampPipe],
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
      </div>
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
})
export class LogDetailDialog {
  readonly log = inject<QueryLog>(MAT_DIALOG_DATA);
}
