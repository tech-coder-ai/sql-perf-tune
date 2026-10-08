import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { Api } from './api';
import { Lookup } from './models';

/** Dropdown values (Administration -> Dropdown values), loaded once and shared by every screen. */
@Injectable({ providedIn: 'root' })
export class LookupStore {
  private readonly api = inject(Api);
  private readonly all = signal<Record<string, Lookup[]>>({});
  readonly loaded = computed(() => Object.keys(this.all()).length > 0);

  constructor() {
    this.reload().subscribe();
  }

  reload(): Observable<Record<string, Lookup[]>> {
    return this.api.lookups().pipe(tap((l) => this.all.set(l)));
  }

  categories(): string[] {
    return Object.keys(this.all()).sort();
  }

  /** Values of a category for new input (active only, plus the current value so old records stay editable). */
  options(category: string, current?: string | null): string[] {
    const values = (this.all()[category] ?? []).filter((l) => l.active).map((l) => l.value);
    if (current && !values.includes(current)) values.push(current);
    return values;
  }

  entries(category: string): Lookup[] {
    return this.all()[category] ?? [];
  }

  tone(category: string, value: string | null | undefined): string {
    return (this.all()[category] ?? []).find((l) => l.value === value)?.tone ?? 'muted';
  }
}
