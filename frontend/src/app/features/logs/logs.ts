import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Api } from '../../core/api';
import { QueryLog } from '../../core/models';
import { MinutesPipe, TimestampPipe } from '../../shared/format';
import { ImportDialog } from './import-dialog';
import { LogDetailDialog } from './log-detail-dialog';

interface LogFilter {
  q: string;
  userId: string;
  errorCategory: string;
  from: string;
  to: string;
  minDuration: number | null;
  errorsOnly: boolean;
  groupId: number | null;
  batchId: number | null;
}

const EMPTY: LogFilter = {
  q: '',
  userId: '',
  errorCategory: '',
  from: '',
  to: '',
  minDuration: null,
  errorsOnly: false,
  groupId: null,
  batchId: null,
};

@Component({
  selector: 'app-logs',
  imports: [
    FormsModule,
    RouterLink,
    MatTableModule,
    MatPaginatorModule,
    MatSortModule,
    MatFormFieldModule,
    MatInputModule,
    MatCheckboxModule,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule,
    MatProgressBarModule,
    MinutesPipe,
    TimestampPipe,
  ],
  templateUrl: './logs.html',
})
export class Logs implements OnInit {
  private readonly api = inject(Api);
  private readonly dialog = inject(MatDialog);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly columns = [
    'seqId',
    'startTime',
    'endTime',
    'durationMinutes',
    'userId',
    'errorCode',
    'errorCategory',
    'errorMessage',
    'executedQuery',
    'groupId',
  ];
  readonly rows = signal<QueryLog[]>([]);
  readonly total = signal(0);
  readonly loading = signal(false);
  filter: LogFilter = { ...EMPTY };
  pageIndex = 0;
  pageSize = 50;
  sort = 'startTime,desc';

  ngOnInit(): void {
    const qp = this.route.snapshot.queryParamMap;
    this.filter.groupId = qp.get('groupId') ? Number(qp.get('groupId')) : null;
    this.filter.batchId = qp.get('batchId') ? Number(qp.get('batchId')) : null;
    this.load();
  }

  load(): void {
    this.loading.set(true);
    const f = this.filter;
    this.api
      .logs({
        page: this.pageIndex,
        size: this.pageSize,
        sort: this.sort,
        q: f.q,
        userId: f.userId,
        errorCategory: f.errorCategory,
        from: f.from ? f.from + ':00' : null,
        to: f.to ? f.to + ':59' : null,
        minDuration: f.minDuration,
        errorsOnly: f.errorsOnly || null,
        groupId: f.groupId,
        batchId: f.batchId,
      })
      .subscribe({
        next: (p) => {
          this.rows.set(p.content);
          this.total.set(p.totalElements);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
  }

  search(): void {
    this.pageIndex = 0;
    this.load();
  }

  reset(): void {
    this.filter = { ...EMPTY };
    this.router.navigate([], { queryParams: {} });
    this.search();
  }

  onSort(s: Sort): void {
    this.sort = s.direction ? `${s.active},${s.direction}` : 'startTime,desc';
    this.search();
  }

  onPage(e: PageEvent): void {
    this.pageIndex = e.pageIndex;
    this.pageSize = e.pageSize;
    this.load();
  }

  open(row: QueryLog): void {
    this.dialog.open(LogDetailDialog, { data: row, width: '960px', maxWidth: '95vw' });
  }

  importLogs(): void {
    this.dialog
      .open(ImportDialog, { width: '640px', maxWidth: '95vw' })
      .afterClosed()
      .subscribe((r) => r && this.search());
  }
}
