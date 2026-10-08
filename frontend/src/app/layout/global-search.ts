import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatAutocompleteModule, MatAutocompleteSelectedEvent } from '@angular/material/autocomplete';
import { MatIconModule } from '@angular/material/icon';
import { Router } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, filter, switchMap } from 'rxjs';
import { Api } from '../core/api';
import { SearchHit } from '../core/models';
import { StatusChip } from '../shared/status-chip';

/** "Where is my SQL?" - T-12, #5, a seq_id or SQL text; jumps to the item's journey. */
@Component({
  selector: 'app-global-search',
  imports: [FormsModule, MatAutocompleteModule, MatIconModule, StatusChip],
  template: `
    <div class="search">
      <mat-icon>search</mat-icon>
      <input
        [(ngModel)]="text"
        (ngModelChange)="query$.next($event)"
        [matAutocomplete]="auto"
        placeholder="Find a SQL: T-12, #5, seq id or SQL text"
        aria-label="Find a SQL"
      />
    </div>
    <mat-autocomplete #auto="matAutocomplete" (optionSelected)="open($event)" class="search-panel">
      @for (h of hits(); track h.type + h.id) {
        <mat-option [value]="h">
          <div class="hit">
            <mat-icon>{{ h.type === 'TRACKER' ? 'fact_check' : h.type === 'GROUP' ? 'account_tree' : 'receipt_long' }}</mat-icon>
            <div class="txt">
              <div class="t">{{ h.title }} @if (h.status) {<app-status [value]="h.status" />}</div>
              <div class="s">{{ h.subtitle }}</div>
            </div>
          </div>
        </mat-option>
      }
    </mat-autocomplete>
  `,
  styles: `
    :host { display: block; width: min(520px, 42vw); }
    .search {
      display: flex; align-items: center; gap: 8px; height: 36px; padding: 0 12px; border-radius: 8px;
      background: rgba(255,255,255,.16); border: 1px solid rgba(255,255,255,.22); color: #fff;
    }
    .search:focus-within { background: rgba(255,255,255,.24); border-color: rgba(255,255,255,.5); }
    .search mat-icon { font-size: 20px; width: 20px; height: 20px; opacity: .85; }
    input { flex: 1; min-width: 0; background: transparent; border: 0; outline: 0; color: #fff; font: inherit; font-size: 14px; }
    input::placeholder { color: rgba(255,255,255,.75); }
    .hit { display: flex; gap: 10px; align-items: center; padding: 2px 0; }
    .hit mat-icon { color: var(--spt-accent); }
    .txt { min-width: 0; }
    .t { font-weight: 600; font-size: 13px; display: flex; gap: 6px; align-items: center; }
    .s { font-family: var(--spt-mono); font-size: 11px; color: var(--spt-muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 440px; }
  `,
})
export class GlobalSearch {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  protected text = '';
  protected readonly hits = signal<SearchHit[]>([]);
  protected readonly query$ = new Subject<string | SearchHit>();

  constructor() {
    this.query$
      .pipe(
        filter((q): q is string => typeof q === 'string'),
        debounceTime(250),
        distinctUntilChanged(),
        switchMap((q) => this.api.search(q)),
      )
      .subscribe((h) => this.hits.set(h));
  }

  open(e: MatAutocompleteSelectedEvent): void {
    const h = e.option.value as SearchHit;
    this.text = '';
    this.hits.set([]);
    if (h.trackerId) this.router.navigate(['/tracker', h.trackerId]);
    else if (h.groupId) this.router.navigate(['/groups', h.groupId]);
  }
}
