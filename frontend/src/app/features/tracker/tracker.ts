import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AgGridAngular } from 'ag-grid-angular';
import { ColDef, ColumnState, GridApi, GridOptions, GridReadyEvent, RowClickedEvent } from 'ag-grid-community';
import { Api, Params } from '../../core/api';
import { STAGE } from '../../core/stages';
import { CustomField, PRIORITIES, Tracker, WORKFLOW_STATUSES } from '../../core/models';
import { downloadBlob, loadPref, savePref } from '../../core/prefs';
import {
  LinkCell,
  StatusCell,
  deltaCellRules,
  deltaFormatter,
  deltaPtsFormatter,
  serverGridOptions,
  minutesFormatter,
  numCol,
  groupingDatasource,
  isGroupRow,
  pctFormatter,
  secondsFormatter,
  sqlCol,
  tsFormatter,
} from '../../shared/grid';
import { GROUPABLE, TRACKER_COLUMNS, TrackerColumn, cellValue } from './tracker-columns';

const STATE_KEY = 'tracker.grid.v4';

@Component({
  selector: 'app-tracker',
  imports: [
    FormsModule,
    RouterLink,
    AgGridAngular,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule,
    MatChipsModule,
  ],
  templateUrl: './tracker.html',
  styleUrl: './tracker.scss',
})
export class TrackerList {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private grid?: GridApi<Tracker>;

  readonly statuses = WORKFLOW_STATUSES;
  readonly stage = STAGE;
  readonly priorities = PRIORITIES;
  readonly total = signal(0);
  readonly columnDefs = signal<ColDef<Tracker>[]>([]);

  filter = {
    q: '',
    status: [] as string[],
    priority: '',
    theme: '',
    lead: '',
    /** only items whose pattern had bad queries between these days (links from Insights Q6) */
    activeFrom: null as string | null,
    activeTo: null as string | null,
  };
  private readonly route = inject(ActivatedRoute);

  readonly gridOptions: GridOptions<Tracker> = {
    ...serverGridOptions<Tracker>(25),
    onColumnMoved: () => this.saveState(),
    onColumnResized: (e) => e.finished && this.saveState(),
    onColumnVisible: () => this.saveState(),
    onColumnPinned: () => this.saveState(),
    onColumnRowGroupChanged: () => this.saveState(),
  };

  private readonly sortMap: Record<string, string> = Object.fromEntries(
    TRACKER_COLUMNS.filter((c) => c.sort).map((c) => [c.key, c.sort!]),
  );

  constructor() {
    // links from the Command center / Insights pipeline bars carry the stage(s) and window
    const qp = this.route.snapshot.queryParamMap;
    if (qp.getAll('status').length) this.filter.status = qp.getAll('status');
    this.filter.activeFrom = qp.get('activeFrom');
    this.filter.activeTo = qp.get('activeTo');
    this.columnDefs.set(this.buildDefs([]));
    this.api.customFields('TRACKER').subscribe((fields) => {
      this.columnDefs.set(this.buildDefs(fields.filter((f) => f.active)));
      // re-apply the saved layout once custom columns exist
      setTimeout(() => this.restoreState());
    });
  }

  private buildDefs(custom: CustomField[]): ColDef<Tracker>[] {
    const defs: ColDef<Tracker>[] = [
      {
        colId: 'trackerId',
        headerName: '#',
        width: 80,
        pinned: 'left',
        lockPinned: true,
        sortable: true,
        valueGetter: (p) => p.data?.trackerId,
        cellRenderer: LinkCell,
        cellRendererParams: { link: (r: Tracker) => ['/tracker', r.trackerId], text: (r: Tracker) => 'T-' + r.trackerId },
        cellClass: 'ag-sql',
      },
      ...TRACKER_COLUMNS.map((c) => this.toColDef(c)),
      ...custom.map((f) =>
        this.toColDef({
          key: 'cf:' + f.fieldKey,
          label: f.label,
          kind: f.dataType === 'NUMBER' ? 'num' : f.dataType === 'LONG_TEXT' ? 'longtext' : 'text',
          visible: true,
        }),
      ),
    ];
    return defs;
  }

  private toColDef(c: TrackerColumn): ColDef<Tracker> {
    const def = this.baseDef(c);
    // header tint: original (orange) / post-implementation (green) / change (violet)
    if (c.phase) {
      const right = def.headerClass ? ' ' + def.headerClass : '';
      def.headerClass = 'hdr-' + c.phase + right;
      def.width = Math.max(def.width ?? 0, c.kind === 'delta' || c.kind === 'deltaPts' ? 175 : 190);
      def.headerTooltip = { pre: 'Original run', post: 'After tuning (post-implementation)', delta: 'Change from original to post-implementation; negative = less' }[c.phase];
    }
    return def;
  }

  private baseDef(c: TrackerColumn): ColDef<Tracker> {
    const def: ColDef<Tracker> = {
      colId: c.key,
      headerName: c.label,
      hide: !c.visible,
      sortable: !!c.sort,
      enableRowGroup: GROUPABLE.has(c.key),
      valueGetter: (p) => (p.data ? cellValue(p.data, c.key) : undefined),
    };
    switch (c.kind) {
      case 'id':
        return {
          ...def,
          width: 100,
          pinned: 'left',
          cellRenderer: LinkCell,
          cellRendererParams: { link: (r: Tracker) => ['/groups', r.groupId], text: (r: Tracker) => '#' + r.groupId, tooltip: 'Drill down to group' },
        };
      case 'status':
        return { ...def, width: 170, cellRenderer: StatusCell };
      case 'lookup':
        return { ...def, width: 170, cellRenderer: StatusCell, cellRendererParams: { category: c.category } };
      case 'delta':
        return { ...def, width: 150, valueFormatter: deltaFormatter, ...numCol, cellClassRules: deltaCellRules };
      case 'deltaPts':
        return { ...def, width: 170, valueFormatter: deltaPtsFormatter, ...numCol, cellClassRules: deltaCellRules };
      case 'minutes':
        return { ...def, width: 130, valueFormatter: minutesFormatter, ...numCol };
      case 'seconds':
        return { ...def, width: 150, valueFormatter: secondsFormatter, ...numCol };
      case 'pct':
        return {
          ...def,
          width: 125,
          valueFormatter: pctFormatter,
          ...numCol,
          cellClassRules:
            c.key === 'improvementPct' ? { 'ag-good': (p) => p.value > 0, 'ag-bad': (p) => p.value < 0 } : undefined,
        };
      case 'num':
        return { ...def, width: 120, ...numCol };
      case 'sql':
        return { ...def, ...sqlCol, width: 360 };
      case 'mono':
        return { ...def, width: 180, cellClass: 'ag-sql', tooltip: (p) => p.value };
      case 'ts':
        return { ...def, width: 170, valueFormatter: tsFormatter };
      case 'days':
        return {
          ...def,
          width: 120,
          ...numCol,
          valueFormatter: (p) => (p.value === null || p.value === undefined ? '' : `${p.value} d`),
          cellClassRules: { 'ag-reloaded': (p) => (p.value ?? 0) > 14 },
        };
      case 'longtext':
        return { ...def, width: 260, tooltip: (p) => p.value };
      default:
        return { ...def, width: 170 };
    }
  }

  private params(): Params {
    const f = this.filter;
    return {
      q: f.q,
      status: f.status,
      priority: f.priority,
      theme: f.theme,
      lead: f.lead,
      activeFrom: f.activeFrom,
      activeTo: f.activeTo,
    };
  }

  onReady(e: GridReadyEvent<Tracker>): void {
    this.grid = e.api;
    this.restoreState();
    e.api.setGridOption(
      'serverSideDatasource',
      groupingDatasource<Tracker>(
        {
          rows: (page, size, sort, gk) => this.api.trackers({ ...this.params(), page, size, sort, gk }),
          buckets: (by, page, size, sort, gk) => this.api.buckets('tracker', { ...this.params(), by, page, size, sort, gk }),
        },
        this.sortMap,
        (total) => this.total.set(total),
      ),
    );
  }

  search(): void {
    this.grid?.paginationGoToFirstPage();
    this.grid?.refreshServerSide({ purge: true });
  }

  reset(): void {
    this.filter = { q: '', status: [], priority: '', theme: '', lead: '', activeFrom: null, activeTo: null };
    this.router.navigate([], { queryParams: {} });
    this.search();
  }

  dropWindow(): void {
    this.filter.activeFrom = null;
    this.filter.activeTo = null;
    this.search();
  }

  // ---- column layout (side bar "Columns" panel + header menus), remembered per user

  openColumns(): void {
    this.grid?.openToolPanel('columns');
  }

  resetLayout(): void {
    try {
      localStorage.removeItem('spt.' + STATE_KEY);
    } catch {
      /* ignore */
    }
    this.grid?.resetColumnState();
  }

  private saveState(): void {
    if (!this.grid) return;
    savePref(
      STATE_KEY,
      this.grid.getColumnState().map(({ colId, hide, width, pinned, rowGroup, rowGroupIndex }) => ({
        colId,
        hide,
        width,
        pinned,
        rowGroup,
        rowGroupIndex,
      })),
    );
  }

  private restoreState(): void {
    const state = loadPref<ColumnState[] | null>(STATE_KEY, null);
    if (this.grid && state) {
      this.grid.applyColumnState({ state, applyOrder: true });
    }
  }

  open(e: RowClickedEvent<Tracker>): void {
    if (e.data && !isGroupRow(e.data)) this.router.navigate(['/tracker', e.data.trackerId]);
  }

  exportXlsx(): void {
    this.api.exportTracker(this.params()).subscribe((res) => {
      const cd = res.headers.get('Content-Disposition') ?? '';
      const name = /filename="?([^"]+)"?/.exec(cd)?.[1] ?? 'sql-tuning-tracker.xlsx';
      downloadBlob(res.body!, name);
    });
  }
}
