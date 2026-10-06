import { Clipboard } from '@angular/cdk/clipboard';
import { Component, inject, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

/** Read-only SQL / plan / profile viewer with a copy button. */
@Component({
  selector: 'app-sql-block',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule],
  template: `
    <div class="sql-block" [style.max-height]="maxHeight()">
      @if (label()) {
        <div class="head">
          <span>{{ label() }}</span>
          <button mat-icon-button (click)="copy()" matTooltip="Copy" aria-label="Copy to clipboard" [disabled]="!text()">
            <mat-icon>content_copy</mat-icon>
          </button>
        </div>
      }
      <pre>{{ text() || placeholder() }}</pre>
    </div>
  `,
  styles: `
    .sql-block {
      border: 1px solid var(--spt-border);
      border-radius: 10px;
      background: var(--spt-code-bg);
      color: var(--spt-code-fg);
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }
    .head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 0 4px 0 12px;
      font: var(--mat-sys-label-large);
      border-bottom: 1px solid var(--spt-border);
      color: var(--spt-muted);
    }
    pre {
      margin: 0;
      padding: 12px;
      overflow: auto;
      font-family: var(--spt-mono);
      font-size: 12.5px;
      line-height: 1.5;
      white-space: pre-wrap;
      word-break: break-word;
    }
  `,
})
export class SqlBlock {
  readonly text = input<string | null | undefined>('');
  readonly label = input<string>('');
  readonly maxHeight = input<string>('360px');
  readonly placeholder = input<string>('—');

  private readonly clipboard = inject(Clipboard);
  private readonly snack = inject(MatSnackBar);

  copy(): void {
    if (this.clipboard.copy(this.text() ?? '')) {
      this.snack.open('Copied to clipboard', undefined, { duration: 1500 });
    }
  }
}
