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
import { CustomEntityType, CustomField, DataSource, IngestionBatch, PromptTemplate } from '../../core/models';
import { TimestampPipe } from '../../shared/format';
import { LinkCell, StatusCell, baseGridOptions, numCol, tsFormatter } from '../../shared/grid';
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
    { field: 'rowsLoaded', headerName: 'Loaded', width: 100, sortable: true, ...numCol },
    { field: 'rowsRejected', headerName: 'Rejected', width: 105, sortable: true, ...numCol },
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
