import { Params } from '@angular/router';

/** What a dashboard number stands for, as a filter of the Query logs screen. */
export interface LogDrill {
  /** one calendar day 'yyyy-MM-dd' */
  day?: string;
  /** with day: one hour of it (0-23) */
  hour?: number;
  /** a period of whole days 'yyyy-MM-dd' (inclusive) */
  from?: string;
  to?: string;
  /** exact user id */
  user?: string;
  /** user group from Administration (Unassigned = none) */
  userGroup?: string;
  groupId?: number;
  /** RECURRING: pattern seen before the day; NEW: the rest */
  pattern?: 'RECURRING' | 'NEW';
}

const pad = (n: number) => String(n).padStart(2, '0');

/**
 * Query parameters of the /logs route for a dashboard number. Times use the datetime-local form of the filter
 * fields ('yyyy-MM-ddTHH:mm'); the logs screen and API count rows the way the dashboards do.
 */
export function logsQuery(d: LogDrill): Params {
  const q: Params = {};
  if (d.day && d.hour !== undefined) {
    q['from'] = `${d.day}T${pad(d.hour)}:00`;
    q['to'] = `${d.day}T${pad(d.hour)}:59`;
  } else if (d.day) {
    q['from'] = `${d.day}T00:00`;
    q['to'] = `${d.day}T23:59`;
  } else {
    if (d.from) q['from'] = `${d.from}T00:00`;
    if (d.to) q['to'] = `${d.to}T23:59`;
  }
  if (d.user) q['user'] = d.user;
  if (d.userGroup) q['userGroup'] = d.userGroup;
  if (d.groupId) q['groupId'] = d.groupId;
  if (d.pattern) q['pattern'] = d.pattern;
  return q;
}

/** First and last day of the dashboards' "active" window (the bars count patterns with bad queries in it). */
export function activeWindow(asOf: string, activeDays: number): { activeFrom: string; activeTo: string } {
  const d = new Date(asOf + 'T00:00:00Z');
  d.setUTCDate(d.getUTCDate() - (activeDays - 1));
  return { activeFrom: d.toISOString().substring(0, 10), activeTo: asOf };
}

/** Query groups: untracked patterns, optionally only those with bad queries in a window. */
export function untrackedQuery(window?: { activeFrom: string; activeTo: string }): Params {
  return { tracked: 'false', ...(window ?? {}) };
}

/** Tuning tracker: items in these stages, optionally only those whose pattern had bad queries in a window. */
export function stageQuery(statuses: string[], window?: { activeFrom: string; activeTo: string }): Params {
  return { status: statuses, ...(window ?? {}) };
}
