/** Local yyyy-MM-dd (not UTC) for API date parameters. */
export function isoDate(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

export function addDays(iso: string, days: number): string {
  const [y, m, d] = iso.split('-').map(Number);
  return isoDate(new Date(y, m - 1, d + days));
}

export function today(): string {
  return isoDate(new Date());
}

export function pct(part: number, whole: number): string {
  return whole ? `${Math.round((part / whole) * 100)}%` : '0%';
}

export function signedPct(now: number, before: number): string | null {
  if (!before) return now ? 'new today' : null;
  const v = Math.round(((now - before) / before) * 100);
  return `${v > 0 ? '+' : ''}${v}% vs previous day`;
}

const nf = new Intl.NumberFormat();
export const num = (v: number | null | undefined) => (v === null || v === undefined ? '—' : nf.format(v));

/** Minutes as a readable duration: "42 min", "6.5 h", "3.2 days". */
export function minutesText(v: number | null | undefined): string {
  if (v === null || v === undefined) return '—';
  if (Math.abs(v) < 120) return `${Math.round(v * 10) / 10} min`;
  if (Math.abs(v) < 2880) return `${Math.round((v / 60) * 10) / 10} h`;
  return `${Math.round((v / 1440) * 10) / 10} days`;
}

export function secondsText(v: number | null | undefined): string {
  if (v === null || v === undefined) return '—';
  if (Math.abs(v) < 120) return `${Math.round(v)} s`;
  return minutesText(v / 60);
}
