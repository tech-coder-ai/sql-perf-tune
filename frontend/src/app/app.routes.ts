import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'home' },
  { path: 'dashboard', redirectTo: 'home' },
  {
    path: 'home',
    title: 'Command center · SQL Tuning',
    loadComponent: () => import('./features/home/home').then((m) => m.Home),
  },
  {
    path: 'insights',
    title: 'Insights · SQL Tuning',
    loadComponent: () => import('./features/insights/insights').then((m) => m.Insights),
  },
  {
    path: 'pipeline',
    title: 'Pipeline board · SQL Tuning',
    loadComponent: () => import('./features/pipeline/pipeline').then((m) => m.Pipeline),
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
  { path: '**', redirectTo: 'home' },
];
