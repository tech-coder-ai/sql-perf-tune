import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { Router } from '@angular/router';
import { Api } from '../core/api';
import { LookupStore } from '../core/lookups';
import { PRIORITIES } from '../core/models';

/** Q14: register a proactive (UAT) or user-requested tuning item from a pasted SQL. */
@Component({
  selector: 'app-request-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatIconModule],
  template: `
    <h2 mat-dialog-title>New tuning request</h2>
    <form #f="ngForm" (ngSubmit)="submit()">
      <mat-dialog-content>
        <p class="muted">
          For SQL found before it reaches production (UAT) or reported by a user. If the same SQL pattern is already
          known, the request joins that item.
        </p>
        <div class="form-grid">
          <mat-form-field>
            <mat-label>Request type</mat-label>
            <mat-select name="requestSource" [(ngModel)]="m.requestSource" required>
              <mat-option value="PROACTIVE_UAT">Proactive tuning (UAT)</mat-option>
              <mat-option value="USER_REQUEST">User tuning request</mat-option>
            </mat-select>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Requested by</mat-label>
            <input matInput name="requestedBy" [(ngModel)]="m.requestedBy" required />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Environment</mat-label>
            <mat-select name="environment" [(ngModel)]="m.environment">
              @for (o of lookups.options('ENVIRONMENT'); track o) {
                <mat-option [value]="o">{{ o }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Priority</mat-label>
            <mat-select name="priority" [(ngModel)]="m.priority">
              @for (p of priorities; track p) {
                <mat-option [value]="p">{{ p }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Theme</mat-label>
            <mat-select name="theme" [(ngModel)]="m.theme">
              <mat-option [value]="null">Not categorized yet</mat-option>
              @for (o of lookups.options('THEME'); track o) {
                <mat-option [value]="o">{{ o }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field class="span-all">
            <mat-label>SQL</mat-label>
            <textarea matInput name="sql" rows="8" class="mono" [(ngModel)]="m.sql" required></textarea>
          </mat-form-field>
          <mat-form-field class="span-all">
            <mat-label>Problem description</mat-label>
            <textarea matInput name="problem" rows="2" [(ngModel)]="m.problem"></textarea>
          </mat-form-field>
        </div>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancel</button>
        <button mat-flat-button type="submit" [disabled]="f.invalid || busy()"><mat-icon>add_task</mat-icon> Create request</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class RequestDialog {
  protected readonly lookups = inject(LookupStore);
  private readonly api = inject(Api);
  private readonly ref = inject(MatDialogRef<RequestDialog>);
  private readonly router = inject(Router);
  protected readonly priorities = PRIORITIES;
  protected readonly busy = signal(false);
  protected m = {
    requestSource: 'PROACTIVE_UAT',
    requestedBy: '',
    environment: 'UAT',
    priority: 'MEDIUM',
    theme: null as string | null,
    sql: '',
    problem: '',
  };

  submit(): void {
    this.busy.set(true);
    this.api.createRequest(this.m).subscribe({
      next: (t) => {
        this.ref.close(t);
        this.router.navigate(['/tracker', t.trackerId]);
      },
      error: () => this.busy.set(false),
    });
  }
}
