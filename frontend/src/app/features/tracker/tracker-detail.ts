import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule, NgForm } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { Api } from '../../core/api';
import { AuditEvent, CustomField, PRIORITIES, Tracker, WORKFLOW_STATUSES } from '../../core/models';
import { MinutesPipe, TimestampPipe } from '../../shared/format';
import { SqlBlock } from '../../shared/sql-block';
import { StatusChip } from '../../shared/status-chip';
import { GroupMembers } from '../groups/group-members';

/** Edit form for one tracker item, with drill-down to the group and its log rows and full change history. */
@Component({
  selector: 'app-tracker-detail',
  imports: [
    FormsModule,
    RouterLink,
    MatTabsModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatCheckboxModule,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule,
    SqlBlock,
    StatusChip,
    GroupMembers,
    MinutesPipe,
    TimestampPipe,
  ],
  templateUrl: './tracker-detail.html',
  styleUrl: './tracker-detail.scss',
})
export class TrackerDetail implements OnInit {
  readonly id = input.required<string>();
  private readonly api = inject(Api);
  private readonly snack = inject(MatSnackBar);

  readonly statuses = WORKFLOW_STATUSES;
  readonly priorities = PRIORITIES;
  readonly saved = signal<Tracker | null>(null);
  readonly history = signal<AuditEvent[]>([]);
  readonly customFields = signal<CustomField[]>([]);
  readonly saving = signal(false);
  /** working copy bound to the form (fields mutated in place by ngModel) */
  readonly model = signal<Tracker | null>(null);

  ngOnInit(): void {
    this.load();
    this.api.customFields('TRACKER').subscribe((f) => this.customFields.set(f.filter((x) => x.active)));
  }

  load(): void {
    const id = Number(this.id());
    this.api.tracker(id).subscribe((t) => {
      this.saved.set(t);
      this.model.set(this.copy(t));
    });
    this.api.trackerHistory(id).subscribe((h) => this.history.set(h));
  }

  options(f: CustomField): string[] {
    return (f.optionsCsv ?? '').split(',').map((s) => s.trim()).filter(Boolean);
  }

  save(form: NgForm): void {
    const m = this.model();
    if (!m || form.invalid) return;
    this.saving.set(true);
    this.api.updateTracker(m.trackerId, m).subscribe({
      next: (t) => {
        this.saving.set(false);
        this.saved.set(t);
        this.model.set(this.copy(t));
        form.form.markAsPristine();
        this.api.trackerHistory(t.trackerId).subscribe((h) => this.history.set(h));
        this.snack.open('Saved', undefined, { duration: 2000 });
      },
      error: (e: HttpErrorResponse) => {
        this.saving.set(false);
        if (e.status === 409) {
          this.snack.open('Someone else changed this item. Reloaded the latest version.', 'OK', { duration: 6000 });
          this.load();
        }
      },
    });
  }

  discard(form: NgForm): void {
    const t = this.saved();
    if (t) this.model.set(this.copy(t));
    form.form.markAsPristine();
  }

  private copy(t: Tracker): Tracker {
    return structuredClone({ ...t, customFields: { ...(t.customFields ?? {}) } });
  }

  fieldLabel(name: string | null): string {
    if (!name) return '';
    if (name.startsWith('custom.')) {
      const key = name.substring(7);
      return this.customFields().find((f) => f.fieldKey === key)?.label ?? key;
    }
    return name.replace(/([A-Z])/g, ' $1').toLowerCase();
  }
}
