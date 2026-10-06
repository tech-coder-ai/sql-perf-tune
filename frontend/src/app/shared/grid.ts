import { Component, inject, isDevMode } from '@angular/core';
import { Router } from '@angular/router';
import { ICellRendererAngularComp } from 'ag-grid-angular';
import {
  CellStyleModule,
  ClientSideRowModelModule,
  ColDef,
  ColumnApiModule,
  GetContextMenuItems,
  GridOptions,
  ICellRendererParams,
  IServerSideDatasource,
  IServerSideGetRowsParams,
  ModuleRegistry,
  PaginationModule,
  RenderApiModule,
  RowApiModule,
  RowSelectionModule,
  RowStyleModule,
  SideBarDef,
  SortModelItem,
  TooltipModule,
  ValidationModule,
  ValueFormatterParams,
  themeQuartz,
} from 'ag-grid-community';
import {
  CellSelectionModule,
  ClipboardModule,
  ColumnMenuModule,
  ColumnsToolPanelModule,
  ContextMenuModule,
  ExcelExportModule,
  MasterDetailModule,
  ServerSideRowModelApiModule,
  ServerSideRowModelModule,
  SideBarModule,
  StatusBarModule,
} from 'ag-grid-enterprise';
import { Observable } from 'rxjs';
import { Page } from '../core/models';
import { formatMinutes } from './format';
import { StatusChip } from './status-chip';

// Register only the AG Grid features the app uses (keeps the bundle small).
// In development the ValidationModule reports any feature that is used but not registered.
ModuleRegistry.registerModules([
  // community
  ClientSideRowModelModule,
  PaginationModule,
  RowSelectionModule,
  TooltipModule,
  CellStyleModule,
  RowStyleModule,
  ColumnApiModule,
  RenderApiModule,
  RowApiModule,
  // enterprise
  ServerSideRowModelModule,
  ServerSideRowModelApiModule,
  MasterDetailModule,
  SideBarModule,
  ColumnsToolPanelModule,
  ColumnMenuModule,
  ContextMenuModule,
  CellSelectionModule,
  ClipboardModule,
  ExcelExportModule,
  StatusBarModule,
  ...(isDevMode() ? [ValidationModule] : []),
]);


/**
 * AG Grid theme wired to the app's CSS tokens. The tokens use light-dark(), so the grid follows the
 * light / dark / system theme switch without re-creating grids.
 */
export const gridTheme = themeQuartz.withParams({
  browserColorScheme: 'inherit',
  accentColor: 'var(--mat-sys-primary)',
  backgroundColor: 'var(--spt-panel)',
  foregroundColor: 'var(--mat-sys-on-surface)',
  headerBackgroundColor: 'var(--mat-sys-surface-container)',
  headerTextColor: 'var(--mat-sys-on-surface)',
  borderColor: 'var(--spt-border)',
  rowHoverColor: 'var(--spt-row-hover)',
  chromeBackgroundColor: 'var(--mat-sys-surface-container-low)',
  oddRowBackgroundColor: 'transparent',
  fontFamily: 'Inter, Roboto, "Helvetica Neue", Arial, sans-serif',
  fontSize: 13,
  headerFontWeight: 600,
  spacing: 6,
  wrapperBorder: false,
  wrapperBorderRadius: 0,
});

export const defaultColDef: ColDef = {
  sortable: false,
  resizable: true,
  filter: false,
  minWidth: 80,
  // enterprise column menu: pin, autosize, choose columns (filters are server side, in the form above the grid)
  mainMenuItems: ['pinSubMenu', 'separator', 'autoSizeThis', 'autoSizeAll', 'separator', 'columnChooser', 'resetColumns'],
};

/** Columns tool panel (show / hide / reorder) on the right of every large grid. */
export const columnsSideBar: SideBarDef = {
  toolPanels: [
    {
      id: 'columns',
      labelDefault: 'Columns',
      labelKey: 'columns',
      iconKey: 'columns',
      toolPanel: 'agColumnsToolPanel',
      toolPanelParams: { suppressRowGroups: true, suppressValues: true, suppressPivots: true, suppressPivotMode: true },
    },
  ],
};

/** Right-click menu: copy cells (Excel friendly) and export what is loaded. */
export const contextMenu: GetContextMenuItems = () => [
  'copy',
  'copyWithHeaders',
  'separator',
  {
    name: 'Export loaded rows to Excel',
    icon: '<span class="ag-icon ag-icon-excel"></span>',
    action: (p) => p.api.exportDataAsExcel({ fileName: 'sql-tuning-export.xlsx' }),
  },
];

/** Common options for every grid in the app. */
export const baseGridOptions: GridOptions = {
  theme: gridTheme,
  defaultColDef,
  animateRows: false,
  suppressCellFocus: false,
  enableCellTextSelection: true,
  ensureDomOrder: true,
  tooltipShowDelay: 400,
  overlayNoRowsTemplate: '<span class="grid-empty">No rows to show</span>',
  rowClass: 'clickable',
  cellSelection: true,
  getContextMenuItems: contextMenu,
};

/** Server-side row model with the app's paging defaults (enterprise). */
export function serverGridOptions<T>(pageSize = 50): GridOptions<T> {
  return {
    ...(baseGridOptions as GridOptions<T>),
    rowModelType: 'serverSide',
    pagination: true,
    paginationPageSize: pageSize,
    paginationPageSizeSelector: [10, 25, 50, 100],
    cacheBlockSize: 100,
    maxBlocksInCache: 20,
    sideBar: columnsSideBar,
  };
}

// ---------------------------------------------------------------- formatters

export const minutesFormatter = (p: ValueFormatterParams) => formatMinutes(p.value);
export const tsFormatter = (p: ValueFormatterParams) => (p.value ? String(p.value).replace('T', ' ').replace(/\.\d+$/, '') : '');
export const secondsFormatter = (p: ValueFormatterParams) => (p.value === null || p.value === undefined ? '' : `${p.value} s`);
export const pctFormatter = (p: ValueFormatterParams) => (p.value === null || p.value === undefined ? '' : `${p.value} %`);

/** Column presets; spread into a typed ColDef. Typed narrowly so they do not widen `field`. */
export const numCol: { headerClass: string; cellClass: string } = { headerClass: 'ag-right-aligned-header', cellClass: 'ag-num' };
export const sqlCol: { cellClass: string; width: number; tooltip: (p: { value?: unknown }) => unknown } = {
  cellClass: 'ag-sql',
  width: 420,
  tooltip: (p) => p.value,
};

// ---------------------------------------------------------------- server paging

/** Translates AG Grid's sort model to the API's "property,dir" form via an optional column -> property map. */
export function toServerSort(model: SortModelItem[], map: Record<string, string> = {}): string {
  const s = model[0];
  return s ? `${map[s.colId] ?? s.colId},${s.sort}` : '';
}

/**
 * Server-side-row-model datasource backed by a paged REST endpoint. Each grid block is fetched as one API
 * page; sorting is done by the API.
 */
export function pagedDatasource<T>(
  fetch: (page: number, size: number, sort: string) => Observable<Page<T>>,
  sortMap: Record<string, string> = {},
  onLoaded?: (total: number) => void,
): IServerSideDatasource {
  return {
    getRows: (p: IServerSideGetRowsParams) => {
      const start = p.request.startRow ?? 0;
      const size = (p.request.endRow ?? start + 100) - start;
      fetch(Math.floor(start / size), size, toServerSort(p.request.sortModel, sortMap)).subscribe({
        next: (page) => {
          p.success({ rowData: page.content, rowCount: page.totalElements });
          onLoaded?.(page.totalElements);
        },
        error: () => p.fail(),
      });
    },
  };
}

// ---------------------------------------------------------------- cell renderers

/** Status / priority pill. */
@Component({
  selector: 'app-status-cell',
  imports: [StatusChip],
  template: `<app-status [value]="value" />`,
})
export class StatusCell implements ICellRendererAngularComp {
  value: string | null = null;
  agInit(p: ICellRendererParams): void {
    this.value = p.value;
  }
  refresh(p: ICellRendererParams): boolean {
    this.value = p.value;
    return true;
  }
}

export interface LinkCellParams {
  /** builds the router link from the row */
  link: (row: never) => unknown[] | null;
  /** text to show; defaults to the cell value */
  text?: (row: never) => string;
  /** optional query parameters */
  query?: (row: never) => Record<string, unknown>;
  tooltip?: string;
}

/** Router link inside a cell (drill up / down) that does not trigger the row click. */
@Component({
  selector: 'app-link-cell',
  template: `@if (commands) {
      <a class="link" [title]="tooltip" (click)="go($event)" [attr.href]="href">{{ text }}</a>
    }`,
})
export class LinkCell implements ICellRendererAngularComp {
  private readonly router = inject(Router);
  commands: unknown[] | null = null;
  queryParams: Record<string, unknown> | undefined;
  text = '';
  tooltip = '';
  href = '';

  agInit(p: ICellRendererParams & LinkCellParams): void {
    this.commands = p.data ? p.link(p.data as never) : null;
    this.text = p.data && p.text ? p.text(p.data as never) : String(p.value ?? '');
    this.tooltip = p.tooltip ?? '';
    this.queryParams = p.query && p.data ? p.query(p.data as never) : undefined;
    this.href = this.commands
      ? this.router.serializeUrl(this.router.createUrlTree(this.commands, { queryParams: this.queryParams }))
      : '';
  }

  refresh(): boolean {
    return false;
  }

  go(e: MouseEvent): void {
    e.stopPropagation();
    if (e.ctrlKey || e.metaKey || e.button === 1) return; // let the browser open a new tab
    e.preventDefault();
    this.router.navigate(this.commands!, { queryParams: this.queryParams });
  }
}
