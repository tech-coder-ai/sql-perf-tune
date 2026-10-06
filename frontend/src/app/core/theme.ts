import { DOCUMENT } from '@angular/common';
import { Injectable, effect, inject, signal } from '@angular/core';

export type ThemeMode = 'light' | 'dark' | 'system';
const KEY = 'spt.theme';

/**
 * Light / dark / system theme. Angular Material's M3 tokens use CSS light-dark(), so switching is just a
 * matter of setting color-scheme on the root element.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly doc = inject(DOCUMENT);
  readonly mode = signal<ThemeMode>(this.read());

  constructor() {
    effect(() => {
      const mode = this.mode();
      const root = this.doc.documentElement;
      root.style.colorScheme = mode === 'system' ? 'light dark' : mode;
      root.dataset['theme'] = mode;
      try {
        localStorage.setItem(KEY, mode);
      } catch {
        /* storage unavailable: keep in memory only */
      }
    });
  }

  cycle(): void {
    const order: ThemeMode[] = ['light', 'dark', 'system'];
    this.mode.set(order[(order.indexOf(this.mode()) + 1) % order.length]);
  }

  private read(): ThemeMode {
    try {
      const v = localStorage.getItem(KEY);
      return v === 'light' || v === 'dark' || v === 'system' ? v : 'system';
    } catch {
      return 'system';
    }
  }
}
