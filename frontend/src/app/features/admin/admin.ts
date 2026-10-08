import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AgGridAngular } from 'ag-grid-angular';
import { ColDef, GridOptions } from 'ag-grid-community';
import { Api } from '../../core/api';
import { LookupStore } from '../../core/lookups';
import {
  CustomEntityType,
  CustomField,
  DataSource,
  IngestionBatch,
  Lookup,
  PromptTemplate,
  SourceFile,
  UserDirectoryEntry,
} from '../../core/models';
import { TimestampPipe } from '../../shared/format';
import { LinkCell, StatusCell, baseGridOptions, errorCellRules, numCol, tsFormatter } from '../../shared/grid';
import { StatusChip } from '../../shared/status-chip';

const SAMPLE_LOG_QUERY = `SELECT seq_id, executed_query, user_query, error_code, error_category, error_message,
       user_id, start_time, end_time, duration_minutes
  FROM audit.impala_query_log
 WHERE start_time > :since`;

@Component({
  selector: 'app-admin',
  imports: [
    FormsModule,
    MatTabsModule,
    AgGridAngular,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatCheckboxModule,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule,
    StatusChip,
    TimestampPipe,
  ],
  templateUrl: './admin.html',
  styleUrl: './admin.scss',
})
export class Admin {
  private readonly api = inject(Api);
  private readonly snack = inject(MatSnackBar);

  readonly sources = signal<DataSource[]>([]);
  readonly fields = signal<CustomField[]>([]);
  readonly prompts = signal<PromptTemplate[]>([]);
  readonly batches = signal<IngestionBatch[]>([]);

  ds: Partial<DataSource> = this.newDs();
  fieldEntity: CustomEntityType = 'TRACKER';
  field: Partial<CustomField> = this.newField();
  prompt = { name: 'impala-default', sqlEngine: 'IMPALA', templateText: '', notes: '' };

  // ---- dropdown values
  protected readonly lookups = inject(LookupStore);
  protected lookupCategory = 'THEME';
  protected newLookup = { value: '', tone: 'info' };
  protected readonly tones = ['ok', 'warn', 'bad', 'info', 'muted'];
  protected readonly categoryLabels: Record<string, string> = {
    THEME: 'Theme',
    DEV_TEAM_STATUS: 'Dev team status',
    OPTIMIZED_SQL_STATUS: 'Optimized SQL',
    CLOUDERA_POST_RUN_VALIDATION: 'Cloudera post-run validation',
    SME_VALIDATION: 'SME validation',
    INSTALL_STATUS: 'Install',
    EXECUTE_STATUS: 'Execute',
    VALIDATION_STATUS: 'Validation',
    ENVIRONMENT: 'Environment',
    REJECTION_REASON: 'Rejection reason',
  };

  addLookup(): void {
    const entries = this.lookups.entries(this.lookupCategory);
    const sortOrder = (entries.length ? Math.max(...entries.map((e) => e.sortOrder)) : 0) + 10;
    this.api
      .saveLookup({ category: this.lookupCategory, value: this.newLookup.value, tone: this.newLookup.tone as Lookup['tone'], sortOrder })
      .subscribe(() => {
        this.newLookup = { value: '', tone: 'info' };
        this.lookups.reload().subscribe();
      });
  }

  updateLookup(l: Lookup, patch: Partial<Lookup>): void {
    this.api.saveLookup({ ...l, ...patch }).subscribe(() => this.lookups.reload().subscribe());
  }

  moveLookup(l: Lookup, dir: -1 | 1): void {
    const list = this.lookups.entries(this.lookupCategory);
    const idx = list.findIndex((x) => x.id === l.id);
    const other = list[idx + dir];
    if (!other) return;
    this.api.saveLookup({ ...l, sortOrder: other.sortOrder }).subscribe(() =>
      this.api.saveLookup({ ...other, sortOrder: l.sortOrder }).subscribe(() => this.lookups.reload().subscribe()),
    );
  }

  // ---- users & groups
  protected readonly directory = signal<UserDirectoryEntry[]>([]);
  protected userEdit: Partial<UserDirectoryEntry> = {};
  readonly userGrid: GridOptions<UserDirectoryEntry> = { ...baseGridOptions, pagination: true, paginationPageSize: 20 };
  readonly userCols: ColDef<UserDirectoryEntry>[] = [
    { field: 'userId', headerName: 'User id', width: 160, sortable: true },
    { field: 'displayName', headerName: 'Name', width: 180, sortable: true },
    { field: 'userGroup', headerName: 'User group', width: 180, sortable: true },
    { field: 'department', headerName: 'Department', flex: 1, sortable: true },
    { field: 'updatedAt', headerName: 'Updated', width: 170, valueFormatter: tsFormatter },
  ];

  loadDirectory(): void {
    this.api.directory().subscribe((d) => this.directory.set(d));
  }

  editUser(e: UserDirectoryEntry | undefined): void {
    if (e) this.userEdit = { ...e };
  }

  saveUser(): void {
    this.api.saveDirectoryEntry(this.userEdit).subscribe(() => {
      this.userEdit = {};
      this.loadDirectory();
    });
  }

  deleteUser(): void {
    if (!this.userEdit.userId) return;
    this.api.deleteDirectoryEntry(this.userEdit.userId).subscribe(() => {
      this.userEdit = {};
      this.loadDirectory();
    });
  }

  uploadUsers(e: Event): void {
    const f = (e.target as HTMLInputElement).files?.[0];
    if (!f) return;
    this.api.uploadDirectory(f).subscribe((r) => {
      this.snack.open(`${r.saved} users saved`, undefined, { duration: 3000 });
      this.loadDirectory();
    });
  }

  readonly files = signal<SourceFile[]>([]);
  readonly fileGrid: GridOptions<SourceFile> = { ...baseGridOptions, rowClass: undefined, pagination: true, paginationPageSize: 20 };
  readonly fileCols: ColDef<SourceFile>[] = [
    { field: 'fileName', headerName: 'File', flex: 1, minWidth: 220, sortable: true },
    { field: 'loadCount', headerName: 'Times loaded', width: 130, sortable: true, ...numCol, cellClassRules: { 'ag-reloaded': (p) => p.value > 1 } },
    { field: 'firstLoadedAt', headerName: 'First loaded', width: 170, sortable: true, valueFormatter: tsFormatter },
    { field: 'lastLoadedAt', headerName: 'Last loaded', width: 170, sortable: true, sort: 'desc', valueFormatter: tsFormatter },
    {
      field: 'processedBatchId',
      headerName: 'Processed by',
      width: 130,
      cellRenderer: LinkCell,
      cellRendererParams: { link: () => ['/logs'], query: (f: SourceFile) => ({ batchId: f.processedBatchId }), text: (f: SourceFile) => 'import #' + f.processedBatchId },
    },
    { field: 'fileSize', headerName: 'Size', width: 110, valueFormatter: (p) => (p.value ? (p.value / 1024).toFixed(0) + ' KB' : ''), ...numCol },
    { field: 'contentHash', headerName: 'SHA-256', width: 200, cellClass: 'ag-sql', tooltip: (p) => p.data?.contentHash },
  ];

  readonly batchGrid: GridOptions<IngestionBatch> = {
    ...baseGridOptions,
    rowClass: undefined,
    pagination: true,
    paginationPageSize: 20,
    paginationPageSizeSelector: [20, 50, 100],
  };
  readonly batchCols: ColDef<IngestionBatch>[] = [
    {
      field: 'id',
      headerName: 'Import',
      width: 100,
      sortable: true,
      sort: 'desc',
      cellRenderer: LinkCell,
      cellRendererParams: {
        link: () => ['/logs'],
        query: (b: IngestionBatch) => ({ batchId: b.id }),
        text: (b: IngestionBatch) => '#' + b.id,
        tooltip: 'Show the rows of this import',
      },
    },
    { field: 'sourceKind', headerName: 'Kind', width: 100, sortable: true },
    { field: 'sourceName', headerName: 'Source', flex: 1, minWidth: 200, tooltip: (p) => p.data?.sourceName },
    { field: 'sqlEngine', headerName: 'Engine', width: 100 },
    { field: 'status', headerName: 'Status', width: 210, sortable: true, cellRenderer: StatusCell, tooltip: (p) => p.data?.message },
    { field: 'rowsRead', headerName: 'Read', width: 95, sortable: true, ...numCol },
    { field: 'rowsLoaded', headerName: 'New', headerTooltip: 'New rows processed by this import', width: 90, sortable: true, ...numCol },
    {
      field: 'rowsDuplicate',
      headerName: 'Already loaded',
      headerTooltip: 'Rows loaded before (or repeated in the file): counted, not re-processed',
      width: 140,
      sortable: true,
      ...numCol,
      cellClassRules: { 'ag-reloaded': (p) => p.value > 0 },
    },
    {
      field: 'fileLoadNumber',
      headerName: 'File load #',
      width: 115,
      sortable: true,
      ...numCol,
      cellClassRules: { 'ag-reloaded': (p) => p.value > 1 },
    },
    {
      field: 'duplicateOfBatchId',
      headerName: 'Duplicate of',
      width: 125,
      valueFormatter: (p) => (p.value ? 'import #' + p.value : ''),
      cellClass: 'ag-reloaded',
    },
    { field: 'rowsRejected', headerName: 'Rejected', width: 105, sortable: true, ...numCol, cellClassRules: errorCellRules },
    { field: 'groupsAffected', headerName: 'Groups', width: 100, sortable: true, ...numCol },
    { field: 'startedAt', headerName: 'Started', width: 170, sortable: true, valueFormatter: tsFormatter },
    { field: 'completedAt', headerName: 'Completed', width: 170, valueFormatter: tsFormatter },
    { field: 'createdBy', headerName: 'By', width: 130 },
    { field: 'message', headerName: 'Message', width: 300, tooltip: (p) => p.data?.message },
  ];

  constructor() {
    this.refresh();
  }

  refresh(): void {
    this.api.dataSources().subscribe((s) => this.sources.set(s));
    this.api.prompts().subscribe((p) => {
      this.prompts.set(p);
      const active = p.find((x) => x.active && x.name === this.prompt.name);
      if (active && !this.prompt.templateText) this.prompt.templateText = active.templateText;
    });
    this.api.batches().subscribe((b) => this.batches.set(b));
    this.api.loadedFiles().subscribe((f) => this.files.set(f));
    this.loadDirectory();
    this.loadFields();
  }

  // ---- data sources
  newDs(): Partial<DataSource> {
    return { sourceType: 'IMPALA', sqlEngine: 'IMPALA', fetchSize: 1000, active: true, logQuery: SAMPLE_LOG_QUERY };
  }

  editDs(s: DataSource): void {
    this.ds = { ...s };
  }

  saveDs(): void {
    this.api.saveDataSource(this.ds).subscribe(() => {
      this.snack.open('Data source saved', undefined, { duration: 2000 });
      this.ds = this.newDs();
      this.refresh();
    });
  }

  testDs(s: DataSource): void {
    this.api.testDataSource(s.id).subscribe((r) => this.snack.open(r.message, 'OK', { duration: 5000 }));
  }

  // ---- custom fields
  newField(): Partial<CustomField> {
    return { dataType: 'TEXT', displayOrder: 0, required: false, active: true };
  }

  loadFields(): void {
    this.api.customFields(this.fieldEntity).subscribe((f) => this.fields.set(f));
  }

  editField(f: CustomField): void {
    this.field = { ...f };
  }

  saveField(): void {
    this.api.saveCustomField({ ...this.field, entityType: this.fieldEntity }).subscribe(() => {
      this.snack.open('Field saved', undefined, { duration: 2000 });
      this.field = this.newField();
      this.loadFields();
    });
  }

  // ---- prompts
  editPrompt(p: PromptTemplate): void {
    this.prompt = { name: p.name, sqlEngine: p.sqlEngine, templateText: p.templateText, notes: '' };
  }

  savePrompt(): void {
    this.api.savePrompt(this.prompt).subscribe((p) => {
      this.snack.open(`Saved ${p.name} v${p.versionNo} (active)`, undefined, { duration: 3000 });
      this.refresh();
    });
  }

  activate(p: PromptTemplate): void {
    this.api.activatePrompt(p.id).subscribe(() => this.refresh());
  }
}
