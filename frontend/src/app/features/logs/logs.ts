import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { ActivatedRoute, Router } from '@angular/router';
import { AgGridAngular } from 'ag-grid-angular';
import { ColDef, GridApi, GridOptions, GridReadyEvent, RowClickedEvent } from 'ag-grid-community';
import { Api } from '../../core/api';
import { QueryLog } from '../../core/models';
import {
  LinkCell,
  baseGridOptions,
  minutesFormatter,
  numCol,
  pagedDatasource,
  sqlCol,
  tsFormatter,
} from '../../shared/grid';
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
    AgGridAngular,
    MatFormFieldModule,
    MatInputModule,
    MatCheckboxModule,
    MatButtonModule,
    MatIconModule,
  ],
  templateUrl: './logs.html',
})
export class Logs implements OnInit {
  private readonly api = inject(Api);
  private readonly dialog = inject(MatDialog);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private grid?: GridApi<QueryLog>;

  readonly total = signal(0);
  filter: LogFilter = { ...EMPTY };

  readonly gridOptions: GridOptions<QueryLog> = {
    ...baseGridOptions,
    rowModelType: 'infinite',
    pagination: true,
    paginationPageSize: 50,
    paginationPageSizeSelector: [25, 50, 100],
    cacheBlockSize: 100,
    maxBlocksInCache: 20,
  };

  readonly columns: ColDef<QueryLog>[] = [
    { field: 'seqId', headerName: 'Seq ID', sortable: true, width: 100, pinned: 'left', ...numCol },
    { field: 'startTime', headerName: 'Start time', sortable: true, width: 170, valueFormatter: tsFormatter, sort: 'desc' },
    { field: 'endTime', headerName: 'End time', sortable: true, width: 170, valueFormatter: tsFormatter },
    { field: 'durationMinutes', headerName: 'Duration', sortable: true, width: 115, valueFormatter: minutesFormatter, ...numCol },
    { field: 'userId', headerName: 'User', sortable: true, width: 120 },
    { field: 'errorCode', headerName: 'Error code', sortable: true, width: 170 },
    { field: 'errorCategory', headerName: 'Error category', sortable: true, width: 140 },
    { field: 'errorMessage', headerName: 'Error message', width: 240, tooltip: (p) => p.data?.errorMessage },
    { headerName: 'Executed query', valueGetter: (p) => p.data?.executedQuery || p.data?.userQuery, ...sqlCol, flex: 1, minWidth: 320 },
    {
      field: 'groupId',
      headerName: 'Group',
      sortable: true,
      width: 95,
      cellRenderer: LinkCell,
      cellRendererParams: { link: (r: QueryLog) => (r.groupId ? ['/groups', r.groupId] : null), text: (r: QueryLog) => '#' + r.groupId, tooltip: 'Drill up to group' },
    },
    { field: 'batchId', headerName: 'Import', sortable: true, width: 95, hide: true, ...numCol },
  ];

  ngOnInit(): void {
    const qp = this.route.snapshot.queryParamMap;
    this.filter.groupId = qp.get('groupId') ? Number(qp.get('groupId')) : null;
    this.filter.batchId = qp.get('batchId') ? Number(qp.get('batchId')) : null;
  }

  onReady(e: GridReadyEvent<QueryLog>): void {
    this.grid = e.api;
    e.api.setGridOption(
      'datasource',
      pagedDatasource(
        (page, size, sort) => {
          const f = this.filter;
          return this.api.logs({
            page,
            size,
            sort,
            q: f.q,
            userId: f.userId,
            errorCategory: f.errorCategory,
            from: f.from ? f.from + ':00' : null,
            to: f.to ? f.to + ':59' : null,
            minDuration: f.minDuration,
            errorsOnly: f.errorsOnly || null,
            groupId: f.groupId,
            batchId: f.batchId,
          });
        },
        {},
        (total) => this.total.set(total),
      ),
    );
  }

  search(): void {
    this.grid?.paginationGoToFirstPage();
    this.grid?.purgeInfiniteCache();
  }

  reset(): void {
    this.filter = { ...EMPTY };
    this.router.navigate([], { queryParams: {} });
    this.search();
  }

  open(e: RowClickedEvent<QueryLog>): void {
    if (!e.data) return;
    this.dialog.open(LogDetailDialog, { data: e.data, width: '960px', maxWidth: '95vw' });
  }

  importLogs(): void {
    this.dialog
      .open(ImportDialog, { width: '640px', maxWidth: '95vw' })
      .afterClosed()
      .subscribe((r) => r && this.search());
  }
}
