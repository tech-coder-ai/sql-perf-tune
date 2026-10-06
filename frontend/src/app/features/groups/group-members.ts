import { Component, OnInit, inject, input, signal } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Api } from '../../core/api';
import { QueryLog } from '../../core/models';
import { MinutesPipe, TimestampPipe } from '../../shared/format';
import { LogDetailDialog } from '../logs/log-detail-dialog';

/** Drill-down: the original log rows that make up a group (lazy, paged). */
@Component({
  selector: 'app-group-members',
  imports: [MatTableModule, MatPaginatorModule, MatSortModule, MatProgressBarModule, MatTooltipModule, MinutesPipe, TimestampPipe],
  template: `
    @if (loading()) {
      <mat-progress-bar mode="indeterminate" />
    }
    <table mat-table [dataSource]="rows()" matSort (matSortChange)="onSort($event)" class="members">
      <ng-container matColumnDef="seqId">
        <th mat-header-cell *matHeaderCellDef mat-sort-header>Seq ID</th>
        <td mat-cell *matCellDef="let r" class="num">{{ r.seqId }}</td>
      </ng-container>
      <ng-container matColumnDef="userId">
        <th mat-header-cell *matHeaderCellDef mat-sort-header>User</th>
        <td mat-cell *matCellDef="let r">{{ r.userId }}</td>
      </ng-container>
      <ng-container matColumnDef="startTime">
        <th mat-header-cell *matHeaderCellDef mat-sort-header>Start time</th>
        <td mat-cell *matCellDef="let r" class="nowrap">{{ r.startTime | ts }}</td>
      </ng-container>
      <ng-container matColumnDef="durationMinutes">
        <th mat-header-cell *matHeaderCellDef mat-sort-header>Duration</th>
        <td mat-cell *matCellDef="let r" class="num">{{ r.durationMinutes | minutes }}</td>
      </ng-container>
      <ng-container matColumnDef="error">
        <th mat-header-cell *matHeaderCellDef>Error</th>
        <td mat-cell *matCellDef="let r" class="nowrap">{{ r.errorCode }} {{ r.errorCategory ? '· ' + r.errorCategory : '' }}</td>
      </ng-container>
      <ng-container matColumnDef="executedQuery">
        <th mat-header-cell *matHeaderCellDef>Executed query</th>
        <td mat-cell *matCellDef="let r"><div class="cell-sql">{{ r.executedQuery || r.userQuery }}</div></td>
      </ng-container>
      <tr mat-header-row *matHeaderRowDef="cols"></tr>
      <tr mat-row *matRowDef="let row; columns: cols" class="clickable" (click)="open(row)"></tr>
    </table>
    <mat-paginator [length]="total()" [pageSize]="size" [pageSizeOptions]="[10, 20, 50]" (page)="onPage($event)" />
  `,
  styles: `
    .members { background: transparent; }
  `,
})
export class GroupMembers implements OnInit {
  readonly groupId = input.required<number>();
  private readonly api = inject(Api);
  private readonly dialog = inject(MatDialog);

  readonly cols = ['seqId', 'userId', 'startTime', 'durationMinutes', 'error', 'executedQuery'];
  readonly rows = signal<QueryLog[]>([]);
  readonly total = signal(0);
  readonly loading = signal(false);
  page = 0;
  size = 10;
  sort = '';

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.api.groupLogs(this.groupId(), { page: this.page, size: this.size, sort: this.sort }).subscribe({
      next: (p) => {
        this.rows.set(p.content);
        this.total.set(p.totalElements);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  onSort(s: Sort): void {
    this.sort = s.direction ? `${s.active},${s.direction}` : '';
    this.page = 0;
    this.load();
  }

  onPage(e: PageEvent): void {
    this.page = e.pageIndex;
    this.size = e.pageSize;
    this.load();
  }

  open(row: QueryLog): void {
    this.dialog.open(LogDetailDialog, { data: row, width: '960px', maxWidth: '95vw' });
  }
}
