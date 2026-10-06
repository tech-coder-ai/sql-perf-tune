import { Pipe, PipeTransform } from '@angular/core';

/** 0.5 -> "30s", 12.25 -> "12.3 min", 135 -> "2h 15m". */
export function formatMinutes(v: number | null | undefined): string {
  if (v === null || v === undefined) return '—';
  if (v < 0) return '-' + formatMinutes(-v);
  if (v < 1) return `${Math.round(v * 60)}s`;
  if (v < 60) return `${v.toFixed(v < 10 ? 2 : 1)} min`;
  const h = Math.floor(v / 60);
  const m = Math.round(v % 60);
  return `${h}h ${m}m`;
}

@Pipe({ name: 'minutes' })
export class MinutesPipe implements PipeTransform {
  transform(v: number | null | undefined): string {
    return formatMinutes(v);
  }
}

/** "2026-09-01T10:00:00" -> "2026-09-01 10:00:00" */
@Pipe({ name: 'ts' })
export class TimestampPipe implements PipeTransform {
  transform(v: string | null | undefined): string {
    return v ? v.replace('T', ' ').replace(/\.\d+$/, '') : '—';
  }
}
