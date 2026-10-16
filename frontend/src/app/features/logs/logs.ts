import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router } from '@angular/router';
import { AgGridAngular } from 'ag-grid-angular';
import { ColDef, GridApi, GridOptions, GridReadyEvent, RowClickedEvent } from 'ag-grid-community';
import { Api } from '../../core/api';
import { WorkflowSettingsStore } from '../../core/workflow-settings';
import { QueryLog } from '../../core/models';
import {
  LinkCell,
  serverGridOptions,
  minutesFormatter,
  numCol,
  groupingDatasource,
  isGroupRow,
  StatusCell,
  sqlCol,
  tsFormatter,
  errorCellRules,
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
  reloadedOnly: boolean;
  groupId: number | null;
  batchId: number | null;
  /** exact user (dashboard links); userId above is a "contains" search */
  user: string | null;
  userGroup: string | null;
  pattern: 'RECURRING' | 'NEW' | null;
  /** also rows that are not bad queries (default: bad queries only) */
  includeNormal: boolean;
}

const EMPTY: LogFilter = {
  q: '',
  userId: '',
  errorCategory: '',
  from: '',
  to: '',
  minDuration: null,
  errorsOnly: false,
  reloadedOnly: false,
  groupId: null,
  batchId: null,
  user: null,
  userGroup: null,
  pattern: null,
  includeNormal: false,
};

/** A filter that came with the link (shown as a removable chip). */
interface LinkFilter {
  key: 'user' | 'userGroup' | 'pattern' | 'groupId' | 'batchId';
  label: string;
}

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
    MatTooltipModule,
    MatChipsModule,
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
  protected readonly settings = inject(WorkflowSettingsStore);
  filter: LogFilter = { ...EMPTY };
  /** filters set by the link that opened the screen (from the Command center, Insights, a group, an import) */
  readonly linkFilters = signal<LinkFilter[]>([]);

  readonly gridOptions: GridOptions<QueryLog> = serverGridOptions<QueryLog>(50);

  /** bad = longer than the admin threshold (Administration → Workflow settings) */
  private isBad(minutes: number | null | undefined): boolean {
    return minutes !== null && minutes !== undefined && minutes > this.settings.badMinMinutes();
  }

  readonly columns: ColDef<QueryLog>[] = [
    { field: 'seqId', headerName: 'Seq ID', sortable: true, width: 100, pinned: 'left', ...numCol },
    {
      colId: 'class',
      headerName: 'Class',
      headerTooltip: 'Bad query: ran longer than the threshold set in Administration → Workflow settings',
      width: 100,
      pinned: 'left',
      valueGetter: (p) => (!p.data || isGroupRow(p.data) ? null : this.isBad(p.data.durationMinutes) ? 'BAD' : 'NORMAL'),
      cellRenderer: StatusCell,
    },
    { field: 'startTime', headerName: 'Start time', sortable: true, width: 170, valueFormatter: tsFormatter, sort: 'desc' },
    { field: 'endTime', headerName: 'End time', sortable: true, width: 170, valueFormatter: tsFormatter },
    {
      field: 'durationMinutes',
      headerName: 'Duration',
      sortable: true,
      width: 115,
      valueFormatter: minutesFormatter,
      ...numCol,
      cellClassRules: { 'ag-bad-duration': (p) => this.isBad(p.value) },
    },
    {
      field: 'seenCount',
      headerName: 'Loads',
      headerTooltip: 'How many loads contained this row. It was processed only by the first one.',
      sortable: true,
      width: 95,
      ...numCol,
      cellClassRules: { 'ag-reloaded': (p) => (p.value ?? 1) > 1 },
    },
    { field: 'createdAt', headerName: 'First loaded', sortable: true, width: 170, valueFormatter: tsFormatter, hide: true },
    { field: 'lastSeenAt', headerName: 'Last seen', sortable: true, width: 170, valueFormatter: tsFormatter },
    {
      colId: 'startDay',
      headerName: 'Start day',
      headerTooltip: 'Day of the start time (load time when there is none); group by it to see the rows per day',
      valueGetter: (p) => (p.data as Record<string, unknown> | undefined)?.['startDay'] ?? (p.data?.startTime ?? p.data?.createdAt ?? '').substring(0, 10),
      width: 120,
      hide: true,
      enableRowGroup: true,
    },
    { field: 'userId', headerName: 'User', sortable: true, width: 120, enableRowGroup: true },
    { field: 'errorCode', headerName: 'Error code', sortable: true, width: 215, cellClassRules: errorCellRules, enableRowGroup: true },
    {
      field: 'errorCategory',
      headerName: 'Error category',
      sortable: true,
      width: 140,
      cellClassRules: errorCellRules,
      enableRowGroup: true,
    },
    { field: 'errorMessage', headerName: 'Error message', width: 240, tooltip: (p) => p.data?.errorMessage, cellClassRules: errorCellRules },
    { headerName: 'Executed query', valueGetter: (p) => p.data?.executedQuery || p.data?.userQuery, ...sqlCol, flex: 1, minWidth: 320 },
    {
      field: 'groupId',
      headerName: 'Group',
      sortable: true,
      width: 95,
      enableRowGroup: true,
      cellRenderer: LinkCell,
      cellRendererParams: { link: (r: QueryLog) => (r.groupId ? ['/groups', r.groupId] : null), text: (r: QueryLog) => '#' + r.groupId, tooltip: 'Drill up to group' },
    },
    { field: 'batchId', headerName: 'Import', sortable: true, width: 95, hide: true, enableRowGroup: true, ...numCol },
  ];

  ngOnInit(): void {
    this.settings.load();
    // links from the dashboards (and from groups / imports) carry their filters in the URL
    this.route.queryParamMap.subscribe((qp) => {
      const num = (k: string) => (qp.get(k) ? Number(qp.get(k)) : null);
      const pattern = qp.get('pattern')?.toUpperCase();
      this.filter = {
        ...EMPTY,
        groupId: num('groupId'),
        batchId: num('batchId'),
        userId: qp.get('userId') ?? '',
        user: qp.get('user'),
        userGroup: qp.get('userGroup'),
        pattern: pattern === 'RECURRING' || pattern === 'NEW' ? pattern : null,
        from: qp.get('from') ?? '',
        to: qp.get('to') ?? '',
        errorsOnly: qp.get('errorsOnly') === 'true',
        includeNormal: qp.get('includeNormal') === 'true',
      };
      this.linkFilters.set(this.describeLinkFilters());
      if (this.grid) this.search();
    });
  }

  private describeLinkFilters(): LinkFilter[] {
    const f = this.filter;
    const out: LinkFilter[] = [];
    if (f.user) out.push({ key: 'user', label: `User is ${f.user}` });
    if (f.userGroup) out.push({ key: 'userGroup', label: `User group: ${f.userGroup}` });
    if (f.pattern) {
      out.push({
        key: 'pattern',
        label: f.pattern === 'RECURRING' ? 'Recurring patterns (seen before this day)' : 'New patterns (first seen this day)',
      });
    }
    if (f.groupId) out.push({ key: 'groupId', label: `Group #${f.groupId}` });
    if (f.batchId) out.push({ key: 'batchId', label: `Import #${f.batchId}` });
    return out;
  }

  /** Removes one of the link's filters. */
  dropLinkFilter(key: LinkFilter['key']): void {
    this.filter = { ...this.filter, [key]: null };
    this.linkFilters.set(this.describeLinkFilters());
    this.search();
  }

  private params(): Record<string, unknown> {
    const f = this.filter;
    return {
      q: f.q,
      userId: f.userId,
      user: f.user,
      userGroup: f.userGroup,
      pattern: f.pattern && f.from ? f.pattern : null,
      errorCategory: f.errorCategory,
      from: f.from ? f.from + ':00' : null,
      to: f.to ? f.to + ':59' : null,
      minDuration: f.minDuration,
      errorsOnly: f.errorsOnly || null,
      reloadedOnly: f.reloadedOnly || null,
      groupId: f.groupId,
      batchId: f.batchId,
      includeNormal: f.includeNormal || null,
    };
  }

  onReady(e: GridReadyEvent<QueryLog>): void {
    this.grid = e.api;
    e.api.setGridOption(
      'serverSideDatasource',
      groupingDatasource<QueryLog>(
        {
          rows: (page, size, sort, gk) => this.api.logs({ page, size, sort, gk, ...this.params() }),
          buckets: (by, page, size, sort, gk) => this.api.buckets('logs', { by, page, size, sort, gk, ...this.params() }),
        },
        {},
        (total) => this.total.set(total),
      ),
    );
  }

  search(): void {
    this.grid?.paginationGoToFirstPage();
    this.grid?.refreshServerSide({ purge: true });
  }

  reset(): void {
    this.filter = { ...EMPTY };
    this.linkFilters.set([]);
    this.router.navigate([], { queryParams: {} });
    this.search();
  }

  open(e: RowClickedEvent<QueryLog>): void {
    if (!e.data || isGroupRow(e.data)) return;
    this.dialog.open(LogDetailDialog, { data: e.data, width: '960px', maxWidth: '95vw' });
  }

  importLogs(): void {
    this.dialog
      .open(ImportDialog, { width: '640px', maxWidth: '95vw' })
      .afterClosed()
      .subscribe((r) => r && this.search());
  }
}
