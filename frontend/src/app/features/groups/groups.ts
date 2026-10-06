import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { AgGridAngular, ICellRendererAngularComp } from 'ag-grid-angular';
import {
  ColDef,
  GridApi,
  GridOptions,
  GridReadyEvent,
  ICellRendererParams,
  RowClickedEvent,
  SortChangedEvent,
} from 'ag-grid-community';
import { Api } from '../../core/api';
import { QueryGroup } from '../../core/models';
import { LinkCell, baseGridOptions, minutesFormatter, numCol, sqlCol, toServerSort } from '../../shared/grid';
import { StatusChip } from '../../shared/status-chip';
import { GroupDetailRow } from './group-detail-row';

/** A page row is either a group (master) or the full-width detail row under an expanded group. */
interface GroupRow extends QueryGroup {
  kind: 'group' | 'detail';
  group?: QueryGroup;
}

/** Expand / collapse chevron. */
@Component({
  selector: 'app-expand-cell',
  imports: [MatIconModule],
  template: `<mat-icon class="chev" [attr.aria-label]="open ? 'Collapse' : 'Expand'">{{ open ? 'expand_less' : 'expand_more' }}</mat-icon>`,
  styles: `.chev { vertical-align: middle; color: var(--spt-muted); }`,
})
export class ExpandCell implements ICellRendererAngularComp {
  open = false;
  agInit(p: ICellRendererParams<GroupRow>): void {
    this.open = (p.context as Groups).expanded() === p.data?.groupId;
  }
  refresh(p: ICellRendererParams<GroupRow>): boolean {
    this.agInit(p);
    return true;
  }
}

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
  agInit(p: ICellRendererParams<GroupRow>): void {
    this.row = p.data?.kind === 'group' ? p.data : null;
    this.parent = p.context;
  }
  refresh(p: ICellRendererParams<GroupRow>): boolean {
    this.agInit(p);
    return true;
  }
  track(e: MouseEvent): void {
    e.stopPropagation();
    if (this.row) this.parent.track([this.row.groupId]);
  }
}

/** Keeps the server's order (and each detail row right below its group) when the user clicks a header. */
const serverOrder = () => 0;

@Component({
  selector: 'app-groups',
  imports: [
    FormsModule,
    AgGridAngular,
    MatPaginatorModule,
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
export class Groups implements OnInit {
  private readonly api = inject(Api);
  private readonly snack = inject(MatSnackBar);
  private readonly router = inject(Router);
  private grid?: GridApi<GroupRow>;

  readonly page = signal<QueryGroup[]>([]);
  readonly rows = signal<GroupRow[]>([]);
  readonly total = signal(0);
  readonly loading = signal(false);
  readonly expanded = signal<number | null>(null);
  readonly selected = signal<number[]>([]);

  filter = { q: '', userId: '', minGroupSize: null as number | null, minAvgDuration: null as number | null, tracked: '' };
  pageIndex = 0;
  pageSize = 25;
  sort = 'totalDurationMinutes,desc';

  readonly gridOptions: GridOptions<GroupRow> = {
    ...baseGridOptions,
    context: this,
    getRowId: (p) => (p.data.kind === 'detail' ? 'd' : 'g') + p.data.groupId,
    isFullWidthRow: (p) => p.rowNode.data?.kind === 'detail',
    fullWidthCellRenderer: GroupDetailRow,
    getRowHeight: (p) => (p.data?.kind === 'detail' ? 520 : undefined),
    rowSelection: {
      mode: 'multiRow',
      checkboxes: true,
      headerCheckbox: true,
      enableClickSelection: false,
      isRowSelectable: (n) => n.data?.kind === 'group',
    },
    selectionColumnDef: { pinned: 'left', width: 48 },
  };

  readonly columns: ColDef<GroupRow>[] = [
    { colId: 'expand', headerName: '', width: 52, pinned: 'left', cellRenderer: ExpandCell, resizable: false },
    {
      colId: 'id',
      field: 'groupId',
      headerName: 'Group ID',
      width: 105,
      pinned: 'left',
      sortable: true,
      comparator: serverOrder,
      cellRenderer: LinkCell,
      cellRendererParams: { link: (r: QueryGroup) => ['/groups', r.groupId], text: (r: QueryGroup) => '#' + r.groupId },
    },
    { colId: 'groupSize', field: 'groupSize', headerName: 'Group size', width: 110, sortable: true, comparator: serverOrder, ...numCol },
    {
      colId: 'distinctUsers',
      headerName: 'User ID(s)',
      width: 240,
      sortable: true,
      comparator: serverOrder,
      valueGetter: (p) => (p.data ? `${p.data.distinctUsers} · ${p.data.userIds ?? ''}` : ''),
      tooltip: (p) => p.data?.userIds,
    },
    { colId: 'durationCount', field: 'durationCount', headerName: 'Duration count', width: 135, sortable: true, comparator: serverOrder, ...numCol },
    { colId: 'avgDurationMinutes', field: 'avgDurationMinutes', headerName: 'Avg', width: 105, sortable: true, comparator: serverOrder, valueFormatter: minutesFormatter, ...numCol },
    { colId: 'minDurationMinutes', field: 'minDurationMinutes', headerName: 'Min', width: 105, sortable: true, comparator: serverOrder, valueFormatter: minutesFormatter, ...numCol },
    { colId: 'maxDurationMinutes', field: 'maxDurationMinutes', headerName: 'Max', width: 105, sortable: true, comparator: serverOrder, valueFormatter: minutesFormatter, ...numCol },
    {
      colId: 'totalDurationMinutes',
      field: 'totalDurationMinutes',
      headerName: 'Total',
      width: 115,
      sortable: true,
      sort: 'desc',
      comparator: serverOrder,
      valueFormatter: minutesFormatter,
      ...numCol,
      cellClass: 'ag-num ag-strong',
    },
    { colId: 'errorCount', field: 'errorCount', headerName: 'Errors', width: 95, sortable: true, comparator: serverOrder, ...numCol },
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

  ngOnInit(): void {
    this.load();
  }

  onReady(e: GridReadyEvent<GroupRow>): void {
    this.grid = e.api;
  }

  load(): void {
    this.loading.set(true);
    this.grid?.setGridOption('loading', true);
    const f = this.filter;
    this.api
      .groups({
        page: this.pageIndex,
        size: this.pageSize,
        sort: this.sort,
        q: f.q,
        userId: f.userId,
        minGroupSize: f.minGroupSize,
        minAvgDuration: f.minAvgDuration,
        tracked: f.tracked || null,
      })
      .subscribe({
        next: (p) => {
          this.page.set(p.content);
          this.total.set(p.totalElements);
          this.selected.set([]);
          this.rebuildRows();
          this.loading.set(false);
          this.grid?.setGridOption('loading', false);
        },
        error: () => {
          this.loading.set(false);
          this.grid?.setGridOption('loading', false);
        },
      });
  }

  private rebuildRows(): void {
    const out: GroupRow[] = [];
    for (const g of this.page()) {
      out.push({ ...g, kind: 'group' });
      if (this.expanded() === g.groupId) out.push({ ...g, kind: 'detail', group: g });
    }
    this.rows.set(out);
  }

  search(): void {
    this.pageIndex = 0;
    this.load();
  }

  onSort(e: SortChangedEvent<GroupRow>): void {
    const model = e.api
      .getColumnState()
      .filter((c) => c.sort)
      .map((c) => ({ colId: c.colId, sort: c.sort! }));
    this.sort = toServerSort(model) || 'totalDurationMinutes,desc';
    this.search();
  }

  onPage(e: PageEvent): void {
    this.pageIndex = e.pageIndex;
    this.pageSize = e.pageSize;
    this.load();
  }

  onRowClicked(e: RowClickedEvent<GroupRow>): void {
    if (e.data?.kind !== 'group') return;
    this.expanded.set(this.expanded() === e.data.groupId ? null : e.data.groupId);
    this.rebuildRows();
    setTimeout(() => this.grid?.refreshCells({ columns: ['expand'], force: true }));
  }

  onSelection(): void {
    this.selected.set(
      (this.grid?.getSelectedRows() ?? []).filter((r) => r.kind === 'group').map((r) => r.groupId),
    );
  }

  track(ids: number[]): void {
    this.api.trackGroups(ids).subscribe((t) => {
      this.snack
        .open(`${t.length} group(s) on the tracker`, 'Open tracker', { duration: 5000 })
        .onAction()
        .subscribe(() => this.router.navigate(['/tracker']));
      this.load();
    });
  }

  rebuild(): void {
    this.api.rebuildGroups().subscribe((r) => {
      this.snack.open(`Rebuilt ${r.groups} groups`, undefined, { duration: 3000 });
      this.search();
    });
  }
}
