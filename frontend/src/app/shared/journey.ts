import { DatePipe } from '@angular/common';
import { Component, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Journey } from '../core/models';
import { REQUEST_SOURCE_LABEL, STAGE } from '../core/stages';

/** Stepper showing where a SQL is on its way to adoption, with time spent per stage. */
@Component({
  selector: 'app-journey',
  imports: [MatIconModule, MatTooltipModule, DatePipe],
  template: `
    @if (journey(); as j) {
      <div class="summary">
        <span>
          <b>{{ stage(j.current).label }}</b>
          @if (j.currentSince) {
            since {{ j.currentSince | date: 'd MMM y' }}
          }
        </span>
        <span class="muted">·</span>
        <span>{{ source(j) }} {{ j.detectedAt | date: 'd MMM y' }}</span>
        <span class="muted">·</span>
        <span>{{ j.daysSinceDetected }} days since detection, {{ j.totalDays }} on the tracker</span>
        @if (j.current === 'ON_HOLD' || j.current === 'REJECTED') {
          <span [class]="'side ' + (j.current === 'REJECTED' ? 'bad' : 'muted')">
            <mat-icon>{{ stage(j.current).icon }}</mat-icon>{{ stage(j.current).label }}
          </span>
        }
      </div>
      <ol class="steps">
        @for (s of j.steps; track s.stage; let last = $last) {
          <li [class]="s.state" [matTooltip]="stage(s.stage).hint">
            <div class="dot">
              <mat-icon>{{ s.state === 'done' ? 'check' : s.state === 'skipped' ? 'remove' : stage(s.stage).icon }}</mat-icon>
            </div>
            <div class="text">
              <div class="name">{{ stage(s.stage).label }}</div>
              <div class="meta">
                @if (s.state === 'skipped') {
                  skipped
                } @else if (s.enteredAt) {
                  {{ s.enteredAt | date: 'd MMM' }}
                  @if (s.daysInStage !== null) {
                    · {{ s.daysInStage }}d
                  }
                } @else {
                  &nbsp;
                }
              </div>
            </div>
            @if (!last) {
              <div class="bar"></div>
            }
          </li>
        }
      </ol>
    }
  `,
  styles: `
    :host { display: block; }
    .summary { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; font-size: 13px; margin-bottom: 14px; }
    .muted { color: var(--spt-muted); }
    .side { display: inline-flex; align-items: center; gap: 4px; font-weight: 600; margin-left: 6px; }
    .side.bad { color: var(--spt-bad); }
    .side mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .steps { list-style: none; display: flex; margin: 0; padding: 0; gap: 0; overflow-x: auto; }
    li { flex: 1 1 0; min-width: 104px; position: relative; display: flex; flex-direction: column; align-items: center; text-align: center; }
    .dot {
      width: 34px; height: 34px; border-radius: 50%; display: grid; place-items: center; z-index: 1;
      border: 2px solid var(--spt-border); background: var(--spt-panel); color: var(--spt-muted);
    }
    .dot mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .bar { position: absolute; top: 17px; left: calc(50% + 19px); right: calc(-50% + 19px); height: 2px; background: var(--spt-border); }
    li.done .dot { background: var(--spt-accent); border-color: var(--spt-accent); color: #fff; }
    li.done .bar { background: var(--spt-accent); }
    li.current .dot { border-color: var(--spt-accent); color: var(--spt-accent); box-shadow: 0 0 0 4px var(--spt-accent-soft); }
    li.stopped .dot { border-color: var(--spt-warn); color: var(--spt-warn); }
    li.skipped .dot { border-style: dashed; }
    li.skipped .bar, li.done.skipped .bar { background: var(--spt-accent); opacity: .4; }
    .text { margin-top: 6px; padding: 0 4px; }
    .name { font-size: 12px; font-weight: 600; }
    li.pending .name, li.skipped .name { color: var(--spt-muted); font-weight: 500; }
    li.current .name { color: var(--spt-accent); }
    .meta { font-size: 11px; color: var(--spt-muted); font-variant-numeric: tabular-nums; }
  `,
})
export class JourneyView {
  readonly journey = input<Journey | null>(null);
  protected readonly stage = (s: keyof typeof STAGE) => STAGE[s];
  protected source(j: Journey): string {
    return j.requestSource === 'LOG_DETECTED' ? 'first seen in logs' : REQUEST_SOURCE_LABEL[j.requestSource] + ', requested';
  }
}
