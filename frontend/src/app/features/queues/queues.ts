import { Component, computed, inject, signal } from '@angular/core';
import { DecimalPipe, NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { Api } from '../../core/api';
import { QueueItem, ThemeFix, WorkQueues } from '../../core/models';
import { PERSONAS, Persona, PersonaStore } from '../../core/persona';
import { minutesText } from '../../shared/dates';
import { TimestampPipe } from '../../shared/format';
import { LoadingArea } from '../../shared/loading';
import { StatusChip } from '../../shared/status-chip';
import { DecisionDialog, DecisionData } from '../tracker/decision-dialog';

/** One queue on the screen. */
interface QueueView {
  key: keyof WorkQueues;
  title: string;
  hint: string;
  empty: string;
}

const QUEUES: Record<Persona, QueueView[]> = {
  cdt: [
    { key: 'readyForAdoption', title: 'Promote for adoption', hint: 'Tuned and tested SQL waiting for the users, largest saving first.', empty: 'Nothing waiting for adoption.' },
    { key: 'followUp', title: 'Follow up', hint: 'Users were told, but have not tested or adopted it yet.', empty: 'Nobody to chase.' },
    { key: 'reappeared', title: 'Came back after adoption', hint: 'Adopted, yet the old bad SQL runs again (another user or a FID still uses it).', empty: 'No adopted SQL came back.' },
  ],
  user: [
    { key: 'readyForAdoption', title: 'Tuned SQL to test', hint: 'Test the tuned SQL in your process, then say whether it works and whether you use it.', empty: 'No tuned SQL waiting for you.' },
  ],
  tuner: [
    { key: 'needsReview', title: 'Below the threshold', hint: 'Tested, but not faster enough to send to the users.', empty: 'Nothing below the threshold.' },
    { key: 'failed', title: 'Failed', hint: 'Different results, failed tests, or the automatic round stopped.', empty: 'No failures.' },
    { key: 'rejected', title: 'Rejected by users', hint: 'Users rejected the tuned SQL; see their reason on the item.', empty: 'No rejections.' },
    { key: 'awaitingFix', title: 'Awaiting a theme fix', hint: 'Root cause is architectural; retried when the theme fix is released.', empty: 'Nothing awaiting a fix.' },
    { key: 'exceptions', title: 'Tuning exceptions', hint: 'Nothing more can be done; not tuned again.', empty: 'No exceptions.' },
  ],
};

/** Persona work queues: what each team has to act on in the tuning workflow. */
@Component({
  selector: 'app-queues',
  imports: [FormsModule, RouterLink, DecimalPipe, NgTemplateOutlet, MatTabsModule, MatButtonModule, MatIconModule, MatTooltipModule, StatusChip, TimestampPipe, LoadingArea],
  templateUrl: './queues.html',
  styleUrl: './queues.scss',
})
export class Queues {
  private readonly api = inject(Api);
  private readonly snack = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);
  protected readonly personaStore = inject(PersonaStore);
  protected readonly personas = PERSONAS;
  protected readonly minutesText = minutesText;

  protected readonly data = signal<WorkQueues | null>(null);
  protected readonly fixes = signal<ThemeFix[]>([]);
  protected readonly loading = signal(false);
  protected readonly tabIndex = computed(() => PERSONAS.findIndex((p) => p.key === this.personaStore.persona()));
  protected fixNotes: Record<string, string> = {};

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    forkJoin({ q: this.api.queues(), f: this.api.themeFixes() }).subscribe({
      next: (r) => {
        this.data.set(r.q);
        this.fixes.set(r.f);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  protected queuesOf(p: Persona): QueueView[] {
    return QUEUES[p];
  }

  protected items(key: keyof WorkQueues): QueueItem[] {
    const d = this.data();
    return d ? (d[key] as QueueItem[]) : [];
  }

  protected count(p: Persona): number {
    return QUEUES[p].reduce((n, q) => n + this.items(q.key).length, 0);
  }

  protected selectPersona(i: number): void {
    this.personaStore.set(PERSONAS[i].key);
  }

  /** Awaiting-fix items by theme (one "fix released" action per theme). */
  protected readonly byTheme = computed(() => {
    const m = new Map<string, QueueItem[]>();
    for (const i of this.data()?.awaitingFix ?? []) {
      const k = i.theme ?? 'No theme';
      m.set(k, [...(m.get(k) ?? []), i]);
    }
    return [...m.entries()].map(([theme, items]) => ({ theme, items }));
  });

  // ---- business user: attest (steps 7-9)

  attestWorks(i: QueueItem): void {
    this.api
      .addFeedback(i.groupId, { sourceRole: 'BUSINESS_USER', decision: 'COMMENT', comments: 'Tested: the tuned SQL works for us.' })
      .subscribe(() => this.snack.open(`T-${i.trackerId}: test result recorded`, undefined, { duration: 2500 }));
  }

  adopt(i: QueueItem): void {
    this.api
      .addFeedback(i.groupId, { sourceRole: 'BUSINESS_USER', decision: 'ADOPTED', comments: 'Adopted: we use the tuned SQL now.' })
      .subscribe(() => {
        this.snack.open(`T-${i.trackerId} adopted. Thank you!`, undefined, { duration: 3000 });
        this.load();
      });
  }

  reject(i: QueueItem): void {
    this.api.iterations(i.trackerId).subscribe((its) => {
      const data: DecisionData = { decision: 'REJECTED', groupId: i.groupId, iterations: its, iterationId: its.find((x) => x.selected)?.id ?? null };
      this.dialog
        .open(DecisionDialog, { data, width: '620px', maxWidth: '95vw' })
        .afterClosed()
        .subscribe((ok) => {
          if (ok) {
            this.snack.open(`T-${i.trackerId}: rejection sent to the tuning team`, undefined, { duration: 3000 });
            this.load();
          }
        });
    });
  }

  // ---- tuning team: theme fix released (step 15)

  releaseFix(theme: string, n: number): void {
    if (!confirm(`Release the fix for "${theme}"? ${n} SQL will be retried automatically.`)) return;
    this.api.releaseThemeFix(theme, this.fixNotes[theme] ?? '').subscribe((f) => {
      this.snack.open(`Fix for "${f.theme}" released: ${f.itemsRetried} SQL retried`, undefined, { duration: 4000 });
      this.load();
    });
  }
}
