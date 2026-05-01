/**
 * Format a millisecond duration for display in the chat UI.
 *
 * Rules:
 *   - < 1s   → "850ms"
 *   - < 60s  → "12.3s"
 *   - >= 60s → "2m 35s"
 *
 * Sub-minute values keep one decimal so users can distinguish "0.4s" from "1.2s".
 * Once the duration crosses 60 seconds, decimals are dropped — at that scale
 * tenths-of-a-second are noise, and the minute-prefix is the more useful read.
 */
export function formatElapsedMs(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '0s';
  if (ms < 1000) return `${Math.round(ms)}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  const totalSeconds = Math.floor(ms / 1000);
  const m = Math.floor(totalSeconds / 60);
  const s = totalSeconds % 60;
  return `${m}m ${s}s`;
}

/**
 * Format a whole-second elapsed value (used by `setInterval(_, 1000)` timers
 * that don't have sub-second granularity). Same minute-prefix rule as
 * [formatElapsedMs] above 60s.
 */
export function formatElapsedSeconds(totalSec: number): string {
  if (!Number.isFinite(totalSec) || totalSec < 0) return '0s';
  if (totalSec < 60) return `${Math.floor(totalSec)}s`;
  const m = Math.floor(totalSec / 60);
  const s = Math.floor(totalSec) % 60;
  return `${m}m ${s}s`;
}
