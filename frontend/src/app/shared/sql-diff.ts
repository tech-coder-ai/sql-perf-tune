import { Component, computed, effect, input, signal } from '@angular/core';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DiffRow, diffLines } from './diff';

type View = 'split' | 'inline';

/** Formats SQL the same way on both sides so the diff shows real changes, not layout (loaded on demand). */
async function formatSql(sql: string): Promise<string> {
  if (!sql.trim()) return sql;
  try {
    const { format } = await import('sql-formatter');
    // Impala is close to Hive; Impala hints (/* +BROADCAST */, [SHUFFLE]) are kept as comments / brackets
    return format(sql, { language: 'hive', keywordCase: 'upper', tabWidth: 2, linesBetweenQueries: 1 });
  } catch {
    return sql; // unparsable SQL: compare as written
  }
}

/**
 * Side-by-side (or inline) diff of two SQL statements: the original on the left, the optimized version on the
 * right. Removed text is red with a "−" marker, added text green with a "+", so changes never rely on colour.
 */
@Component({
  selector: 'app-sql-diff',
  imports: [MatButtonToggleModule, MatSlideToggleModule, MatIconModule, MatTooltipModule],
  template: `
    <div class="toolbar">
      <mat-button-toggle-group [value]="view()" (change)="view.set($event.value)" hideSingleSelectionIndicator aria-label="Diff view">
        <mat-button-toggle value="split"><mat-icon>vertical_split</mat-icon> Side by side</mat-button-toggle>
        <mat-button-toggle value="inline"><mat-icon>view_agenda</mat-icon> Inline</mat-button-toggle>
      </mat-button-toggle-group>
      <mat-slide-toggle [checked]="format()" (change)="format.set($event.checked)" matTooltip="Lay out both statements the same way first">
        Format SQL
      </mat-slide-toggle>
      <mat-slide-toggle [checked]="ignore()" (change)="ignore.set($event.checked)">Ignore case &amp; spacing</mat-slide-toggle>
      <mat-slide-toggle [checked]="fold()" (change)="fold.set($event.checked)">Hide unchanged lines</mat-slide-toggle>
      <span class="spacer"></span>
      @if (result(); as r) {
        @if (r.identical) {
          <span class="stat stat-same"><mat-icon>check</mat-icon> No differences</span>
        } @else {
          <span class="stat stat-changed" matTooltip="Lines edited">~{{ r.changed }} changed</span>
          <span class="stat stat-add" matTooltip="Lines only in the optimized SQL">+{{ r.added }} added</span>
          <span class="stat stat-del" matTooltip="Lines only in the original SQL">−{{ r.removed }} removed</span>
        }
      }
    </div>

    @if (view() === 'split') {
      <div class="diff split" role="table" aria-label="SQL differences, side by side">
        <div class="head" role="row">
          <div class="hdr-pre" role="columnheader">{{ leftLabel() }}</div>
          <div class="hdr-post" role="columnheader">{{ rightLabel() }}</div>
        </div>
        @for (row of result().rows; track $index) {
          @if (row.kind === 'skip') {
            <div class="skip" role="row" (click)="fold.set(false)" title="Show all lines">⋯ {{ row.skipped }} unchanged lines</div>
          } @else {
            <div class="line" [class]="row.kind" role="row">
              <div class="cell left" [class.empty]="!row.left" role="cell">
                @if (row.left; as l) {
                  <span class="no">{{ l.no }}</span><span class="mark">{{ markLeft(row) }}</span>
                  <code>@for (p of l.parts; track $index) {<span [class]="p.kind">{{ p.text }}</span>}</code>
                }
              </div>
              <div class="cell right" [class.empty]="!row.right" role="cell">
                @if (row.right; as r) {
                  <span class="no">{{ r.no }}</span><span class="mark">{{ markRight(row) }}</span>
                  <code>@for (p of r.parts; track $index) {<span [class]="p.kind">{{ p.text }}</span>}</code>
                }
              </div>
            </div>
          }
        }
      </div>
    } @else {
      <div class="diff inline" aria-label="SQL differences, inline">
        <div class="head single">
          <span class="key key-del">− {{ leftLabel() }}</span>
          <span class="key key-add">+ {{ rightLabel() }}</span>
        </div>
        @for (row of result().rows; track $index) {
          @switch (row.kind) {
            @case ('skip') {
              <div class="skip" (click)="fold.set(false)" title="Show all lines">⋯ {{ row.skipped }} unchanged lines</div>
            }
            @case ('same') {
              <div class="iline same"><span class="no">{{ row.left!.no }}</span><span class="no">{{ row.right!.no }}</span><span class="mark"></span><code>{{ row.left!.parts[0].text }}</code></div>
            }
            @default {
              @if (row.left; as l) {
                <div class="iline removed"><span class="no">{{ l.no }}</span><span class="no"></span><span class="mark">−</span><code>@for (p of l.parts; track $index) {<span [class]="p.kind">{{ p.text }}</span>}</code></div>
              }
              @if (row.right; as r) {
                <div class="iline added"><span class="no"></span><span class="no">{{ r.no }}</span><span class="mark">+</span><code>@for (p of r.parts; track $index) {<span [class]="p.kind">{{ p.text }}</span>}</code></div>
              }
            }
          }
        }
      </div>
    }
  `,
  styles: `
    :host { display: block; }
    .toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 18px; margin-bottom: 12px; }
    .toolbar mat-icon { font-size: 18px; width: 18px; height: 18px; vertical-align: -4px; margin-right: 4px; }
    mat-button-toggle-group { --mat-standard-button-toggle-height: 32px; }
    .spacer { flex: 1; }
    .stat { font-size: 12px; font-weight: 600; padding: 2px 10px; border-radius: 999px; border: 1px solid currentColor; }
    .stat-add { color: var(--spt-ok); }
    .stat-del { color: var(--spt-bad); }
    .stat-changed { color: var(--spt-delta-ink); }
    .stat-same { color: var(--spt-ok); display: inline-flex; align-items: center; }

    .diff { border: 1px solid var(--spt-border); border-radius: 10px; overflow: auto; max-height: 70vh;
            background: var(--spt-code-bg); color: var(--spt-code-fg); font-family: var(--spt-mono); font-size: 12.5px;
            font-variant-ligatures: none; /* show >= as typed, not as a single glyph */ }
    .head { display: grid; grid-template-columns: 1fr 1fr; position: sticky; top: 0; z-index: 1;
            font-family: var(--mat-sys-label-large-font, inherit); font-size: 13px; font-weight: 600; }
    .head > div { padding: 8px 12px; }
    .head .hdr-pre { background: var(--spt-pre); color: var(--spt-pre-ink); box-shadow: inset 0 3px 0 var(--spt-pre-ink); }
    .head .hdr-post { background: var(--spt-post); color: var(--spt-post-ink); box-shadow: inset 0 3px 0 var(--spt-post-ink); }
    .head.single { display: flex; gap: 16px; padding: 8px 12px; background: var(--mat-sys-surface-container); }
    .key { padding: 1px 10px; border-radius: 999px; }
    .key-del { background: var(--spt-diff-del); color: var(--spt-bad); }
    .key-add { background: var(--spt-diff-add); color: var(--spt-ok); }

    .line { display: grid; grid-template-columns: 1fr 1fr; }
    .cell { display: flex; min-width: 0; border-right: 1px solid var(--spt-border); }
    .cell.right { border-right: 0; }
    .cell.empty { background: repeating-linear-gradient(135deg, transparent 0 6px, var(--spt-border) 6px 7px); opacity: 0.6; }
    .no { flex: 0 0 40px; text-align: right; padding-right: 8px; color: var(--spt-muted); user-select: none; }
    .mark { flex: 0 0 14px; font-weight: 700; user-select: none; }
    code { flex: 1; white-space: pre-wrap; word-break: break-word; font: inherit; padding-right: 8px; }

    .line.removed .left, .line.changed .left, .iline.removed { background: var(--spt-diff-del); }
    .line.added .right, .line.changed .right, .iline.added { background: var(--spt-diff-add); }
    .line.removed .mark, .line.changed .left .mark, .iline.removed .mark { color: var(--spt-bad); }
    .line.added .mark, .line.changed .right .mark, .iline.added .mark { color: var(--spt-ok); }
    span.del { background: var(--spt-diff-del-strong); text-decoration: line-through; text-decoration-color: var(--spt-bad); border-radius: 2px; }
    span.add { background: var(--spt-diff-add-strong); border-radius: 2px; }
    .iline { display: flex; }
    .iline .no { flex-basis: 36px; }

    .skip { padding: 4px 12px; color: var(--spt-muted); background: var(--mat-sys-surface-container-low);
            border-block: 1px dashed var(--spt-border); cursor: pointer; font-family: var(--mat-sys-body-small-font, inherit); font-size: 12px; }
    .skip:hover { color: var(--mat-sys-primary); }
  `,
})
export class SqlDiff {
  readonly left = input<string | null | undefined>('');
  readonly right = input<string | null | undefined>('');
  readonly leftLabel = input('Original');
  readonly rightLabel = input('Optimized');

  /** side by side needs room; phones start with the inline view */
  protected readonly view = signal<View>(typeof window !== 'undefined' && window.innerWidth < 900 ? 'inline' : 'split');
  protected readonly format = signal(true);
  protected readonly ignore = signal(false);
  protected readonly fold = signal(true);

  private readonly formattedLeft = signal('');
  private readonly formattedRight = signal('');

  constructor() {
    // (re)format whenever the inputs or the "Format SQL" switch change
    effect(() => {
      const l = this.left() ?? '';
      const r = this.right() ?? '';
      if (!this.format()) {
        this.formattedLeft.set(l);
        this.formattedRight.set(r);
        return;
      }
      Promise.all([formatSql(l), formatSql(r)]).then(([fl, fr]) => {
        // ignore stale results if the inputs changed meanwhile
        if (l === (this.left() ?? '') && r === (this.right() ?? '')) {
          this.formattedLeft.set(fl);
          this.formattedRight.set(fr);
        }
      });
    });
  }

  protected readonly result = computed(() =>
    diffLines(this.formattedLeft(), this.formattedRight(), {
      ignoreCaseAndSpace: this.ignore(),
      context: this.fold() ? 3 : undefined,
    }),
  );

  protected markLeft(row: DiffRow): string {
    return row.kind === 'removed' || row.kind === 'changed' ? '−' : '';
  }

  protected markRight(row: DiffRow): string {
    return row.kind === 'added' || row.kind === 'changed' ? '+' : '';
  }
}
