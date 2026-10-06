/** Per-viewer UI preferences in localStorage (best effort; never required for correctness). */
export function loadPref<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem('spt.' + key);
    return raw ? (JSON.parse(raw) as T) : fallback;
  } catch {
    return fallback;
  }
}

export function savePref(key: string, value: unknown): void {
  try {
    localStorage.setItem('spt.' + key, JSON.stringify(value));
  } catch {
    /* ignore */
  }
}

export function downloadBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
