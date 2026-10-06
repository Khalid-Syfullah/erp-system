// Per-browser conveniences only (theme, last company, sidebar state). Storage can be unavailable
// (private windows, blocked site data), so every access is guarded and has a default.

export function readStored(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

export function writeStored(key: string, value: string | null): void {
  try {
    if (value === null) window.localStorage.removeItem(key);
    else window.localStorage.setItem(key, value);
  } catch {
    // ignored: the preference just is not remembered
  }
}
