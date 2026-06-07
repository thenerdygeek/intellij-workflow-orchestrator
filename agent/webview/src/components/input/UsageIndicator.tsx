import { useEffect, useState, useRef } from 'react';

/**
 * Multimodal-agent Phase 7 Task 7.2 — live token usage indicator below the
 * chat input.
 *
 * Spec (design doc §Decision 7 → option D, input side):
 *   - Text: `context: 23K / 132K used (17%)`
 *   - Color: gray <50%, amber 50-80%, red >80%
 *   - Source: `window.workflowAgent.getContextUsage()` returns `{used, max}`
 *
 * Polling cadence: every 1s while the indicator is mounted, with a single
 * fetch on mount so the indicator never starts at 0/0 once data is available.
 * Polling pauses when the document is hidden (Page Visibility API) so the
 * IDE doesn't burn CPU on unfocused windows.
 *
 * Defensive against:
 *   - Bridge missing (page hasn't fully loaded; sandbox/test build) → 0 / 132K
 *   - Bridge throwing → preserve last-good value, do not crash
 *   - max === 0 → render `0%` instead of `Infinity%`
 */
export function UsageIndicator() {
  const [usage, setUsage] = useState<{ used: number; max: number }>({ used: 0, max: 132_000 });
  const lastUsageRef = useRef(usage);

  useEffect(() => {
    let cancelled = false;

    async function tick() {
      const wf = (window as any).workflowAgent;
      const fn = wf?.getContextUsage;
      if (typeof fn !== 'function') {
        // Bridge not yet wired — preserve last-good and try again next interval.
        return;
      }
      try {
        const result = await fn();
        if (cancelled) return;
        if (result && typeof result.used === 'number' && typeof result.max === 'number') {
          const next = { used: result.used, max: result.max };
          lastUsageRef.current = next;
          setUsage(next);
        }
      } catch {
        // Swallow — next tick may succeed.
      }
    }

    // Initial fetch + polling
    tick();
    const id = setInterval(() => {
      if (typeof document !== 'undefined' && document.hidden) return;
      tick();
    }, 1000);
    // Event-driven immediate refresh — Kotlin fires this after context compaction and after a
    // session handoff / new_task so the bar reflects the new context size at once instead of
    // waiting up to 1s (and even when the poll is paused via document.hidden).
    const onRefresh = () => { tick(); };
    if (typeof window !== 'undefined') window.addEventListener('wf-context-usage-refresh', onRefresh);
    return () => {
      cancelled = true;
      clearInterval(id);
      if (typeof window !== 'undefined') window.removeEventListener('wf-context-usage-refresh', onRefresh);
    };
  }, []);

  const pct = usage.max > 0 ? (usage.used / usage.max) * 100 : 0;
  const color = pct < 50 ? '#888' : pct < 80 ? '#d97706' : '#dc2626';
  const usedK = Math.round(usage.used / 1000);
  const maxK = Math.round(usage.max / 1000);

  return (
    <div
      data-testid="usage-indicator"
      className="text-[10px] px-3 pb-1.5 select-none"
      style={{ color, paddingTop: '2px' }}
      title={`Context usage: ${usage.used.toLocaleString()} / ${usage.max.toLocaleString()} tokens`}
    >
      context: {usedK}K / {maxK}K used ({pct.toFixed(0)}%)
    </div>
  );
}
