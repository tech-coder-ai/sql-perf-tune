import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { RouterLink } from '@angular/router';
import { Api } from '../../core/api';
import { NotificationRow } from '../../core/models';
import { TimestampPipe } from '../../shared/format';
import { StatusChip } from '../../shared/status-chip';

/** Administration → Notifications: every e-mail the workflow produced, with its delivery status and a preview. */
@Component({
  selector: 'app-notifications-panel',
  imports: [MatButtonModule, MatIconModule, RouterLink, StatusChip, TimestampPipe],
  template: `
    <div class="head">
      <p class="muted">
        E-mails are always recorded here. They are delivered only when <b>Send e-mails</b> is on (Workflow settings) and a
        mail transport is configured (<code>spt.email.transport</code>: SMTP or HTTP); with the default transport (LOG)
        they are recorded but not sent.
      </p>
      <span class="spacer"></span>
      <button mat-stroked-button (click)="load()"><mat-icon>refresh</mat-icon> Refresh</button>
    </div>
    <div class="layout">
      <table class="simple list">
        <tr><th>When</th><th>Kind</th><th>Item</th><th>To</th><th>Status</th></tr>
        @for (n of rows(); track n.id) {
          <tr class="clickable" [class.sel]="n.id === openId()" (click)="open(n)">
            <td class="nowrap">{{ n.createdAt | ts }}</td>
            <td><app-status [value]="n.kind" /></td>
            <td class="nowrap">@if (n.trackerId) {<a class="link" [routerLink]="['/tracker', n.trackerId]" (click)="$event.stopPropagation()">T-{{ n.trackerId }}</a>}</td>
            <td class="to">{{ n.recipients || '—' }}</td>
            <td><app-status [value]="n.status" />@if (n.errorMessage) {<div class="err">{{ n.errorMessage }}</div>}</td>
          </tr>
        } @empty {
          <tr><td colspan="5" class="muted">No e-mails yet. They are created when an item is ready for adoption, or needs the tuning team.</td></tr>
        }
      </table>
      @if (preview(); as p) {
        <div class="preview">
          <div class="subject"><b>{{ subject() }}</b></div>
          <iframe sandbox="" [srcdoc]="p" title="E-mail preview"></iframe>
        </div>
      }
    </div>
  `,
  styles: `
    :host { display: block; padding: 16px; }
    .head { display: flex; align-items: center; gap: 12px; }
    .spacer { flex: 1; }
    .layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 16px; align-items: start; }
    .list tr.sel td { background: var(--spt-accent-soft); }
    .to { max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .err { color: var(--spt-bad); font-size: 12px; }
    .preview { border: 1px solid var(--spt-border); border-radius: 12px; overflow: hidden; background: #fff; position: sticky; top: 8px; }
    .subject { padding: 10px 12px; border-bottom: 1px solid var(--spt-border); background: var(--spt-panel); }
    iframe { width: 100%; height: 640px; border: 0; background: #fff; }
    @media (max-width: 1100px) { .layout { grid-template-columns: 1fr; } }
  `,
})
export class NotificationsPanel {
  private readonly api = inject(Api);
  private readonly sanitizer = inject(DomSanitizer);
  protected readonly rows = signal<NotificationRow[]>([]);
  protected readonly preview = signal<SafeHtml | null>(null);
  protected readonly subject = signal('');
  protected readonly openId = signal<number | null>(null);

  constructor() {
    this.load();
  }

  load(): void {
    this.api.notifications().subscribe((p) => this.rows.set(p.content));
  }

  open(n: NotificationRow): void {
    this.openId.set(n.id);
    this.api.notification(n.id).subscribe((full) => {
      this.subject.set(full.subject);
      // our own generated e-mail (values HTML-escaped on the server), shown in a sandboxed frame without scripts
      this.preview.set(this.sanitizer.bypassSecurityTrustHtml(full.body));
    });
  }
}
