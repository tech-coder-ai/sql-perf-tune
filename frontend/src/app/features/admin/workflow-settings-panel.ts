import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Api } from '../../core/api';
import { SettingValue } from '../../core/models';
import { WorkflowSettingsStore } from '../../core/workflow-settings';

/**
 * Administration → Workflow settings: bad-query threshold, automatic first run, routing threshold, e-mail switch and
 * groups. Changes apply at once (no restart).
 */
@Component({
  selector: 'app-workflow-settings-panel',
  imports: [FormsModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatIconModule, MatSlideToggleModule],
  template: `
    <div class="head">
      <p class="muted">
        How the tuning workflow behaves. Changes apply immediately. Clear a field to go back to its default.
      </p>
      <span class="spacer"></span>
      <button mat-stroked-button (click)="runNow()" [disabled]="running()" title="Track the current candidates and start their first round">
        <mat-icon>play_circle</mat-icon> Run the automatic round now
      </button>
    </div>
    @for (g of groups(); track g) {
      <section class="group">
        <h3>{{ g }}</h3>
        @for (v of byGroup(g); track v.definition.key) {
          <div class="setting">
            @if (v.definition.kind === 'BOOLEAN') {
              <mat-slide-toggle [checked]="edit[v.definition.key] === 'true'" (change)="edit[v.definition.key] = $event.checked ? 'true' : 'false'">
                {{ v.definition.label }}
              </mat-slide-toggle>
            } @else {
              <mat-form-field class="field" [class.wide]="v.definition.kind === 'EMAILS' || v.definition.kind === 'TEXT'">
                <mat-label>{{ v.definition.label }}</mat-label>
                <input matInput [type]="v.definition.kind === 'NUMBER' ? 'number' : 'text'" [(ngModel)]="edit[v.definition.key]"
                       [placeholder]="v.definition.kind === 'EMAILS' ? 'name@company.com, team@company.com' : v.definition.defaultValue" />
                <mat-hint>{{ v.isDefault ? 'default' : 'changed · default ' + (v.definition.defaultValue || '(none)') }}</mat-hint>
              </mat-form-field>
            }
            <p class="help">{{ v.definition.help }}</p>
          </div>
        }
      </section>
    }
    <div class="actions">
      <button mat-button (click)="discard()" [disabled]="!dirty()">Discard</button>
      <button mat-flat-button (click)="save()" [disabled]="!dirty()"><mat-icon>save</mat-icon> Save settings</button>
    </div>
  `,
  styles: `
    :host { display: block; padding: 16px; max-width: 980px; }
    .head { display: flex; align-items: center; gap: 12px; }
    .spacer { flex: 1; }
    .group { border: 1px solid var(--spt-border); border-radius: 12px; padding: 4px 16px 12px; margin-bottom: 14px; background: var(--spt-panel); }
    h3 { font: var(--mat-sys-title-small); margin: 12px 0 8px; }
    .setting { display: grid; grid-template-columns: minmax(280px, 380px) 1fr; gap: 4px 20px; align-items: start; padding: 6px 0; }
    .setting mat-slide-toggle { padding-top: 10px; }
    .field { width: 100%; }
    .help { margin: 8px 0 0; font-size: 13px; color: var(--spt-muted); line-height: 1.45; }
    .actions { display: flex; justify-content: flex-end; gap: 8px; position: sticky; bottom: 0; padding: 10px 0; background: var(--mat-sys-surface); }
    @media (max-width: 760px) { .setting { grid-template-columns: 1fr; } }
  `,
})
export class WorkflowSettingsPanel {
  private readonly api = inject(Api);
  private readonly snack = inject(MatSnackBar);
  private readonly store = inject(WorkflowSettingsStore);

  protected readonly values = signal<SettingValue[]>([]);
  protected readonly running = signal(false);
  protected edit: Record<string, string> = {};
  protected readonly groups = computed(() => [...new Set(this.values().map((v) => v.definition.group))]);

  constructor() {
    this.api.settings().subscribe((v) => this.apply(v));
  }

  protected byGroup(g: string): SettingValue[] {
    return this.values().filter((v) => v.definition.group === g);
  }

  protected dirty(): boolean {
    return this.values().some((v) => (this.edit[v.definition.key] ?? '') !== this.shown(v));
  }

  private shown(v: SettingValue): string {
    return v.value;
  }

  private apply(v: SettingValue[]): void {
    this.values.set(v);
    this.store.set(v);
    this.edit = Object.fromEntries(v.map((x) => [x.definition.key, this.shown(x)]));
  }

  protected discard(): void {
    this.apply(this.values());
  }

  protected save(): void {
    const changed = Object.fromEntries(
      this.values()
        .filter((v) => (this.edit[v.definition.key] ?? '') !== this.shown(v))
        .map((v) => [v.definition.key, String(this.edit[v.definition.key] ?? '')]),
    );
    this.api.saveSettings(changed).subscribe((v) => {
      this.apply(v);
      this.snack.open('Workflow settings saved', undefined, { duration: 2500 });
    });
  }

  protected runNow(): void {
    this.running.set(true);
    this.api.runAutoRound().subscribe({
      next: (r) => {
        this.running.set(false);
        this.snack.open(
          r.tracked ? `${r.tracked} candidates put on the tracker; their first round runs in the background` : 'No new candidates to track',
          undefined,
          { duration: 4000 },
        );
      },
      error: () => this.running.set(false),
    });
  }
}
