import { Component, input } from '@angular/core';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

/**
 * Wraps a page's data area while its API requests run: a spinner when there is nothing to show yet, then a
 * progress bar over the previous numbers (dimmed) while a new day / period loads, so stale figures are never
 * mistaken for the new ones.
 */
@Component({
  selector: 'app-loading',
  imports: [MatProgressBarModule, MatProgressSpinnerModule],
  template: `
    @if (loading()) {
      <mat-progress-bar class="bar" mode="indeterminate" aria-label="Loading" />
    }
    @if (loading() && empty()) {
      <div class="first" role="status">
        <mat-spinner diameter="40" />
        <span>{{ label() }}</span>
      </div>
    }
    <div class="content" [class.stale]="loading() && !empty()" [attr.aria-busy]="loading()">
      <ng-content />
    </div>
  `,
  styles: `
    :host {
      display: block;
      position: relative;
    }
    .bar {
      position: sticky;
      top: 0;
      z-index: 5;
      border-radius: 4px;
      margin-bottom: -4px;
    }
    .first {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 14px;
      padding: 72px 0;
      color: var(--spt-muted);
      font-size: 14px;
    }
    .content {
      transition: opacity 0.15s;
    }
    .content.stale {
      opacity: 0.45;
      pointer-events: none;
    }
  `,
})
export class LoadingArea {
  readonly loading = input(false);
  /** nothing loaded yet: show the spinner instead of dimmed content */
  readonly empty = input(false);
  readonly label = input('Loading…');
}
