import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { Api } from '../../core/api';
import { DailyInsight, PipelineInsight, SavingsInsight, WorkflowStatus } from '../../core/models';
import { STAGE, STAGE_ORDER } from '../../core/stages';
import { BarList, BarRow, ChartCard, ColumnChart, StatTile } from '../../shared/charts';
import { addDays, minutesText, num, pct, signedPct, today } from '../../shared/dates';
import { StatusChip } from '../../shared/status-chip';

/** Command center: today's bad queries and where the tuning backlog stands (Q1, Q2, Q3, Q5, Q6, Q8, Q15). */
@Component({
  selector: 'app-home',
  imports: [FormsModule, RouterLink, MatButtonModule, MatIconModule, MatTooltipModule, StatTile, ChartCard, ColumnChart, BarList, StatusChip],
  templateUrl: './home.html',
  styleUrl: './home.scss',
})
export class Home {
  private readonly api = inject(Api);
  private readonly router = inject(Router);

  protected readonly date = signal(today());
  protected readonly daily = signal<DailyInsight | null>(null);
  protected readonly pipeline = signal<PipelineInsight | null>(null);
  protected readonly savings = signal<SavingsInsight | null>(null);
  protected readonly num = num;
  protected readonly minutesText = minutesText;
  protected readonly isToday = computed(() => this.date() === today());
  /** no future days in the picker */
  protected readonly maxDate = today();
  /** id of the latest load; responses of older loads are dropped (a slow request must not overwrite a newer day) */
  private loadSeq = 0;

  protected readonly hourData = computed(() =>
    (this.daily()?.byHour ?? []).map((h) => ({ label: h.label, value: h.count, title: `${h.label}:00 – ${h.label}:59` })),
  );
  protected readonly dayData = computed(() =>
    (this.daily()?.last14Days ?? []).map((d) => ({ label: d.label.substring(8), value: d.count, title: d.label })),
  );
  protected readonly stageRows = computed<BarRow[]>(() => {
    const p = this.pipeline();
    if (!p) return [];
    const by = new Map(p.stages.map((s) => [s.status, s.count]));
    const rows: BarRow[] = [
      {
        label: 'Not tracked yet',
        value: p.outstanding.untrackedPatterns,
        sub: `patterns with bad queries in the last ${p.outstanding.activeDays} days`,
        key: 'UNTRACKED',
      },
    ];
    for (const s of [...STAGE_ORDER, 'ON_HOLD', 'REJECTED'] as WorkflowStatus[]) {
      rows.push({ label: STAGE[s].label, value: by.get(s) ?? 0, sub: STAGE[s].hint, key: s });
    }
    return rows;
  });

  /** set when the page opened on the latest loaded day because today has no bad queries (yet) */
  protected readonly movedFrom = signal<string | null>(null);

  constructor() {
    this.load(true);
  }

  /**
   * Loads the selected day. On the first load, if today has no bad queries but older logs exist (the daily extract
   * usually covers yesterday's runs), it switches to the latest day with data and says so.
   */
  load(first = false): void {
    const d = this.date();
    const seq = ++this.loadSeq;
    forkJoin({
      daily: this.api.daily(d),
      pipeline: this.api.pipelineInsight({ priorDay: addDays(d, -1) }),
      savings: this.api.savings({}),
    }).subscribe((r) => {
      if (seq !== this.loadSeq) return;
      const latest = r.daily.latestDataDay;
      if (first && r.daily.badQueries === 0 && latest && latest < d) {
        this.movedFrom.set(d);
        this.date.set(latest);
        this.load();
        return;
      }
      this.daily.set(r.daily);
      this.pipeline.set(r.pipeline);
      this.savings.set(r.savings);
    });
  }

  protected stageCount(p: PipelineInsight, status: WorkflowStatus): number {
    return p.stages.find((s) => s.status === status)?.count ?? 0;
  }

  protected showLatest(d: DailyInsight): void {
    if (d.latestDataDay) this.setDate(d.latestDataDay);
  }

  shift(days: number): void {
    this.movedFrom.set(null);
    this.date.set(addDays(this.date(), days));
    this.load();
  }

  /**
   * Date picker. Browsers differ in which event a calendar pick fires (input, change or both), so both call this;
   * partial or unchanged values are ignored.
   */
  setDate(v: string): void {
    if (/^\d{4}-\d{2}-\d{2}$/.test(v) && v !== this.date()) {
      this.movedFrom.set(null);
      this.date.set(v);
      this.load();
    }
  }

  delta(d: DailyInsight): string | null {
    return signedPct(d.badQueries, d.badQueriesPreviousDay);
  }

  recurringShare(d: DailyInsight): string {
    return pct(d.recurringQueries, d.badQueries);
  }

  openStage(r: BarRow): void {
    if (r.key === 'UNTRACKED') this.router.navigate(['/groups'], { queryParams: { tracked: 'false' } });
    else this.router.navigate(['/pipeline'], { fragment: String(r.key) });
  }

  peakText(d: DailyInsight): string {
    return d.peakHour === null ? 'No bad queries on this day' : `Peak hour ${String(d.peakHour).padStart(2, '0')}:00 with ${num(d.byHour[d.peakHour].count)} bad queries`;
  }
}
