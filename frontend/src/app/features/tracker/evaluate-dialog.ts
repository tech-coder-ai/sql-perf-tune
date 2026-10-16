import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { LookupStore } from '../../core/lookups';

export interface EvaluateData {
  kind: 'exception' | 'fix';
  theme: string | null;
  rootCause: string | null;
}

export interface EvaluateResult {
  reason: string;
  theme: string;
  rootCause: string;
}

/** Step 13 "Evaluate error": RCA exception, or awaiting an architectural fix of a theme (steps 14-15). */
@Component({
  selector: 'app-evaluate-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule],
  template: `
    @if (data.kind === 'exception') {
      <h2 mat-dialog-title>Mark as tuning exception</h2>
      <mat-dialog-content>
        <p class="muted">
          Nothing more can be done on any front to improve this SQL. It is no longer sent to the optimization agent or the
          tuning team, and it is counted as an exception. Your name is recorded so later questions come to you.
        </p>
        <mat-form-field class="full">
          <mat-label>Reason (required)</mat-label>
          <textarea matInput rows="4" [(ngModel)]="reason" required></textarea>
        </mat-form-field>
      </mat-dialog-content>
    } @else {
      <h2 mat-dialog-title>Waits for an architectural fix</h2>
      <mat-dialog-content>
        <p class="muted">
          The root cause is in the design (e.g. a table or view), not in this SQL. The item waits for the theme's fix; when
          the fix is released (Work queues → Tuning team) it is retried automatically.
        </p>
        <mat-form-field class="full">
          <mat-label>Theme</mat-label>
          <mat-select [(ngModel)]="theme" required>
            @for (o of lookups.options('THEME', theme); track o) {
              <mat-option [value]="o">{{ o }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <mat-form-field class="full">
          <mat-label>Root cause</mat-label>
          <textarea matInput rows="4" [(ngModel)]="rootCause"></textarea>
        </mat-form-field>
      </mat-dialog-content>
    }
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancel</button>
      <button mat-flat-button [disabled]="data.kind === 'exception' ? !reason.trim() : !theme" (click)="ok()">
        {{ data.kind === 'exception' ? 'Mark as exception' : 'Wait for the fix' }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `.full { width: 100%; }`,
})
export class EvaluateDialog {
  protected readonly data = inject<EvaluateData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<EvaluateDialog, EvaluateResult>);
  protected readonly lookups = inject(LookupStore);
  protected reason = '';
  protected theme = this.data.theme ?? '';
  protected rootCause = this.data.rootCause ?? '';

  ok(): void {
    this.ref.close({ reason: this.reason.trim(), theme: this.theme, rootCause: this.rootCause.trim() });
  }
}
