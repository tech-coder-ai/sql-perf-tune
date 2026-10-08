import { describe, expect, it } from 'vitest';
import { diffLines, diffWords } from './diff';

const ORIGINAL = `SELECT *
FROM trade.trade_all t
JOIN ref.book b ON t.book_id = b.id
WHERE t.as_of = '2026-10-01'`;

const OPTIMIZED = `SELECT t.trade_id, t.notional
FROM trade.trade_all t
JOIN /* +BROADCAST */ ref.book b ON t.book_id = b.id
WHERE t.as_of = '2026-10-01'
  AND t.dt = '2026-10-01'`;

describe('diffLines', () => {
  it('marks changed, added and unchanged lines', () => {
    const d = diffLines(ORIGINAL, OPTIMIZED);
    expect(d.rows.map((r) => r.kind)).toEqual(['changed', 'same', 'changed', 'same', 'added']);
    expect(d.changed).toBe(2);
    expect(d.added).toBe(1);
    expect(d.removed).toBe(0);
    expect(d.identical).toBe(false);
    // line numbers on each side
    expect(d.rows[4].right?.no).toBe(5);
    expect(d.rows[4].left).toBeUndefined();
  });

  it('highlights only the changed words of a changed line', () => {
    const d = diffLines(ORIGINAL, OPTIMIZED);
    const join = d.rows[2];
    expect(join.left?.parts).toEqual([{ kind: 'same', text: 'JOIN ref.book b ON t.book_id = b.id' }]);
    expect(join.right?.parts.filter((p) => p.kind === 'add').map((p) => p.text.trim())).toEqual(['/* +BROADCAST */']);
  });

  it('reports removed lines', () => {
    const d = diffLines('a\nb\nc', 'a\nc');
    expect(d.rows.map((r) => r.kind)).toEqual(['same', 'removed', 'same']);
    expect(d.removed).toBe(1);
  });

  it('can ignore case and spacing', () => {
    expect(diffLines('select  a\nFROM t', 'SELECT a\nfrom t').identical).toBe(false);
    expect(diffLines('select  a\nFROM t', 'SELECT a\nfrom t', { ignoreCaseAndSpace: true }).identical).toBe(true);
  });

  it('folds long unchanged runs but keeps context around changes', () => {
    const left = Array.from({ length: 20 }, (_, i) => `line ${i}`).join('\n');
    const right = left.replace('line 10', 'line ten');
    const d = diffLines(left, right, { context: 2 });
    expect(d.rows.map((r) => r.kind)).toEqual(['skip', 'same', 'same', 'changed', 'same', 'same', 'skip']);
    expect(d.rows[0].skipped).toBe(8);
    expect(d.rows[6].skipped).toBe(7);
  });

  it('handles empty sides', () => {
    expect(diffLines('', 'select 1').added).toBe(1);
    expect(diffLines('select 1', '').removed).toBe(1);
    expect(diffLines('', '').identical).toBe(true);
  });
});

describe('diffWords', () => {
  it('keeps quoted strings as one token', () => {
    const w = diffWords("where x = 'a b'", "where x = 'a c'");
    expect(w.left.find((p) => p.kind === 'del')?.text).toBe("'a b'");
    expect(w.right.find((p) => p.kind === 'add')?.text).toBe("'a c'");
  });
});
