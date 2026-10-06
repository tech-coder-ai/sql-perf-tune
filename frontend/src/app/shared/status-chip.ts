import { Component, computed, input } from '@angular/core';

const TONE: Record<string, string> = {
  NEW: 'info',
  DIAGNOSTICS_CAPTURED: 'info',
  OPTIMIZATION_REQUESTED: 'warn',
  OPTIMIZED: 'primary',
  POST_RUN_VALIDATED: 'primary',
  SME_VALIDATION: 'warn',
  ADOPTED: 'ok',
  REJECTED: 'bad',
  ON_HOLD: 'muted',
  COMPLETED: 'ok',
  COMPLETED_WITH_ERRORS: 'warn',
  FAILED: 'bad',
  RUNNING: 'info',
  PENDING: 'warn',
  CAPTURED: 'ok',
  TIMEOUT: 'bad',
  CRITICAL: 'bad',
  HIGH: 'warn',
  MEDIUM: 'info',
  LOW: 'muted',
  ACCEPTED: 'ok',
  ORIGINAL: 'muted',
  OPTIMIZED_PHASE: 'primary',
  IMPALA: 'primary',
  ORACLE: 'warn',
  GENERIC_JDBC: 'muted',
};

@Component({
  selector: 'app-status',
  template: `<span class="chip" [class]="'chip ' + tone()">{{ label() }}</span>`,
  styles: `
    .chip {
      display: inline-block;
      padding: 2px 8px;
      border-radius: 999px;
      font: var(--mat-sys-label-small);
      font-weight: 600;
      letter-spacing: 0.02em;
      white-space: nowrap;
      border: 1px solid currentColor;
    }
    .ok { color: var(--spt-ok); }
    .warn { color: var(--spt-warn); }
    .bad { color: var(--spt-bad); }
    .info { color: var(--spt-info); }
    .primary { color: var(--mat-sys-primary); }
    .muted { color: var(--spt-muted); }
  `,
})
export class StatusChip {
  readonly value = input<string | null | undefined>();
  protected readonly tone = computed(() => TONE[this.value() ?? ''] ?? 'muted');
  protected readonly label = computed(() => (this.value() ?? '—').replaceAll('_', ' ').toLowerCase());
}
