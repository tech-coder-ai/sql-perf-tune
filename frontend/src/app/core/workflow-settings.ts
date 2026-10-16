import { Injectable, computed, inject, signal } from '@angular/core';
import { Api } from './api';
import { SettingValue } from './models';

/**
 * The admin workflow settings the screens need (bad-query threshold, adoption threshold ...), loaded once and
 * refreshed after Administration saves them.
 */
@Injectable({ providedIn: 'root' })
export class WorkflowSettingsStore {
  private readonly api = inject(Api);
  readonly values = signal<SettingValue[]>([]);
  private loaded = false;

  /** a query longer than this (minutes) is a bad query */
  readonly badMinMinutes = computed(() => this.number('bad.min-minutes', 5));
  readonly adoptionThresholdPct = computed(() => this.number('adoption.threshold-pct', 15));

  load(force = false): void {
    if (this.loaded && !force) return;
    this.loaded = true;
    this.api.settings().subscribe((v) => this.values.set(v));
  }

  set(values: SettingValue[]): void {
    this.values.set(values);
  }

  private number(key: string, fallback: number): number {
    const v = this.values().find((x) => x.definition.key === key)?.value;
    return v === undefined || v === '' ? fallback : Number(v);
  }
}
