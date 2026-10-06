import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { Api, Params } from '../../core/api';
import { CustomField, PRIORITIES, Tracker, WORKFLOW_STATUSES } from '../../core/models';
import { downloadBlob, loadPref, savePref } from '../../core/prefs';
import { MinutesPipe, TimestampPipe } from '../../shared/format';
import { StatusChip } from '../../shared/status-chip';
import { TRACKER_COLUMNS, TrackerColumn, cellValue } from './tracker-columns';

@Component({
  selector: 'app-tracker',
  imports: [
    FormsModule,
    RouterLink,
    MatTableModule,
    MatPaginatorModule,
    MatSortModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatCheckboxModule,
    MatButtonModule,
    MatIconModule,
    MatMenuModule,
    MatTooltipModule,
    MatProgressBarModule,
    MinutesPipe,
    TimestampPipe,
    StatusChip,
  ],
  templateUrl: './tracker.html',
  styleUrl: './tracker.scss',
})
export class TrackerList implements OnInit {
  private readonly api = inject(Api);
  private readonly router = inject(Router);

  readonly statuses = WORKFLOW_STATUSES;
  readonly priorities = PRIORITIES;
  readonly rows = signal<Tracker[]>([]);
  readonly total = signal(0);
  readonly loading = signal(false);
  readonly allColumns = signal<TrackerColumn[]>([]);
  readonly visibleKeys = computed(() => ['open', ...this.allColumns().filter((c) => c.visible).map((c) => c.key)]);
  readonly cell = cellValue;

  filter = { q: '', status: [] as string[], priority: '', theme: '', lead: '' };
  pageIndex = 0;
  pageSize = 25;
  sort = '';

  ngOnInit(): void {
    const saved = loadPref<Record<string, boolean>>('tracker.columns', {});
    const base = TRACKER_COLUMNS.map((c) => ({ ...c, visible: saved[c.key] ?? c.visible }));
    this.allColumns.set(base);
    this.api.customFields('TRACKER').subscribe((fields: CustomField[]) => {
      const custom = fields
        .filter((f) => f.active)
        .map<TrackerColumn>((f) => ({
          key: 'cf:' + f.fieldKey,
          label: f.label,
          kind: f.dataType === 'NUMBER' ? 'num' : f.dataType === 'LONG_TEXT' ? 'longtext' : 'text',
          visible: saved['cf:' + f.fieldKey] ?? true,
        }));
      this.allColumns.set([...base, ...custom]);
    });
    this.load();
  }

  private params(): Params {
    const f = this.filter;
    return { q: f.q, status: f.status, priority: f.priority, theme: f.theme, lead: f.lead };
  }

  load(): void {
    this.loading.set(true);
    this.api.trackers({ ...this.params(), page: this.pageIndex, size: this.pageSize, sort: this.sort }).subscribe({
      next: (p) => {
        this.rows.set(p.content);
        this.total.set(p.totalElements);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  search(): void {
    this.pageIndex = 0;
    this.load();
  }

  reset(): void {
    this.filter = { q: '', status: [], priority: '', theme: '', lead: '' };
    this.search();
  }

  onSort(s: Sort): void {
    const col = this.allColumns().find((c) => c.key === s.active);
    this.sort = s.direction && col?.sort ? `${col.sort},${s.direction}` : '';
    this.search();
  }

  onPage(e: PageEvent): void {
    this.pageIndex = e.pageIndex;
    this.pageSize = e.pageSize;
    this.load();
  }

  toggleColumn(col: TrackerColumn): void {
    this.allColumns.update((cols) => cols.map((c) => (c.key === col.key ? { ...c, visible: !c.visible } : c)));
    this.persistColumns();
  }

  showColumns(mode: 'all' | 'default'): void {
    this.allColumns.update((cols) =>
      cols.map((c) => ({
        ...c,
        visible: mode === 'all' || c.key.startsWith('cf:') || (TRACKER_COLUMNS.find((d) => d.key === c.key)?.visible ?? true),
      })),
    );
    this.persistColumns();
  }

  private persistColumns(): void {
    savePref('tracker.columns', Object.fromEntries(this.allColumns().map((c) => [c.key, c.visible])));
  }

  open(row: Tracker): void {
    this.router.navigate(['/tracker', row.trackerId]);
  }

  exportXlsx(): void {
    this.api.exportTracker(this.params()).subscribe((res) => {
      const cd = res.headers.get('Content-Disposition') ?? '';
      const name = /filename="?([^"]+)"?/.exec(cd)?.[1] ?? 'sql-tuning-tracker.xlsx';
      downloadBlob(res.body!, name);
    });
  }
}
