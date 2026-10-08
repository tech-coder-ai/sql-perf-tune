import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { Api } from '../../core/api';
import { LookupStore } from '../../core/lookups';
import { Iteration } from '../../core/models';

export interface DecisionData {
  decision: 'ADOPTED' | 'REJECTED';
  groupId: number;
  iterations: Iteration[];
  iterationId: number | null;
}

/** Records the users' decision on a tuned iteration: adopted, or rejected with a reason (Q15). */
@Component({
  selector: 'app-decision-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatIconModule],
  template: `
    <h2 mat-dialog-title>{{ data.decision === 'ADOPTED' ? 'Mark as adopted' : 'Reject optimization' }}</h2>
    <form #f="ngForm" (ngSubmit)="save()">
      <mat-dialog-content>
        <p class="muted">
          @if (data.decision === 'ADOPTED') {
            The selected iteration becomes the adopted SQL and the item is closed.
          } @else {
            The iteration is marked rejected and the item goes back to tuning so a new iteration can be tried. The reason
            feeds the rejection report and prompt improvements.
          }
        </p>
        <div class="form-grid">
          <mat-form-field>
            <mat-label>Iteration</mat-label>
            <mat-select name="iterationId" [(ngModel)]="m.iterationId" [required]="data.iterations.length > 0">
              @for (i of data.iterations; track i.id) {
                <mat-option [value]="i.id" [disabled]="i.status === 'REJECTED' || i.status === 'FAILED'">
                  #{{ i.iterationNo }} · {{ i.status.toLowerCase() }}
                  @if (i.durationImprovementPct !== null) {
                    · {{ i.durationImprovementPct }}% faster
                  }
                </mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Decided by</mat-label>
            <mat-select name="sourceRole" [(ngModel)]="m.sourceRole" required>
              <mat-option value="BUSINESS_USER">Business user</mat-option>
              <mat-option value="CLIENT_DEV">Client development</mat-option>
              <mat-option value="SME">SME</mat-option>
              <mat-option value="CLOUDERA">Cloudera team</mat-option>
            </mat-select>
          </mat-form-field>
          @if (data.decision === 'REJECTED') {
            <mat-form-field class="span-all">
              <mat-label>Reason</mat-label>
              <mat-select name="rejectionReason" [(ngModel)]="m.rejectionReason" required>
                @for (r of lookups.options('REJECTION_REASON'); track r) {
                  <mat-option [value]="r">{{ r }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
          }
          <mat-form-field class="span-all">
            <mat-label>{{ data.decision === 'REJECTED' ? 'What was wrong?' : 'Comments' }}</mat-label>
            <textarea matInput name="comments" rows="3" [(ngModel)]="m.comments" [required]="data.decision === 'REJECTED'"></textarea>
          </mat-form-field>
        </div>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancel</button>
        <button mat-flat-button type="submit" [disabled]="f.invalid || busy()" [class.danger]="data.decision === 'REJECTED'">
          <mat-icon>{{ data.decision === 'ADOPTED' ? 'verified' : 'block' }}</mat-icon>
          {{ data.decision === 'ADOPTED' ? 'Mark adopted' : 'Reject' }}
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .danger { --mat-button-filled-container-color: var(--spt-bad); }
  `,
})
export class DecisionDialog {
  protected readonly data = inject<DecisionData>(MAT_DIALOG_DATA);
  protected readonly lookups = inject(LookupStore);
  private readonly api = inject(Api);
  private readonly ref = inject(MatDialogRef<DecisionDialog>);
  protected readonly busy = signal(false);
  protected m = {
    iterationId: this.data.iterationId,
    sourceRole: 'BUSINESS_USER',
    rejectionReason: null as string | null,
    comments: '',
  };

  save(): void {
    this.busy.set(true);
    this.api
      .addFeedback(this.data.groupId, {
        iterationId: this.m.iterationId,
        sourceRole: this.m.sourceRole,
        decision: this.data.decision,
        rejectionReason: this.data.decision === 'REJECTED' ? this.m.rejectionReason : null,
        comments: this.m.comments,
      })
      .subscribe({ next: () => this.ref.close(true), error: () => this.busy.set(false) });
  }
}
