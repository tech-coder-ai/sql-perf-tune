import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { AgGridAngular } from 'ag-grid-angular';
import { ColDef, ColumnState, GridApi, GridOptions, GridReadyEvent, RowClickedEvent } from 'ag-grid-community';
import { Api, Params } from '../../core/api';
import { CustomField, PRIORITIES, Tracker, WORKFLOW_STATUSES } from '../../core/models';
import { downloadBlob, loadPref, savePref } from '../../core/prefs';
import {
  LinkCell,
  StatusCell,
  serverGridOptions,
  minutesFormatter,
  numCol,
  pagedDatasource,
  pctFormatter,
  secondsFormatter,
  sqlCol,
  tsFormatter,
} from '../../shared/grid';
import { TRACKER_COLUMNS, TrackerColumn, cellValue } from './tracker-columns';

const STATE_KEY = 'tracker.grid.v2';

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
  ],
  templateUrl: './tracker.html',
  styleUrl: './tracker.scss',
})
export class TrackerList {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private grid?: GridApi<Tracker>;

  readonly statuses = WORKFLOW_STATUSES;
  readonly priorities = PRIORITIES;
  readonly total = signal(0);
  readonly columnDefs = signal<ColDef<Tracker>[]>([]);

  filter = { q: '', status: [] as string[], priority: '', theme: '', lead: '' };

  readonly gridOptions: GridOptions<Tracker> = {
    ...serverGridOptions<Tracker>(25),
    onColumnMoved: () => this.saveState(),
    onColumnResized: (e) => e.finished && this.saveState(),
    onColumnVisible: () => this.saveState(),
    onColumnPinned: () => this.saveState(),
  };

  private readonly sortMap: Record<string, string> = Object.fromEntries(
    TRACKER_COLUMNS.filter((c) => c.sort).map((c) => [c.key, c.sort!]),
  );

  constructor() {
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
    const def: ColDef<Tracker> = {
      colId: c.key,
      headerName: c.label,
      hide: !c.visible,
      sortable: !!c.sort,
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
    return { q: f.q, status: f.status, priority: f.priority, theme: f.theme, lead: f.lead };
  }

  onReady(e: GridReadyEvent<Tracker>): void {
    this.grid = e.api;
    this.restoreState();
    e.api.setGridOption(
      'serverSideDatasource',
      pagedDatasource(
        (page, size, sort) => this.api.trackers({ ...this.params(), page, size, sort }),
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
    this.filter = { q: '', status: [], priority: '', theme: '', lead: '' };
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
      this.grid.getColumnState().map(({ colId, hide, width, pinned }) => ({ colId, hide, width, pinned })),
    );
  }

  private restoreState(): void {
    const state = loadPref<ColumnState[] | null>(STATE_KEY, null);
    if (this.grid && state) {
      this.grid.applyColumnState({ state, applyOrder: true });
    }
  }

  open(e: RowClickedEvent<Tracker>): void {
    if (e.data) this.router.navigate(['/tracker', e.data.trackerId]);
  }

  exportXlsx(): void {
    this.api.exportTracker(this.params()).subscribe((res) => {
      const cd = res.headers.get('Content-Disposition') ?? '';
      const name = /filename="?([^"]+)"?/.exec(cd)?.[1] ?? 'sql-tuning-tracker.xlsx';
      downloadBlob(res.body!, name);
    });
  }
}
