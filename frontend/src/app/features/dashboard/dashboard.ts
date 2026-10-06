import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { Api } from '../../core/api';
import { DashboardSummary, WORKFLOW_STATUSES } from '../../core/models';
import { MinutesPipe } from '../../shared/format';
import { StatusChip } from '../../shared/status-chip';

@Component({
  selector: 'app-dashboard',
  imports: [RouterLink, MatIconModule, MatButtonModule, MinutesPipe, StatusChip],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class Dashboard {
  private readonly api = inject(Api);
  readonly data = signal<DashboardSummary | null>(null);

  readonly pipeline = computed(() => {
    const by = this.data()?.trackerByStatus ?? {};
    const max = Math.max(1, ...Object.values(by));
    return WORKFLOW_STATUSES.map((s) => ({ status: s, count: by[s] ?? 0, pct: ((by[s] ?? 0) / max) * 100 }));
  });

  readonly maxTotal = computed(() => Math.max(1, ...(this.data()?.topGroups ?? []).map((g) => g.totalDurationMinutes ?? 0)));

  constructor() {
    this.api.dashboard().subscribe((d) => this.data.set(d));
  }
}
