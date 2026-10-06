import { Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { ICellRendererAngularComp } from 'ag-grid-angular';
import { ICellRendererParams } from 'ag-grid-community';
import { QueryGroup } from '../../core/models';
import { SqlBlock } from '../../shared/sql-block';
import { GroupMembers } from './group-members';

/** Full-width detail row shown under an expanded group: grouping key + member log rows. */
@Component({
  selector: 'app-group-detail-row',
  imports: [RouterLink, MatButtonModule, MatIconModule, SqlBlock, GroupMembers],
  template: `
    @if (group) {
      <div class="detail">
        <div class="head">
          <span class="section-title">Normalized SQL (grouping key)</span>
          <span class="spacer"></span>
          <a mat-stroked-button [routerLink]="['/groups', group.groupId]"><mat-icon>open_in_new</mat-icon> Open group</a>
          <a mat-button routerLink="/logs" [queryParams]="{ groupId: group.groupId }"><mat-icon>receipt_long</mat-icon> In log view</a>
        </div>
        <app-sql-block [text]="group.normalizedQuery" maxHeight="90px" />
        <div class="section-title">Log rows in this group ({{ group.groupSize }})</div>
        <app-group-members [groupId]="group.groupId" height="330px" />
      </div>
    }
  `,
  styles: `
    :host { display: block; height: 100%; }
    .detail {
      box-sizing: border-box;
      height: 100%;
      padding: 4px 16px 12px 56px;
      background: var(--mat-sys-surface-container-low);
      overflow: auto;
      white-space: normal;
    }
    .head { display: flex; align-items: center; gap: 8px; }
    .spacer { flex: 1; }
    .section-title { margin: 10px 0 8px; display: block; }
  `,
})
export class GroupDetailRow implements ICellRendererAngularComp {
  group: QueryGroup | null = null;

  agInit(p: ICellRendererParams): void {
    this.group = p.data?.group ?? null;
  }

  refresh(): boolean {
    return false;
  }
}
