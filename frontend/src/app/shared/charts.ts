import {
  Component,
  Directive,
  ElementRef,
  OnDestroy,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';

/*
 * Lightweight SVG charts on the app's chart tokens (--chart-1..3, --chart-grid, --chart-axis, --chart-ink),
 * which switch with the light / dark theme. Mark specs: columns <= 24px with a 4px rounded data end,
 * 2px lines, >= 8px end markers with a surface ring, hairline grid, per-mark hover tooltip.
 */

export interface Datum {
  label: string;
  value: number;
  /** optional longer label for the tooltip */
  title?: string;
}

export interface Series {
  name: string;
  values: number[];
  /** categorical slot 1..3 */
  slot: 1 | 2 | 3;
}

const fmt = new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 });
export const compact = (v: number) =>
  Math.abs(v) >= 10_000 ? new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }).format(v) : fmt.format(v);

/** 0 .. max with 4-5 round ticks. */
export function niceTicks(max: number): number[] {
  if (max <= 0) return [0, 1];
  const raw = max / 4;
  const pow = Math.pow(10, Math.floor(Math.log10(raw)));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * pow).find((s) => s >= raw) ?? raw;
  const ticks: number[] = [];
  for (let v = 0; v <= max + step * 0.001; v += step) ticks.push(+v.toFixed(6));
  if (ticks[ticks.length - 1] < max) ticks.push(+(ticks[ticks.length - 1] + step).toFixed(6));
  return ticks;
}

/** Tracks the host width so charts re-layout on resize. */
@Directive()
abstract class Responsive implements OnInit, OnDestroy {
  protected readonly host = inject(ElementRef<HTMLElement>);
  protected readonly width = signal(600);
  private ro?: ResizeObserver;

  ngOnInit(): void {
    this.ro = new ResizeObserver((e) => this.width.set(Math.max(240, Math.floor(e[0].contentRect.width))));
    this.ro.observe(this.host.nativeElement);
  }

  ngOnDestroy(): void {
    this.ro?.disconnect();
  }
}

// ------------------------------------------------------------------------------------------------ columns

@Component({
  selector: 'app-column-chart',
  template: `
    <svg [attr.width]="width()" [attr.height]="height()" role="img" [attr.aria-label]="ariaLabel()">
      @for (t of layout().ticks; track t.v) {
        <line [attr.x1]="layout().left" [attr.x2]="width()" [attr.y1]="t.y" [attr.y2]="t.y" class="grid" />
        <text [attr.x]="layout().left - 6" [attr.y]="t.y + 4" class="tick" text-anchor="end">{{ t.label }}</text>
      }
      @for (b of layout().bars; track b.i) {
        <g (mouseenter)="hover.set(b.i)" (mouseleave)="hover.set(null)" (click)="pick.emit(b.i)" class="hit">
          <rect [attr.x]="b.bandX" [attr.y]="0" [attr.width]="b.bandW" [attr.height]="layout().base" class="band" />
          @if (b.h > 0) {
            <path [attr.d]="b.path" [class]="'bar s' + slot()" [class.dim]="highlight() !== null && highlight() !== b.i" />
          }
        </g>
        @if (b.showLabel) {
          <text [attr.x]="b.cx" [attr.y]="layout().base + 16" class="tick" text-anchor="middle">{{ b.label }}</text>
        }
      }
      <line [attr.x1]="layout().left" [attr.x2]="width()" [attr.y1]="layout().base" [attr.y2]="layout().base" class="axis" />
    </svg>
    @if (hover() !== null) {
      @let b = layout().bars[hover()!];
      <div class="tip" [style.left.px]="b.cx" [style.top.px]="b.y - 8">
        <b>{{ b.value }}</b> {{ unit() }}<br /><span>{{ b.title }}</span>
      </div>
    }
  `,
  styles: `
    :host { display: block; position: relative; }
    svg { display: block; overflow: visible; }
    .grid { stroke: var(--chart-grid); stroke-width: 1; }
    .axis { stroke: var(--chart-axis); stroke-width: 1; }
    .tick { fill: var(--chart-ink); font-size: 11px; font-variant-numeric: tabular-nums; }
    .band { fill: transparent; }
    .hit { cursor: default; }
    .hit:hover .band { fill: var(--spt-row-hover); }
    .bar.s1 { fill: var(--chart-1); }
    .bar.s2 { fill: var(--chart-2); }
    .bar.s3 { fill: var(--chart-3); }
    .bar.dim { opacity: 0.45; }
    .tip {
      position: absolute; transform: translate(-50%, -100%); pointer-events: none; white-space: nowrap;
      background: var(--mat-sys-inverse-surface); color: var(--mat-sys-inverse-on-surface);
      padding: 6px 8px; border-radius: 6px; font-size: 12px; z-index: 2; box-shadow: 0 2px 8px rgba(0,0,0,.2);
    }
    .tip span { opacity: .8; }
  `,
})
export class ColumnChart extends Responsive {
  readonly data = input.required<Datum[]>();
  readonly height = input(200);
  readonly slot = input<1 | 2 | 3>(1);
  readonly unit = input('');
  readonly highlight = input<number | null>(null);
  readonly ariaLabel = input('Column chart');
  readonly pick = output<number>();
  protected readonly hover = signal<number | null>(null);

  protected readonly layout = computed(() => {
    const data = this.data();
    const w = this.width();
    const left = 40;
    const base = this.height() - 24;
    const ticks = niceTicks(Math.max(0, ...data.map((d) => d.value)));
    const max = ticks[ticks.length - 1] || 1;
    const band = (w - left) / Math.max(1, data.length);
    const barW = Math.min(24, Math.max(4, band * 0.6));
    const labelEvery = Math.ceil(data.length / Math.max(1, Math.floor((w - left) / 36)));
    return {
      left,
      base,
      ticks: ticks.map((v) => ({ v, y: base - (v / max) * (base - 8), label: compact(v) })),
      bars: data.map((d, i) => {
        const h = (d.value / max) * (base - 8);
        const x = left + i * band + (band - barW) / 2;
        const y = base - h;
        const r = Math.min(4, barW / 2, h);
        return {
          i,
          h,
          y,
          cx: x + barW / 2,
          bandX: left + i * band,
          bandW: band,
          value: compact(d.value),
          label: d.label,
          title: d.title ?? d.label,
          showLabel: i % labelEvery === 0,
          path: `M${x},${base} V${y + r} Q${x},${y} ${x + r},${y} H${x + barW - r} Q${x + barW},${y} ${x + barW},${y + r} V${base} Z`,
        };
      }),
    };
  });
}

// ------------------------------------------------------------------------------------------------ bar list

export interface BarRow {
  label: string;
  value: number;
  /** text shown at the bar end (defaults to the value) */
  display?: string;
  sub?: string;
  key?: string | number;
}

/** Ranked horizontal bars (categories with long names): label, bar, value. */
@Component({
  selector: 'app-bar-list',
  template: `
    @for (r of rows(); track r.label; let i = $index) {
      <div class="row" [class.clickable]="clickable()" (click)="clickable() && pick.emit(r)" [title]="r.label + ': ' + (r.display ?? r.value)">
        <div class="label">
          <span class="name">{{ r.label }}</span>
          @if (r.sub) {
            <span class="sub">{{ r.sub }}</span>
          }
        </div>
        <div class="track"><div [class]="'bar s' + slot()" [style.width.%]="pct(r.value)"></div></div>
        <div class="value">{{ r.display ?? fmtv(r.value) }}</div>
      </div>
    } @empty {
      <div class="empty">No data</div>
    }
  `,
  styles: `
    :host { display: block; }
    .row { display: grid; grid-template-columns: minmax(110px, 40%) 1fr auto; gap: 10px; align-items: center; padding: 5px 6px; border-radius: 6px; }
    .row.clickable { cursor: pointer; }
    .row:hover { background: var(--spt-row-hover); }
    .label { min-width: 0; display: flex; flex-direction: column; }
    .name { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-size: 13px; }
    .sub { color: var(--spt-muted); font-size: 11px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .track { height: 12px; }
    .bar { height: 12px; border-radius: 0 4px 4px 0; min-width: 2px; }
    .bar.s1 { background: var(--chart-1); }
    .bar.s2 { background: var(--chart-2); }
    .bar.s3 { background: var(--chart-3); }
    .value { font-variant-numeric: tabular-nums; font-size: 13px; white-space: nowrap; text-align: right; min-width: 48px; }
    .empty { color: var(--spt-muted); padding: 12px 0; }
  `,
})
export class BarList {
  readonly rows = input.required<BarRow[]>();
  readonly slot = input<1 | 2 | 3>(1);
  readonly clickable = input(false);
  readonly pick = output<BarRow>();
  private readonly max = computed(() => Math.max(1, ...this.rows().map((r) => r.value)));
  protected pct(v: number): number {
    return Math.max(0, (v / this.max()) * 100);
  }
  protected fmtv(v: number): string {
    return compact(v);
  }
}

// ------------------------------------------------------------------------------------------------ lines

@Component({
  selector: 'app-line-chart',
  template: `
    @if (series().length > 1) {
      <div class="legend">
        @for (s of series(); track s.name) {
          <span class="key"><i [class]="'sw s' + s.slot"></i>{{ s.name }}</span>
        }
      </div>
    }
    <div class="plot" (mouseleave)="hover.set(null)">
      <svg [attr.width]="width()" [attr.height]="height()" role="img" [attr.aria-label]="ariaLabel()">
        @for (t of layout().ticks; track t.v) {
          <line [attr.x1]="layout().left" [attr.x2]="width() - 8" [attr.y1]="t.y" [attr.y2]="t.y" class="grid" />
          <text [attr.x]="layout().left - 6" [attr.y]="t.y + 4" class="tick" text-anchor="end">{{ t.label }}</text>
        }
        @for (x of layout().xs; track $index) {
          @if (x.showLabel) {
            <text [attr.x]="x.x" [attr.y]="layout().base + 16" class="tick" text-anchor="middle">{{ x.label }}</text>
          }
          <rect [attr.x]="x.x - layout().step / 2" y="0" [attr.width]="layout().step" [attr.height]="layout().base"
                fill="transparent" (mouseenter)="hover.set($index)" />
        }
        @if (hover() !== null) {
          <line [attr.x1]="layout().xs[hover()!].x" [attr.x2]="layout().xs[hover()!].x" y1="0" [attr.y2]="layout().base" class="cross" />
        }
        @for (s of layout().lines; track s.name) {
          <path [attr.d]="s.path" [class]="'line s' + s.slot" />
          <circle [attr.cx]="s.endX" [attr.cy]="s.endY" r="4" [class]="'dot s' + s.slot" />
          @if (hover() !== null) {
            <circle [attr.cx]="layout().xs[hover()!].x" [attr.cy]="s.ys[hover()!]" r="4" [class]="'dot s' + s.slot" />
          }
        }
        <line [attr.x1]="layout().left" [attr.x2]="width() - 8" [attr.y1]="layout().base" [attr.y2]="layout().base" class="axis" />
      </svg>
      @if (hover() !== null) {
        <div class="tip" [style.left.px]="layout().xs[hover()!].x" style="top: 0">
          <b>{{ labels()[hover()!] }}</b>
          @for (s of series(); track s.name) {
            <div><i [class]="'sw s' + s.slot"></i>{{ s.name }}: <b>{{ fmtv(s.values[hover()!]) }}</b></div>
          }
        </div>
      }
    </div>
  `,
  styles: `
    :host { display: block; }
    .plot { position: relative; }
    svg { display: block; overflow: visible; }
    .legend { display: flex; gap: 14px; flex-wrap: wrap; margin-bottom: 6px; font-size: 12px; color: var(--chart-ink); }
    .key { display: inline-flex; align-items: center; gap: 6px; }
    .sw { display: inline-block; width: 14px; height: 3px; border-radius: 2px; margin-right: 4px; vertical-align: middle; }
    .sw.s1, .dot.s1 { background: var(--chart-1); fill: var(--chart-1); }
    .sw.s2, .dot.s2 { background: var(--chart-2); fill: var(--chart-2); }
    .sw.s3, .dot.s3 { background: var(--chart-3); fill: var(--chart-3); }
    .dot { stroke: var(--spt-panel); stroke-width: 2; }
    .line { fill: none; stroke-width: 2; stroke-linejoin: round; stroke-linecap: round; }
    .line.s1 { stroke: var(--chart-1); }
    .line.s2 { stroke: var(--chart-2); }
    .line.s3 { stroke: var(--chart-3); }
    .grid { stroke: var(--chart-grid); stroke-width: 1; }
    .axis { stroke: var(--chart-axis); stroke-width: 1; }
    .cross { stroke: var(--chart-axis); stroke-width: 1; }
    .tick { fill: var(--chart-ink); font-size: 11px; font-variant-numeric: tabular-nums; }
    .tip {
      position: absolute; transform: translate(12px, 0); pointer-events: none; white-space: nowrap;
      background: var(--mat-sys-inverse-surface); color: var(--mat-sys-inverse-on-surface);
      padding: 6px 8px; border-radius: 6px; font-size: 12px; z-index: 2; box-shadow: 0 2px 8px rgba(0,0,0,.2);
    }
  `,
})
export class LineChart extends Responsive {
  readonly series = input.required<Series[]>();
  readonly labels = input.required<string[]>();
  readonly height = input(220);
  readonly ariaLabel = input('Line chart');
  protected readonly hover = signal<number | null>(null);

  protected readonly layout = computed(() => {
    const w = this.width();
    const left = 44;
    const base = this.height() - 24;
    const n = this.labels().length;
    const ticks = niceTicks(Math.max(0, ...this.series().flatMap((s) => s.values)));
    const max = ticks[ticks.length - 1] || 1;
    const step = n > 1 ? (w - left - 16) / (n - 1) : 0;
    const x = (i: number) => left + 8 + i * step;
    const y = (v: number) => base - (v / max) * (base - 8);
    const labelEvery = Math.ceil(n / Math.max(1, Math.floor((w - left) / 60)));
    return {
      left,
      base,
      step: Math.max(step, 12),
      ticks: ticks.map((v) => ({ v, y: y(v), label: compact(v) })),
      xs: this.labels().map((label, i) => ({ x: x(i), label, showLabel: i % labelEvery === 0 || i === n - 1 })),
      lines: this.series().map((s) => ({
        name: s.name,
        slot: s.slot,
        ys: s.values.map(y),
        path: s.values.map((v, i) => `${i ? 'L' : 'M'}${x(i)},${y(v)}`).join(' '),
        endX: x(s.values.length - 1),
        endY: y(s.values[s.values.length - 1] ?? 0),
      })),
    };
  });

  protected fmtv(v: number): string {
    return compact(v);
  }
}

// ------------------------------------------------------------------------------------------------ tiles & cards

/** Stat tile: label, value, optional delta (coloured by whether up is good) and hint. */
@Component({
  selector: 'app-stat-tile',
  imports: [MatIconModule, RouterLink],
  template: `
    <a class="tile" [class.hero]="hero()" [routerLink]="link()" [queryParams]="query()" [class.static]="!link()">
      <div class="top">
        @if (icon()) {
          <mat-icon>{{ icon() }}</mat-icon>
        }
        <span class="label">{{ label() }}</span>
        @if (q()) {
          <span class="q">{{ q() }}</span>
        }
      </div>
      <div class="value">{{ value() }}</div>
      @if (delta()) {
        <div class="delta" [class.good]="deltaGood() === true" [class.bad]="deltaGood() === false">{{ delta() }}</div>
      }
      @if (hint()) {
        <div class="hint">{{ hint() }}</div>
      }
    </a>
  `,
  styles: `
    .tile {
      display: block; height: 100%; box-sizing: border-box; padding: 14px 16px; border-radius: 12px; text-decoration: none;
      color: inherit; background: var(--spt-panel); border: 1px solid var(--spt-border); box-shadow: var(--spt-shadow);
      transition: border-color .15s, transform .15s;
    }
    .tile:not(.static):hover { border-color: var(--spt-accent); transform: translateY(-1px); }
    .tile.static { cursor: default; }
    .top { display: flex; align-items: center; gap: 8px; color: var(--spt-muted); }
    .top mat-icon { font-size: 20px; width: 20px; height: 20px; color: var(--spt-accent); }
    .label { font: var(--mat-sys-label-large); flex: 1; }
    .q { font-size: 10px; font-weight: 700; color: var(--spt-accent); background: var(--spt-accent-soft); border-radius: 4px; padding: 1px 5px; }
    .value { font-size: 28px; font-weight: 600; margin-top: 6px; line-height: 1.15; }
    .hero .value { font-size: 48px; }
    .delta { font-size: 12px; margin-top: 2px; color: var(--spt-muted); }
    .delta.good { color: var(--spt-ok); }
    .delta.bad { color: var(--spt-bad); }
    .hint { font-size: 12px; color: var(--spt-muted); margin-top: 2px; }
  `,
})
export class StatTile {
  readonly label = input.required<string>();
  readonly value = input.required<string | number>();
  readonly icon = input<string>();
  readonly q = input<string>();
  readonly delta = input<string | null>();
  readonly deltaGood = input<boolean | null>(null);
  readonly hint = input<string | null>();
  readonly link = input<string | unknown[] | null>(null);
  readonly query = input<Record<string, unknown> | null>(null);
  readonly hero = input(false);
}

export interface TableView {
  headers: string[];
  rows: (string | number | null)[][];
}

/**
 * Card around a chart: the business question it answers, a one-line answer, and a Chart / Table switch
 * (the table carries every value, for accessibility and copy-paste).
 */
@Component({
  selector: 'app-chart-card',
  imports: [MatButtonToggleModule, MatIconModule],
  template: `
    <section class="card" [attr.id]="anchor()">
      <header>
        <div class="titles">
          <div class="title">
            @if (q()) {
              <span class="q">{{ q() }}</span>
            }
            {{ title() }}
          </div>
          @if (answer()) {
            <div class="answer">{{ answer() }}</div>
          }
        </div>
        @if (table()) {
          <mat-button-toggle-group [value]="mode()" (change)="mode.set($event.value)" hideSingleSelectionIndicator aria-label="View">
            <mat-button-toggle value="chart" aria-label="Chart view"><mat-icon>bar_chart</mat-icon></mat-button-toggle>
            <mat-button-toggle value="table" aria-label="Table view"><mat-icon>table_rows</mat-icon></mat-button-toggle>
          </mat-button-toggle-group>
        }
      </header>
      @if (mode() === 'chart' || !table()) {
        <div class="body"><ng-content /></div>
      } @else {
        <div class="table-wrap">
          <table class="simple">
            <tr>
              @for (h of table()!.headers; track $index) {
                <th [class.num]="$index > 0">{{ h }}</th>
              }
            </tr>
            @for (r of table()!.rows; track $index) {
              <tr>
                @for (c of r; track $index) {
                  <td [class.num]="$index > 0">{{ c ?? '—' }}</td>
                }
              </tr>
            }
          </table>
        </div>
      }
      @if (note()) {
        <div class="note">{{ note() }}</div>
      }
    </section>
  `,
  styles: `
    :host { display: block; min-width: 0; }
    .card { height: 100%; box-sizing: border-box; scroll-margin-top: 190px; }
    .body { overflow-x: auto; }
    header { display: flex; gap: 12px; align-items: flex-start; margin-bottom: 12px; }
    .titles { flex: 1; min-width: 0; }
    .title { font: var(--mat-sys-title-medium); font-weight: 600; }
    .q { font-size: 11px; font-weight: 700; color: var(--spt-accent); background: var(--spt-accent-soft); border-radius: 4px; padding: 2px 6px; margin-right: 6px; vertical-align: 2px; }
    .answer { color: var(--spt-muted); margin-top: 4px; font-size: 13px; }
    .table-wrap { max-height: 360px; overflow: auto; }
    .note { color: var(--spt-muted); font-size: 12px; margin-top: 10px; }
    mat-button-toggle-group { --mat-standard-button-toggle-height: 30px; }
    mat-icon { font-size: 18px; width: 18px; height: 18px; }
  `,
})
export class ChartCard {
  readonly title = input.required<string>();
  readonly q = input<string>();
  readonly answer = input<string | null>();
  readonly note = input<string | null>();
  readonly anchor = input<string>();
  readonly table = input<TableView | null>(null);
  protected readonly mode = signal<'chart' | 'table'>('chart');
}
