import { describe, expect, it } from 'vitest';
import { logsQuery } from './drill';

describe('logsQuery', () => {
  it('turns a day into a whole-day time range', () => {
    expect(logsQuery({ day: '2026-10-08' })).toEqual({
      from: '2026-10-08T00:00',
      to: '2026-10-08T23:59',
    });
  });

  it('turns an hour of a day into that hour', () => {
    expect(logsQuery({ day: '2026-10-08', hour: 7 })).toEqual({
      from: '2026-10-08T07:00',
      to: '2026-10-08T07:59',
    });
  });

  it('keeps a period, exact user, user group, pattern and group', () => {
    expect(
      logsQuery({
        from: '2026-09-14',
        to: '2026-10-13',
        user: 'akumar',
        userGroup: 'Trading',
        groupId: 5,
        pattern: 'RECURRING',
      }),
    ).toEqual({
      from: '2026-09-14T00:00',
      to: '2026-10-13T23:59',
      user: 'akumar',
      userGroup: 'Trading',
      groupId: 5,
      pattern: 'RECURRING',
    });
  });
});
