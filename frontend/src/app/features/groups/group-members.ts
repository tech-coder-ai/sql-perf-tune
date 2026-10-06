import { Component, inject, input } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { AgGridAngular } from 'ag-grid-angular';
import { ColDef, GridOptions, GridReadyEvent, RowClickedEvent } from 'ag-grid-community';
import { Api } from '../../core/api';
import { QueryLog } from '../../core/models';
import { serverGridOptions, minutesFormatter, numCol, pagedDatasource, sqlCol, tsFormatter } from '../../shared/grid';
import { LogDetailDialog } from '../logs/log-detail-dialog';

/** Drill-down: the original log rows that make up a group (server paged). */
@Component({
  selector: 'app-group-members',
  imports: [AgGridAngular],
  template: `<ag-grid-angular
    class="members-grid"
    [style.height]="height()"
    [gridOptions]="gridOptions"
    [columnDefs]="columns"
    (gridReady)="onReady($event)"
    (rowClicked)="open($event)"
  />`,
  styles: `
    .members-grid { display: block; width: 100%; }
  `,
})
export class GroupMembers {
  readonly groupId = input.required<number>();
  readonly height = input('420px');
  private readonly api = inject(Api);
  private readonly dialog = inject(MatDialog);

  readonly gridOptions: GridOptions<QueryLog> = { ...serverGridOptions<QueryLog>(10), sideBar: false };

  readonly columns: ColDef<QueryLog>[] = [
    { field: 'seqId', headerName: 'Seq ID', sortable: true, width: 100, ...numCol },
    { field: 'userId', headerName: 'User', sortable: true, width: 120 },
    { field: 'startTime', headerName: 'Start time', sortable: true, width: 170, valueFormatter: tsFormatter },
    { field: 'durationMinutes', headerName: 'Duration', sortable: true, width: 115, sort: 'desc', valueFormatter: minutesFormatter, ...numCol },
    { headerName: 'Error', width: 200, valueGetter: (p) => [p.data?.errorCode, p.data?.errorCategory].filter(Boolean).join(' · ') },
    { field: 'seenCount', headerName: 'Loads', sortable: true, width: 90, ...numCol },
    { headerName: 'Executed query', valueGetter: (p) => p.data?.executedQuery || p.data?.userQuery, ...sqlCol, flex: 1, minWidth: 300 },
  ];

  onReady(e: GridReadyEvent<QueryLog>): void {
    e.api.setGridOption(
      'serverSideDatasource',
      pagedDatasource((page, size, sort) => this.api.groupLogs(this.groupId(), { page, size, sort })),
    );
  }

  open(e: RowClickedEvent<QueryLog>): void {
    if (e.data) this.dialog.open(LogDetailDialog, { data: e.data, width: '960px', maxWidth: '95vw' });
  }
}
