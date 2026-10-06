import { BreakpointObserver } from '@angular/cdk/layout';
import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { map } from 'rxjs';
import { ThemeService } from '../core/theme';

interface NavItem {
  path: string;
  label: string;
  icon: string;
  hint: string;
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
  ],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class Shell {
  protected readonly theme = inject(ThemeService);
  private readonly bp = inject(BreakpointObserver);

  protected readonly compact = toSignal(this.bp.observe('(max-width: 960px)').pipe(map((r) => r.matches)), {
    initialValue: false,
  });

  protected readonly themeIcon = computed(() =>
    ({ light: 'light_mode', dark: 'dark_mode', system: 'brightness_auto' })[this.theme.mode()],
  );

  protected readonly nav: NavItem[] = [
    { path: '/dashboard', label: 'Dashboard', icon: 'space_dashboard', hint: 'Overview' },
    { path: '/logs', label: 'Query Logs', icon: 'receipt_long', hint: 'Raw executed SQL' },
    { path: '/groups', label: 'Query Groups', icon: 'account_tree', hint: 'SQL grouped by fingerprint' },
    { path: '/tracker', label: 'Tuning Tracker', icon: 'fact_check', hint: 'Optimization work items' },
    { path: '/admin', label: 'Administration', icon: 'settings', hint: 'Sources, fields, prompts' },
  ];
}
