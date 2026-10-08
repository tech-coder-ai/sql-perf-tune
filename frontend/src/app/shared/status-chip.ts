import { Component, computed, input } from '@angular/core';
import { STAGE } from '../core/stages';

const TONE: Record<string, string> = {
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
  PROCESSED: 'ok',
  ALREADY_LOADED: 'muted',
  DUPLICATE_FILE: 'warn',
  IMPALA: 'primary',
  ORACLE: 'warn',
  GENERIC_JDBC: 'muted',
  PROPOSED: 'info',
  TESTED: 'primary',
  SELECTED: 'warn',
  LOG_DETECTED: 'muted',
  PROACTIVE_UAT: 'primary',
  USER_REQUEST: 'info',
  AI_AGENT: 'primary',
  MANUAL: 'muted',
};

const LABEL: Record<string, string> = {
  LOG_DETECTED: 'from logs',
  PROACTIVE_UAT: 'proactive UAT',
  USER_REQUEST: 'user request',
  AI_AGENT: 'AI agent',
  SELECTED: 'selected for adoption',
};

/**
 * Pill for statuses. Workflow statuses show their business stage name (e.g. "Awaiting adoption"); pass
 * [tone] / [text] to colour free values such as dropdown entries.
 */
@Component({
  selector: 'app-status',
  template: `<span [class]="'chip ' + resolvedTone()">{{ resolvedLabel() }}</span>`,
  styles: `
    .chip {
      display: inline-block;
      padding: 1px 8px;
      border-radius: 999px;
      font: var(--mat-sys-label-small);
      font-weight: 600;
      letter-spacing: 0.02em;
      white-space: nowrap;
      border: 1px solid currentColor;
      line-height: 18px;
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
  readonly tone = input<string | null | undefined>();
  readonly text = input<string | null | undefined>();

  protected readonly resolvedTone = computed(() => {
    const v = this.value() ?? '';
    return this.tone() ?? STAGE[v as keyof typeof STAGE]?.tone ?? TONE[v] ?? 'muted';
  });

  protected readonly resolvedLabel = computed(() => {
    if (this.text()) return this.text();
    const v = this.value();
    if (!v) return '—';
    const stage = STAGE[v as keyof typeof STAGE];
    if (stage) return stage.label;
    return LABEL[v] ?? v.replaceAll('_', ' ').toLowerCase();
  });
}
