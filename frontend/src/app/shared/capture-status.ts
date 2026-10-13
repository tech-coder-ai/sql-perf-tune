import { Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { CaptureStatus } from '../core/models';

/**
 * Where the automatic explain plan / profile / DDL fetch of a tracked SQL stands. Shown on every tab whose content
 * depends on it, so nobody works on half-captured diagnostics without knowing.
 */
@Component({
  selector: 'app-capture-status',
  imports: [MatButtonModule, MatIconModule, MatProgressBarModule],
  template: `
    @switch (status()) {
      @case ('PENDING') {
        <div class="cap busy" role="status">
          <mat-icon>hourglass_top</mat-icon>
          <div class="text">
            <b>Diagnostics capture in progress (queued).</b>
            Explain plan, query profile and table DDL are being fetched from {{ engine() }} in the
            background.
            {{ consequence() }}
          </div>
          <mat-progress-bar mode="indeterminate" />
        </div>
      }
      @case ('RUNNING') {
        <div class="cap busy" role="status">
          <mat-icon>sync</mat-icon>
          <div class="text">
            <b>Diagnostics capture in progress.</b>
            Fetching the explain plan, query profile and table DDL from {{ engine() }}
            @if (since()) {
              (started {{ since() }})
            }
            .
            {{ consequence() }}
          </div>
          <mat-progress-bar mode="indeterminate" />
        </div>
      }
      @case ('FAILED') {
        <div class="cap failed" role="alert">
          <mat-icon>error</mat-icon>
          <div class="text">
            <b>Automatic diagnostics capture failed.</b> {{ message() }}
            Capture them on the group page, or try again.
          </div>
          <button mat-stroked-button (click)="retry.emit()">
            <mat-icon>refresh</mat-icon> Try again
          </button>
        </div>
      }
      @case ('DONE') {
        @if (!compact()) {
          <div class="cap done" role="status">
            <mat-icon>check_circle</mat-icon>
            <div class="text">
              <b>Diagnostics captured automatically</b>
              @if (finished()) {
                on {{ finished() }}
              }
              . {{ message() }}
            </div>
            <button
              mat-button
              (click)="retry.emit()"
              title="Fetch explain plan, profile and DDL again"
            >
              <mat-icon>refresh</mat-icon> Capture again
            </button>
          </div>
        }
      }
    }
  `,
  styles: `
    .cap {
      display: grid;
      grid-template-columns: auto 1fr auto;
      gap: 4px 12px;
      align-items: center;
      padding: 10px 14px;
      border-radius: 10px;
      font-size: 13px;
      line-height: 1.45;
      margin-bottom: 12px;
    }
    .cap mat-progress-bar {
      grid-column: 1 / -1;
      border-radius: 4px;
    }
    .busy {
      background: var(--spt-accent-soft);
      color: var(--spt-info);
    }
    .failed {
      background: var(--spt-bad-soft);
      color: var(--spt-bad);
    }
    .done {
      background: var(--spt-post);
      color: var(--spt-post-ink);
    }
    .text {
      min-width: 0;
    }
  `,
})
export class CaptureStatusBanner {
  readonly status = input<CaptureStatus | null>(null);
  readonly requestedAt = input<string | null>(null);
  readonly finishedAt = input<string | null>(null);
  readonly message = input<string | null>(null);
  readonly engine = input('Impala');
  /** what is not available yet on the tab showing the banner */
  readonly consequence = input(
    'The original-run metrics, explain plan and DDL fill in by themselves when it finishes.',
  );
  /** hide the "done" line (tabs where it adds nothing) */
  readonly compact = input(false);
  readonly retry = output<void>();

  protected readonly since = computed(() => clock(this.requestedAt()));
  protected readonly finished = computed(() => {
    const f = this.finishedAt();
    return f ? f.replace('T', ' ').substring(0, 16) : null;
  });
}

function clock(ts: string | null): string | null {
  if (!ts) return null;
  const secs = Math.max(0, Math.round((Date.now() - new Date(ts).getTime()) / 1000));
  return secs < 60 ? `${secs} s ago` : `${Math.round(secs / 60)} min ago`;
}
