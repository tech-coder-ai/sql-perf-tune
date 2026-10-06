import { describe, expect, it } from 'vitest';
import { TimestampPipe, formatMinutes } from './format';

describe('formatMinutes', () => {
  it('formats sub-minute, minute and hour ranges', () => {
    expect(formatMinutes(null)).toBe('—');
    expect(formatMinutes(0.5)).toBe('30s');
    expect(formatMinutes(3.456)).toBe('3.46 min');
    expect(formatMinutes(42.25)).toBe('42.3 min');
    expect(formatMinutes(135)).toBe('2h 15m');
    expect(formatMinutes(-7.5)).toBe('-7.50 min');
  });
});

describe('TimestampPipe', () => {
  it('renders ISO local date-times without the T and fraction', () => {
    expect(new TimestampPipe().transform('2026-09-01T10:00:00.123')).toBe('2026-09-01 10:00:00');
    expect(new TimestampPipe().transform(null)).toBe('—');
  });
});
