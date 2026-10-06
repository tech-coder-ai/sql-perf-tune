import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'dashboard',
    title: 'Dashboard · SQL Tuning',
    loadComponent: () => import('./features/dashboard/dashboard').then((m) => m.Dashboard),
  },
  {
    path: 'logs',
    title: 'Query Logs · SQL Tuning',
    loadComponent: () => import('./features/logs/logs').then((m) => m.Logs),
  },
  {
    path: 'groups',
    title: 'Query Groups · SQL Tuning',
    loadComponent: () => import('./features/groups/groups').then((m) => m.Groups),
  },
  {
    path: 'groups/:id',
    title: 'Group · SQL Tuning',
    loadComponent: () => import('./features/groups/group-detail').then((m) => m.GroupDetail),
  },
  {
    path: 'tracker',
    title: 'Tuning Tracker · SQL Tuning',
    loadComponent: () => import('./features/tracker/tracker').then((m) => m.TrackerList),
  },
  {
    path: 'tracker/:id',
    title: 'Tracker Item · SQL Tuning',
    loadComponent: () => import('./features/tracker/tracker-detail').then((m) => m.TrackerDetail),
  },
  {
    path: 'admin',
    title: 'Administration · SQL Tuning',
    loadComponent: () => import('./features/admin/admin').then((m) => m.Admin),
  },
  { path: '**', redirectTo: 'dashboard' },
];
