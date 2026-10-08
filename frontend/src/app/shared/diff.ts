/**
 * Line and word diff for comparing an original SQL with its optimized version (no dependencies).
 * Lines are matched with a longest-common-subsequence diff; a removed line followed by an added line is shown
 * as one "changed" row with the changed words highlighted.
 */

export type PartKind = 'same' | 'add' | 'del';

/** A piece of a line; `kind` marks words that differ inside a changed line. */
export interface Part {
  kind: PartKind;
  text: string;
}

export interface Side {
  /** 1-based line number on that side */
  no: number;
  parts: Part[];
}

export type RowKind = 'same' | 'changed' | 'added' | 'removed' | 'skip';

export interface DiffRow {
  kind: RowKind;
  left?: Side;
  right?: Side;
  /** for 'skip' rows: how many unchanged lines are folded */
  skipped?: number;
}

export interface DiffResult {
  rows: DiffRow[];
  added: number;
  removed: number;
  changed: number;
  identical: boolean;
}

export interface DiffOptions {
  /** compare ignoring case and runs of whitespace (the display keeps the original text) */
  ignoreCaseAndSpace?: boolean;
  /** unchanged lines kept around each change; longer unchanged runs are folded into a 'skip' row */
  context?: number;
}

type Op<T> = { op: 'eq'; a: T; b: T } | { op: 'del'; a: T } | { op: 'add'; b: T };

/** Above this many LCS cells the middle part is treated as replaced (keeps very large inputs responsive). */
const MAX_CELLS = 4_000_000;

/** LCS-based diff of two sequences (common prefix / suffix trimmed first). */
export function diffSequence<T>(a: T[], b: T[], key: (x: T) => string = (x) => String(x)): Op<T>[] {
  const ka = a.map(key);
  const kb = b.map(key);
  let start = 0;
  while (start < a.length && start < b.length && ka[start] === kb[start]) start++;
  let endA = a.length;
  let endB = b.length;
  while (endA > start && endB > start && ka[endA - 1] === kb[endB - 1]) {
    endA--;
    endB--;
  }
  const head: Op<T>[] = a.slice(0, start).map((x, i) => ({ op: 'eq', a: x, b: b[i] }));
  const tail: Op<T>[] = a.slice(endA).map((x, i) => ({ op: 'eq', a: x, b: b[endB + i] }));
  const n = endA - start;
  const m = endB - start;
  const mid: Op<T>[] = [];
  if (n * m > MAX_CELLS) {
    for (let i = start; i < endA; i++) mid.push({ op: 'del', a: a[i] });
    for (let j = start; j < endB; j++) mid.push({ op: 'add', b: b[j] });
    return [...head, ...mid, ...tail];
  }
  // lcs[i][j] = LCS length of a[start+i..endA) and b[start+j..endB), flattened
  const w = m + 1;
  const lcs = new Int32Array((n + 1) * w);
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      lcs[i * w + j] =
        ka[start + i] === kb[start + j] ? lcs[(i + 1) * w + j + 1] + 1 : Math.max(lcs[(i + 1) * w + j], lcs[i * w + j + 1]);
    }
  }
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (ka[start + i] === kb[start + j]) {
      mid.push({ op: 'eq', a: a[start + i], b: b[start + j] });
      i++;
      j++;
    } else if (lcs[(i + 1) * w + j] >= lcs[i * w + j + 1]) {
      mid.push({ op: 'del', a: a[start + i++] });
    } else {
      mid.push({ op: 'add', b: b[start + j++] });
    }
  }
  while (i < n) mid.push({ op: 'del', a: a[start + i++] });
  while (j < m) mid.push({ op: 'add', b: b[start + j++] });
  return [...head, ...mid, ...tail];
}

/** Words, numbers, quoted strings, whitespace and single punctuation characters. */
function tokens(line: string): string[] {
  return line.match(/\s+|'(?:[^']|'')*'|"[^"]*"|[A-Za-z_][\w$]*|\d+(?:\.\d+)?|./g) ?? [];
}

function merge(parts: Part[]): Part[] {
  const out: Part[] = [];
  for (const p of parts) {
    const last = out[out.length - 1];
    if (last && last.kind === p.kind) last.text += p.text;
    else out.push({ ...p });
  }
  return out;
}

/** Word-level diff of one changed line pair. */
export function diffWords(left: string, right: string, ignoreCaseAndSpace = false): { left: Part[]; right: Part[] } {
  const key = (t: string) => (ignoreCaseAndSpace ? (/^\s+$/.test(t) ? ' ' : t.toLowerCase()) : t);
  const ops = diffSequence(tokens(left), tokens(right), key);
  const l: Part[] = [];
  const r: Part[] = [];
  for (const o of ops) {
    if (o.op === 'eq') {
      l.push({ kind: 'same', text: o.a });
      r.push({ kind: 'same', text: o.b });
    } else if (o.op === 'del') {
      l.push({ kind: 'del', text: o.a });
    } else {
      r.push({ kind: 'add', text: o.b });
    }
  }
  return { left: merge(l), right: merge(r) };
}

/** Diffs two texts line by line; changed line pairs carry word-level parts. */
export function diffLines(leftText: string, rightText: string, options: DiffOptions = {}): DiffResult {
  const ignore = !!options.ignoreCaseAndSpace;
  const context = options.context ?? Number.POSITIVE_INFINITY;
  const split = (t: string) => (t === '' ? [] : t.replace(/\r\n?/g, '\n').replace(/\n$/, '').split('\n'));
  const a = split(leftText);
  const b = split(rightText);
  const key = (line: string) => (ignore ? line.replace(/\s+/g, ' ').trim().toLowerCase() : line);
  const ops = diffSequence(a, b, key);

  const rows: DiffRow[] = [];
  let ln = 0;
  let rn = 0;
  let added = 0;
  let removed = 0;
  let changed = 0;
  let k = 0;
  while (k < ops.length) {
    const o = ops[k];
    if (o.op === 'eq') {
      rows.push({
        kind: 'same',
        left: { no: ++ln, parts: [{ kind: 'same', text: o.a }] },
        right: { no: ++rn, parts: [{ kind: 'same', text: o.b }] },
      });
      k++;
      continue;
    }
    // a block of deletions and additions: pair them up as changed lines, the rest are pure adds / removes
    const dels: string[] = [];
    const adds: string[] = [];
    while (k < ops.length && ops[k].op !== 'eq') {
      const x = ops[k++];
      if (x.op === 'del') dels.push(x.a);
      else if (x.op === 'add') adds.push(x.b);
    }
    const pairs = Math.min(dels.length, adds.length);
    for (let p = 0; p < pairs; p++) {
      const w = diffWords(dels[p], adds[p], ignore);
      rows.push({ kind: 'changed', left: { no: ++ln, parts: w.left }, right: { no: ++rn, parts: w.right } });
      changed++;
    }
    for (let p = pairs; p < dels.length; p++) {
      rows.push({ kind: 'removed', left: { no: ++ln, parts: [{ kind: 'del', text: dels[p] }] } });
      removed++;
    }
    for (let p = pairs; p < adds.length; p++) {
      rows.push({ kind: 'added', right: { no: ++rn, parts: [{ kind: 'add', text: adds[p] }] } });
      added++;
    }
  }
  return { rows: fold(rows, context), added, removed, changed, identical: added + removed + changed === 0 };
}

/** Folds runs of unchanged rows longer than 2 x context (+2) into a single 'skip' row. */
function fold(rows: DiffRow[], context: number): DiffRow[] {
  if (!Number.isFinite(context)) return rows;
  const out: DiffRow[] = [];
  let i = 0;
  while (i < rows.length) {
    if (rows[i].kind !== 'same') {
      out.push(rows[i++]);
      continue;
    }
    let j = i;
    while (j < rows.length && rows[j].kind === 'same') j++;
    const run = rows.slice(i, j);
    const keepHead = i === 0 ? 0 : context;
    const keepTail = j === rows.length ? 0 : context;
    if (run.length > keepHead + keepTail + 2) {
      out.push(...run.slice(0, keepHead));
      out.push({ kind: 'skip', skipped: run.length - keepHead - keepTail });
      out.push(...run.slice(run.length - keepTail));
    } else {
      out.push(...run);
    }
    i = j;
  }
  return out;
}
