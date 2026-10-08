import { BreakpointObserver } from '@angular/cdk/layout';
import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { map } from 'rxjs';
import { loadPref, savePref } from '../core/prefs';
import { ThemeService } from '../core/theme';
import { RequestDialog } from '../shared/request-dialog';
import { GlobalSearch } from './global-search';

interface NavItem {
  path: string;
  label: string;
  icon: string;
  hint: string;
}

interface NavSection {
  title: string;
  items: NavItem[];
}

@Component({
  selector: 'app-shell',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatSidenavModule,
    MatToolbarModule,
    MatListModule,
    MatIconModule,
    MatButtonModule,
    MatTooltipModule,
    GlobalSearch,
  ],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class Shell {
  protected readonly theme = inject(ThemeService);
  private readonly bp = inject(BreakpointObserver);
  private readonly dialog = inject(MatDialog);

  protected readonly compact = toSignal(this.bp.observe('(max-width: 960px)').pipe(map((r) => r.matches)), {
    initialValue: false,
  });
  protected readonly mini = signal<boolean>(loadPref('nav.mini', false));

  protected readonly themeIcon = computed(() =>
    ({ light: 'light_mode', dark: 'dark_mode', system: 'brightness_auto' })[this.theme.mode()],
  );

  protected readonly sections: NavSection[] = [
    {
      title: 'Overview',
      items: [
        { path: '/home', label: 'Command center', icon: 'space_dashboard', hint: "Today's bad queries and backlog" },
        { path: '/insights', label: 'Insights', icon: 'insights', hint: 'Answers to the 15 programme questions' },
      ],
    },
    {
      title: 'Tuning',
      items: [
        { path: '/pipeline', label: 'Pipeline board', icon: 'view_kanban', hint: 'Every SQL by stage until adoption' },
        { path: '/tracker', label: 'Tuning tracker', icon: 'fact_check', hint: 'All tracking columns, export' },
      ],
    },
    {
      title: 'Data',
      items: [
        { path: '/groups', label: 'Query groups', icon: 'account_tree', hint: 'SQL patterns (same SQL, any filter)' },
        { path: '/logs', label: 'Query logs', icon: 'receipt_long', hint: 'Every bad query loaded; import here' },
      ],
    },
    {
      title: 'Settings',
      items: [{ path: '/admin', label: 'Administration', icon: 'settings', hint: 'Dropdowns, users, sources, prompts' }],
    },
  ];

  toggleMini(): void {
    this.mini.update((v) => !v);
    savePref('nav.mini', this.mini());
  }

  newRequest(): void {
    this.dialog.open(RequestDialog, { width: '760px', maxWidth: '95vw' });
  }
}
