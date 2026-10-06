import { SelectionModel } from '@angular/cdk/collections';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { Api } from '../../core/api';
import { QueryGroup } from '../../core/models';
import { MinutesPipe } from '../../shared/format';
import { SqlBlock } from '../../shared/sql-block';
import { StatusChip } from '../../shared/status-chip';
import { GroupMembers } from './group-members';

@Component({
  selector: 'app-groups',
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
    MatTooltipModule,
    MatProgressBarModule,
    MinutesPipe,
    StatusChip,
    SqlBlock,
    GroupMembers,
  ],
  templateUrl: './groups.html',
  styleUrl: './groups.scss',
})
export class Groups implements OnInit {
  private readonly api = inject(Api);
  private readonly snack = inject(MatSnackBar);
  private readonly router = inject(Router);

  readonly columns = [
    'select',
    'expand',
    'groupId',
    'groupSize',
    'userIds',
    'durationCount',
    'avgDurationMinutes',
    'minDurationMinutes',
    'maxDurationMinutes',
    'totalDurationMinutes',
    'sampleQuery',
    'rowIndices',
    'fingerprint',
    'tracker',
  ];
  readonly rows = signal<QueryGroup[]>([]);
  readonly total = signal(0);
  readonly loading = signal(false);
  readonly expanded = signal<number | null>(null);
  readonly selection = new SelectionModel<number>(true, []);

  filter = { q: '', userId: '', minGroupSize: null as number | null, minAvgDuration: null as number | null, tracked: '' };
  pageIndex = 0;
  pageSize = 25;
  sort = 'totalDurationMinutes,desc';

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
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
          this.rows.set(p.content);
          this.total.set(p.totalElements);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
  }

  search(): void {
    this.pageIndex = 0;
    this.selection.clear();
    this.load();
  }

  onSort(s: Sort): void {
    this.sort = s.direction ? `${s.active},${s.direction}` : 'totalDurationMinutes,desc';
    this.search();
  }

  onPage(e: PageEvent): void {
    this.pageIndex = e.pageIndex;
    this.pageSize = e.pageSize;
    this.selection.clear();
    this.load();
  }

  toggle(row: QueryGroup): void {
    this.expanded.set(this.expanded() === row.groupId ? null : row.groupId);
  }

  allSelected(): boolean {
    return this.rows().length > 0 && this.rows().every((r) => this.selection.isSelected(r.groupId));
  }

  toggleAll(): void {
    if (this.allSelected()) this.selection.clear();
    else this.rows().forEach((r) => this.selection.select(r.groupId));
  }

  track(ids: number[]): void {
    this.api.trackGroups(ids).subscribe((t) => {
      this.snack.open(`${t.length} group(s) on the tracker`, 'Open tracker', { duration: 5000 })
        .onAction()
        .subscribe(() => this.router.navigate(['/tracker']));
      this.selection.clear();
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
