import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { Api } from '../../core/api';
import {
  DailyInsight,
  PipelineInsight,
  REQUEST_SOURCES,
  SavingsInsight,
  ThemePriority,
  TrendsInsight,
  UsersInsight,
} from '../../core/models';
import { REQUEST_SOURCE_LABEL, STAGE } from '../../core/stages';
import { BarList, BarRow, ChartCard, ColumnChart, LineChart, Series, TableView } from '../../shared/charts';
import { addDays, minutesText, num, pct, secondsText, signedPct, today } from '../../shared/dates';
import { StatusChip } from '../../shared/status-chip';

interface Question {
  q: string;
  anchor: string;
  text: string;
}

interface Section {
  title: string;
  questions: Question[];
}

type Preset = '7d' | '30d' | 'mtd' | '90d' | 'custom';

/** Answers to the programme's 15 questions, grouped by theme, each with chart + table view. */
@Component({
  selector: 'app-insights',
  imports: [
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatSelectModule,
    MatIconModule,
    MatTooltipModule,
    ChartCard,
    ColumnChart,
    LineChart,
    BarList,
    StatusChip,
    DecimalPipe,
  ],
  templateUrl: './insights.html',
  styleUrl: './insights.scss',
})
export class Insights {
  private readonly api = inject(Api);
  protected readonly num = num;
  protected readonly minutesText = minutesText;
  protected readonly secondsText = secondsText;
  protected readonly stage = STAGE;
  protected readonly sourceLabel = REQUEST_SOURCE_LABEL;

  protected readonly sections: Section[] = [
    {
      title: 'Today',
      questions: [
        { q: 'Q1', anchor: 'q1', text: 'How many bad queries did we see today?' },
        { q: 'Q2', anchor: 'q2', text: 'How many of them were recurring?' },
        { q: 'Q3', anchor: 'q3', text: 'How many instances per query pattern?' },
        { q: 'Q8', anchor: 'q8', text: 'Which hour has the most bad queries?' },
      ],
    },
    {
      title: 'Tuning outcomes',
      questions: [
        { q: 'Q4', anchor: 'q4', text: "Prior day's bad SQL: optimized or not, and by theme" },
        { q: 'Q5', anchor: 'q5', text: 'How many tuned SQL were adopted and what did we save?' },
        { q: 'Q11', anchor: 'q11', text: 'Impact of a fixed project / theme after adoption' },
        { q: 'Q15', anchor: 'q15', text: 'Waiting for adoption and rejected optimizations' },
      ],
    },
    {
      title: 'Backlog & priorities',
      questions: [
        { q: 'Q6', anchor: 'q6', text: 'How many bad queries are outstanding?' },
        { q: 'Q12', anchor: 'q12', text: 'Which theme should we work on next?' },
        { q: 'Q9', anchor: 'q9', text: 'What is our end-to-end turnaround time?' },
      ],
    },
    {
      title: 'Users',
      questions: [
        { q: 'Q7', anchor: 'q7', text: 'Which user groups / users have the most bad queries?' },
        { q: 'Q13', anchor: 'q13', text: 'Which users always have bad SQL?' },
      ],
    },
    {
      title: 'Trends',
      questions: [
        { q: 'Q10', anchor: 'q10', text: 'Are outstanding bad queries going down month over month?' },
        { q: 'Q14', anchor: 'q14', text: 'Proactive (UAT) vs user tuning requests' },
      ],
    },
  ];

  // ---- controls
  protected readonly day = signal(today());
  protected preset: Preset = '30d';
  protected from = addDays(today(), -29);
  protected to = today();
  protected months = 12;
  protected themeDays = 30;

  // ---- data
  protected readonly daily = signal<DailyInsight | null>(null);
  protected readonly pipeline = signal<PipelineInsight | null>(null);
  protected readonly savings = signal<SavingsInsight | null>(null);
  protected readonly themes = signal<ThemePriority[]>([]);
  protected readonly users = signal<UsersInsight | null>(null);
  protected readonly trends = signal<TrendsInsight | null>(null);
  protected readonly loading = signal(false);

  constructor() {
    this.loadAll();
  }

  loadAll(): void {
    this.loading.set(true);
    forkJoin({
      daily: this.api.daily(this.day()),
      pipeline: this.api.pipelineInsight({ priorDay: addDays(this.day(), -1), from: this.from, to: this.to }),
      savings: this.api.savings({ from: this.from, to: this.to }),
      themes: this.api.themePriorities(this.themeDays),
      users: this.api.usersInsight({ from: this.from, to: this.to }),
      trends: this.api.trends(this.months),
    }).subscribe({
      next: (r) => {
        this.daily.set(r.daily);
        this.pipeline.set(r.pipeline);
        this.savings.set(r.savings);
        this.themes.set(r.themes);
        this.users.set(r.users);
        this.trends.set(r.trends);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  setPreset(p: Preset): void {
    this.preset = p;
    const t = today();
    if (p === '7d') this.from = addDays(t, -6);
    if (p === '30d') this.from = addDays(t, -29);
    if (p === '90d') this.from = addDays(t, -89);
    if (p === 'mtd') this.from = t.substring(0, 8) + '01';
    if (p !== 'custom') {
      this.to = t;
      this.loadAll();
    }
  }

  setDay(v: string): void {
    if (v) {
      this.day.set(v);
      this.loadAll();
    }
  }

  go(anchor: string): void {
    document.getElementById(anchor)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  // ------------------------------------------------------------------ Q1-Q3, Q8
  protected readonly q1Days = computed(() =>
    (this.daily()?.last14Days ?? []).map((d) => ({ label: d.label.substring(5), value: d.count, title: d.label })),
  );
  protected q1Answer(d: DailyInsight): string {
    const delta = signedPct(d.badQueries, d.badQueriesPreviousDay);
    return `${num(d.badQueries)} bad queries on ${d.date}${delta ? ' (' + delta + ')' : ''}, ${minutesText(d.totalMinutes)} of run time.`;
  }
  protected q2Rows(d: DailyInsight): BarRow[] {
    return [
      { label: 'Recurring queries', value: d.recurringQueries, sub: `${d.recurringPatterns} patterns seen on earlier days` },
      { label: 'Queries of new patterns', value: d.badQueries - d.recurringQueries, sub: `${d.newPatterns} patterns first seen this day` },
      { label: 'Patterns run more than once this day', value: d.repeatedTodayPatterns },
    ];
  }
  protected readonly q3Buckets = computed(() =>
    (this.daily()?.instanceBuckets ?? []).map((b) => ({ label: b.label, value: b.count, title: `${b.label} instances` })),
  );
  protected readonly q8Hours = computed(() =>
    (this.daily()?.byHour ?? []).map((h) => ({ label: h.label, value: h.count, title: `${h.label}:00 – ${h.label}:59` })),
  );
  protected q8Answer(d: DailyInsight): string {
    return d.peakHour === null
      ? 'No bad queries on this day.'
      : `${String(d.peakHour).padStart(2, '0')}:00 is the busiest hour with ${num(d.byHour[d.peakHour].count)} bad queries (${pct(d.byHour[d.peakHour].count, d.badQueries)} of the day).`;
  }

  // ------------------------------------------------------------------ Q4, Q5, Q11, Q15
  protected q4Rows(p: PipelineInsight): BarRow[] {
    const d = p.priorDay;
    return [
      { label: 'Optimized', value: d.optimizedPatterns, display: `${d.optimizedPatterns} · ${num(d.optimizedQueries)} queries` },
      { label: 'Not optimized – categorized', value: d.categorizedPatterns, sub: 'analysed, theme assigned' },
      { label: 'Not optimized – not categorized', value: d.uncategorizedPatterns, sub: 'on the tracker, no theme yet' },
      { label: 'Not optimized – not tracked', value: d.untrackedPatterns, sub: 'not analysed yet' },
    ];
  }
  protected q4ThemeRows(p: PipelineInsight): BarRow[] {
    return p.priorDay.themes.map((t) => ({ label: t.theme, value: t.patterns, display: `${t.patterns} · ${num(t.badQueries)} queries` }));
  }
  protected readonly q5Weeks = computed(() =>
    (this.savings()?.byWeek ?? []).map((w) => ({ label: w.label.substring(5), value: w.minutesSaved, title: `Week of ${w.label}` })),
  );
  protected q5Answer(s: SavingsInsight): string {
    return `${s.adoptedInPeriod} adopted in the period (${s.adoptedTotal} in total, ${s.adoptionRatePct ?? 0}% of ${s.tunedItems} tuned). Estimated ${minutesText(s.minutesSaved)} of wall-clock time saved.`;
  }
  protected q15Rows(p: PipelineInsight): BarRow[] {
    return p.rejections.byReason.map((r) => ({ label: r.label, value: r.count }));
  }

  // ------------------------------------------------------------------ Q6, Q12, Q9
  protected q6Rows(p: PipelineInsight): BarRow[] {
    const o = p.outstanding;
    return [
      { label: 'Not tracked yet', value: o.untrackedPatterns },
      { label: 'In progress', value: o.inProgressPatterns, sub: 'triage to tested' },
      { label: 'Awaiting adoption', value: o.awaitingAdoptionPatterns },
      { label: 'On hold / rejected', value: o.onHoldOrRejectedPatterns },
    ];
  }
  protected q12Rows(): BarRow[] {
    return this.themes().slice(0, 10).map((t) => ({
      label: `${t.rank}. ${t.theme}`,
      value: t.potentialMinutesPerMonth,
      display: minutesText(t.potentialMinutesPerMonth) + ' / month',
      sub: `${t.outstandingPatterns} patterns · ${num(t.badQueries)} queries`,
    }));
  }
  protected q9Rows(p: PipelineInsight): BarRow[] {
    return p.turnaround.byStage.map((s) => ({
      label: STAGE[s.stage].label,
      value: s.avgDays ?? 0,
      display: s.avgDays === null ? '—' : `${s.avgDays} days`,
    }));
  }

  // ------------------------------------------------------------------ Q7, Q13
  protected q7GroupRows(u: UsersInsight): BarRow[] {
    return u.groups.slice(0, 10).map((g) => ({
      label: g.userGroup,
      value: g.badQueries,
      sub: `${g.users} users · ${g.patterns} patterns`,
      display: `${num(g.badQueries)} (${g.sharePct}%)`,
    }));
  }
  protected q7UserRows(u: UsersInsight): BarRow[] {
    return u.users.slice(0, 10).map((x) => ({
      label: x.displayName ? `${x.displayName} (${x.userId})` : x.userId,
      value: x.badQueries,
      sub: x.userGroup ?? 'no user group',
      display: `${num(x.badQueries)} (${x.sharePct}%)`,
    }));
  }

  // ------------------------------------------------------------------ Q10, Q14
  protected readonly q10Labels = computed(() => (this.trends()?.months ?? []).map((m) => m.month));
  protected readonly q10Series = computed<Series[]>(() => {
    const m = this.trends()?.months ?? [];
    return [
      { name: 'Outstanding patterns (month end)', slot: 1, values: m.map((x) => x.outstandingPatterns) },
      { name: 'New patterns', slot: 2, values: m.map((x) => x.newPatterns) },
      { name: 'Adopted patterns', slot: 3, values: m.map((x) => x.adoptedPatterns) },
    ];
  });
  protected q10Answer(t: TrendsInsight): string {
    if (t.months.length < 2) return 'Not enough history yet.';
    const last = t.months[t.months.length - 1];
    const word = t.outstandingDirection === 'down' ? 'down' : t.outstandingDirection === 'up' ? 'up' : 'flat';
    return `Outstanding patterns are ${word}${t.outstandingChangePct !== null ? ' ' + Math.abs(t.outstandingChangePct) + '%' : ''} vs last month (${num(last.outstandingPatterns)} now; ${num(last.outstandingBadQueries)} bad queries this month from untuned patterns).`;
  }
  protected readonly q14Series = computed<Series[]>(() => {
    const r = this.trends()?.requestsByMonth ?? [];
    return REQUEST_SOURCES.map((s, i) => ({
      name: REQUEST_SOURCE_LABEL[s],
      slot: (i + 1) as 1 | 2 | 3,
      values: r.map((m) => m.created[s] ?? 0),
    }));
  });
  protected readonly sources = REQUEST_SOURCES;

  // ------------------------------------------------------------------ table views
  protected tableOf(rows: BarRow[], valueHeader: string): TableView {
    return { headers: ['', valueHeader], rows: rows.map((r) => [r.label, r.display ?? r.value]) };
  }
  protected trendTable(t: TrendsInsight): TableView {
    return {
      headers: ['Month', 'Bad queries', 'New patterns', 'Adopted', 'Outstanding patterns', 'Bad queries from outstanding'],
      rows: t.months.map((m) => [m.month, m.badQueries, m.newPatterns, m.adoptedPatterns, m.outstandingPatterns, m.outstandingBadQueries]),
    };
  }
  protected requestTable(t: TrendsInsight): TableView {
    return {
      headers: ['Month', ...this.sources.map((s) => REQUEST_SOURCE_LABEL[s])],
      rows: t.requestsByMonth.map((m) => [m.month, ...this.sources.map((s) => m.created[s] ?? 0)]),
    };
  }
}
