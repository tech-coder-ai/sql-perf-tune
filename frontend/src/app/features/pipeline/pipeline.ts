import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Api } from '../../core/api';
import { LookupStore } from '../../core/lookups';
import { BoardCard, PRIORITIES, REQUEST_SOURCES, WorkflowStatus } from '../../core/models';
import { loadPref, savePref } from '../../core/prefs';
import { REQUEST_SOURCE_LABEL, STAGE, STAGE_ORDER } from '../../core/stages';
import { minutesText } from '../../shared/dates';
import { StatusChip } from '../../shared/status-chip';

interface Column {
  status: WorkflowStatus;
  cards: BoardCard[];
  avgDays: number | null;
}

/** Pipeline board: every tracker item as a card in the column of its current stage (until adoption). */
@Component({
  selector: 'app-pipeline',
  imports: [
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatIconModule,
    MatTooltipModule,
    StatusChip,
  ],
  templateUrl: './pipeline.html',
  styleUrl: './pipeline.scss',
})
export class Pipeline {
  private readonly api = inject(Api);
  protected readonly lookups = inject(LookupStore);
  private readonly route = inject(ActivatedRoute);
  protected readonly stage = STAGE;
  protected readonly priorities = PRIORITIES;
  protected readonly sources = REQUEST_SOURCES;
  protected readonly sourceLabel = REQUEST_SOURCE_LABEL;
  protected readonly minutesText = minutesText;

  protected readonly cards = signal<BoardCard[]>([]);
  protected readonly showClosed = signal<boolean>(loadPref('board.closed', false));
  protected filter = { q: '', theme: '', priority: '', lead: '', requestSource: '' };

  protected readonly columns = computed<Column[]>(() => {
    const order: WorkflowStatus[] = this.showClosed()
      ? [...STAGE_ORDER, 'ON_HOLD', 'REJECTED']
      : (STAGE_ORDER.filter((s) => s !== 'ADOPTED') as WorkflowStatus[]).concat(['ON_HOLD']);
    return order.map((status) => {
      const cards = this.cards().filter((c) => c.status === status);
      const ages = cards.map((c) => c.daysInStage ?? 0);
      return {
        status,
        cards: status === 'ADOPTED' ? cards.slice(0, 30) : cards,
        avgDays: ages.length ? Math.round((ages.reduce((a, b) => a + b, 0) / ages.length) * 10) / 10 : null,
      };
    });
  });

  protected readonly total = computed(() => this.cards().filter((c) => c.status !== 'ADOPTED' && c.status !== 'REJECTED').length);

  constructor() {
    this.load();
    const frag = this.route.snapshot.fragment;
    if (frag) setTimeout(() => document.getElementById('col-' + frag)?.scrollIntoView({ inline: 'start', behavior: 'smooth' }), 400);
  }

  load(): void {
    this.api.board(this.filter).subscribe((c) => this.cards.set(c));
  }

  reset(): void {
    this.filter = { q: '', theme: '', priority: '', lead: '', requestSource: '' };
    this.load();
  }

  toggleClosed(v: boolean): void {
    this.showClosed.set(v);
    savePref('board.closed', v);
  }

  age(c: BoardCard): 'ok' | 'warn' | 'bad' {
    const d = c.daysInStage ?? 0;
    return d > 30 ? 'bad' : d > 14 ? 'warn' : 'ok';
  }
}
