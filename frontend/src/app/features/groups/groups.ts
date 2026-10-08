import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AgGridAngular, ICellRendererAngularComp } from 'ag-grid-angular';
import { ColDef, GridApi, GridOptions, GridReadyEvent, ICellRendererParams, RowClickedEvent } from 'ag-grid-community';
import { Api } from '../../core/api';
import { QueryGroup } from '../../core/models';
import { LinkCell, minutesFormatter, numCol, pagedDatasource, serverGridOptions, sqlCol } from '../../shared/grid';
import { StatusChip } from '../../shared/status-chip';
import { GroupDetailRow } from './group-detail-row';

/** Tracker status link, or a "Track" button for untracked groups (drill up). */
@Component({
  selector: 'app-tracker-cell',
  imports: [RouterLink, MatButtonModule, MatIconModule, StatusChip],
  template: `
    @if (row?.trackerId) {
      <a [routerLink]="['/tracker', row!.trackerId]" (click)="$event.stopPropagation()"><app-status [value]="row!.workflowStatus" /></a>
    } @else if (row) {
      <button mat-button (click)="track($event)"><mat-icon>add</mat-icon> Track</button>
    }
  `,
})
export class TrackerCell implements ICellRendererAngularComp {
  row: QueryGroup | null = null;
  private parent!: Groups;
  agInit(p: ICellRendererParams<QueryGroup>): void {
    this.row = p.data ?? null;
    this.parent = p.context;
  }
  refresh(p: ICellRendererParams<QueryGroup>): boolean {
    this.agInit(p);
    return true;
  }
  track(e: MouseEvent): void {
    e.stopPropagation();
    if (this.row) this.parent.track([this.row.groupId]);
  }
}

/**
 * Grouping screen: AG Grid Enterprise server-side row model with master/detail - each group expands into
 * its grouping key and the original log rows.
 */
@Component({
  selector: 'app-groups',
  imports: [
    FormsModule,
    AgGridAngular,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule,
  ],
  templateUrl: './groups.html',
  styleUrl: './groups.scss',
})
export class Groups {
  private readonly api = inject(Api);
  private readonly snack = inject(MatSnackBar);
  private readonly router = inject(Router);
  private grid?: GridApi<QueryGroup>;

  readonly total = signal(0);
  readonly selected = signal<number[]>([]);

  filter = { q: '', userId: '', minGroupSize: null as number | null, minAvgDuration: null as number | null, tracked: '' };

  readonly gridOptions: GridOptions<QueryGroup> = {
    ...serverGridOptions<QueryGroup>(25),
    context: this,
    getRowId: (p) => String(p.data.groupId),
    masterDetail: true,
    isRowMaster: () => true,
    detailCellRenderer: GroupDetailRow,
    detailRowHeight: 520,
    rowSelection: { mode: 'multiRow', checkboxes: true, headerCheckbox: false, enableClickSelection: false },
    selectionColumnDef: { pinned: 'left', width: 48 },
  };

  readonly columns: ColDef<QueryGroup>[] = [
    {
      colId: 'expand',
      headerName: '',
      width: 48,
      pinned: 'left',
      cellRenderer: 'agGroupCellRenderer',
      valueGetter: () => '',
      resizable: false,
      suppressHeaderMenuButton: true,
      suppressColumnsToolPanel: true,
    },
    {
      colId: 'id',
      field: 'groupId',
      headerName: 'Group ID',
      width: 105,
      pinned: 'left',
      sortable: true,
      cellRenderer: LinkCell,
      cellRendererParams: { link: (r: QueryGroup) => ['/groups', r.groupId], text: (r: QueryGroup) => '#' + r.groupId },
    },
    { colId: 'groupSize', field: 'groupSize', headerName: 'Group size', width: 115, sortable: true, ...numCol },
    {
      colId: 'distinctUsers',
      headerName: 'User ID(s)',
      width: 240,
      sortable: true,
      valueGetter: (p) => (p.data ? `${p.data.distinctUsers} · ${p.data.userIds ?? ''}` : ''),
      tooltip: (p) => p.data?.userIds,
    },
    { colId: 'durationCount', field: 'durationCount', headerName: 'Duration count', width: 140, sortable: true, ...numCol },
    { colId: 'avgDurationMinutes', field: 'avgDurationMinutes', headerName: 'Avg', width: 110, sortable: true, valueFormatter: minutesFormatter, ...numCol },
    { colId: 'minDurationMinutes', field: 'minDurationMinutes', headerName: 'Min', width: 110, sortable: true, valueFormatter: minutesFormatter, ...numCol },
    { colId: 'maxDurationMinutes', field: 'maxDurationMinutes', headerName: 'Max', width: 110, sortable: true, valueFormatter: minutesFormatter, ...numCol },
    {
      colId: 'totalDurationMinutes',
      field: 'totalDurationMinutes',
      headerName: 'Total',
      width: 120,
      sortable: true,
      sort: 'desc',
      valueFormatter: minutesFormatter,
      ...numCol,
      cellClass: 'ag-num ag-strong',
    },
    { colId: 'errorCount', field: 'errorCount', headerName: 'Errors', width: 100, sortable: true, ...numCol },
    { colId: 'sampleQuerySeqId', field: 'sampleQuery', headerName: 'Sample query', ...sqlCol, minWidth: 320, flex: 1 },
    { colId: 'rowIndices', field: 'rowIndices', headerName: 'Row indices', width: 160, cellClass: 'ag-sql', tooltip: (p) => p.data?.rowIndices },
    {
      colId: 'fingerprint',
      field: 'fingerprint',
      headerName: 'Fingerprint',
      width: 140,
      cellClass: 'ag-sql',
      tooltip: (p) => p.data?.fingerprint,
      valueFormatter: (p) => (p.value ? String(p.value).substring(0, 12) + '…' : ''),
    },
    { colId: 'tracker', headerName: 'Tracker', width: 175, pinned: 'right', cellRenderer: TrackerCell },
  ];

  private readonly route = inject(ActivatedRoute);

  onReady(e: GridReadyEvent<QueryGroup>): void {
    this.grid = e.api;
    this.filter.tracked = this.route.snapshot.queryParamMap.get('tracked') ?? '';
    e.api.setGridOption(
      'serverSideDatasource',
      pagedDatasource(
        (page, size, sort) => {
          const f = this.filter;
          return this.api.groups({
            page,
            size,
            sort: sort || 'totalDurationMinutes,desc',
            q: f.q,
            userId: f.userId,
            minGroupSize: f.minGroupSize,
            minAvgDuration: f.minAvgDuration,
            tracked: f.tracked || null,
          });
        },
        {},
        (total) => this.total.set(total),
      ),
    );
  }

  search(): void {
    this.selected.set([]);
    this.grid?.deselectAll();
    this.grid?.paginationGoToFirstPage();
    this.grid?.refreshServerSide({ purge: true });
  }

  /** Clicking a row toggles its detail (the chevron handles its own clicks). */
  onRowClicked(e: RowClickedEvent<QueryGroup>): void {
    const target = e.event?.target as HTMLElement | undefined;
    if (!e.node.master || target?.closest('.ag-group-contracted, .ag-group-expanded, .ag-selection-checkbox, a, button')) return;
    e.node.setExpanded(!e.node.expanded);
  }

  onSelection(): void {
    this.selected.set((this.grid?.getSelectedRows() ?? []).map((r) => r.groupId));
  }

  track(ids: number[]): void {
    this.api.trackGroups(ids).subscribe((t) => {
      this.snack
        .open(`${t.length} group(s) on the tracker`, 'Open tracker', { duration: 5000 })
        .onAction()
        .subscribe(() => this.router.navigate(['/tracker']));
      this.search();
    });
  }

  rebuild(): void {
    this.api.rebuildGroups().subscribe((r) => {
      this.snack.open(`Rebuilt ${r.groups} groups`, undefined, { duration: 3000 });
      this.search();
    });
  }
}
