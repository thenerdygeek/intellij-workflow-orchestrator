import { useEffect, useMemo, useRef, useState } from 'react';
import { useChatStore } from '../../stores/chatStore';

function formatRuntime(ms: number): string {
  const s = Math.floor(Math.max(0, ms) / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  return `${m}m ${s % 60}s`;
}

function truncate(s: string, max: number): string {
  return s.length > max ? s.slice(0, max - 1) + '…' : s;
}

/**
 * Read-only top-bar chip that shows background processes for the current session.
 * Populated via `useChatStore.backgroundProcesses`, pushed from Kotlin through
 * `window.__receiveBackgroundUpdate` and hydrated on session load via
 * `window._loadBackgroundSnapshot`. Renders nothing when the list is empty.
 */
export function BackgroundIndicator() {
  const processes = useChatStore((s) => s.backgroundProcesses);
  const [open, setOpen] = useState(false);
  const [, setTick] = useState(0);
  const ref = useRef<HTMLDivElement>(null);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const hasRunning = useMemo(() => processes.some((p) => p.state === 'RUNNING'), [processes]);
  const hasError = useMemo(
    () =>
      processes.some(
        (p) =>
          p.state === 'TIMED_OUT' ||
          (p.state === 'EXITED' && (p.exitCode ?? 0) !== 0),
      ),
    [processes],
  );

  // 1s re-render tick while dropdown open and there is a RUNNING process
  // so the live runtime counter updates.
  useEffect(() => {
    if (open && hasRunning) {
      timerRef.current = setInterval(() => setTick((t) => t + 1), 1000);
      return () => {
        if (timerRef.current != null) clearInterval(timerRef.current);
      };
    }
  }, [open, hasRunning]);

  // Close on outside click or Escape.
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onEsc = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onEsc);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onEsc);
    };
  }, [open]);

  if (processes.length === 0) return null;

  const chipStyle: React.CSSProperties = hasError
    ? {
        border: '1px solid color-mix(in srgb, var(--accent-edit, #f59e0b) 40%, transparent)',
        background: 'color-mix(in srgb, var(--accent-edit, #f59e0b) 12%, transparent)',
        color: 'var(--accent-edit, #f59e0b)',
      }
    : {
        border: '1px solid var(--border, #333)',
        background: 'transparent',
        color: 'var(--fg-secondary, #9ca3af)',
      };

  return (
    <div ref={ref} style={{ position: 'relative', display: 'inline-block' }}>
      <button
        type="button"
        aria-label={`${processes.length} background process${processes.length !== 1 ? 'es' : ''}, click to view`}
        onClick={() => setOpen((o) => !o)}
        className="flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] font-medium transition-colors hover:bg-[var(--hover-overlay,rgba(255,255,255,0.06))]"
        style={chipStyle}
      >
        {/* Gear icon */}
        <svg width="10" height="10" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
          <circle cx="8" cy="8" r="2.5" />
          <path d="M8 1v2M8 13v2M1 8h2M13 8h2M3.05 3.05l1.41 1.41M11.54 11.54l1.41 1.41M3.05 12.95l1.41-1.41M11.54 4.46l1.41-1.41" />
        </svg>
        <span>{processes.length} bg</span>
        {hasRunning && (
          <span
            className="inline-block size-1.5 rounded-full"
            style={{
              background: 'var(--accent-write, #22c55e)',
              animation: 'pulse 2s ease-in-out infinite',
            }}
          />
        )}
      </button>

      {open && (
        <div
          className="absolute top-full left-0 mt-1 z-50 rounded-md shadow-lg"
          style={{
            width: '480px',
            border: '1px solid var(--border, #333)',
            background: 'var(--bg, #1e1e1e)',
            padding: '6px',
          }}
        >
          <div
            className="text-[11px] font-medium px-1 mb-1"
            style={{ color: 'var(--fg-secondary, #9ca3af)' }}
          >
            Background processes
          </div>
          {processes.map((p) => {
            const runtime =
              p.state === 'RUNNING'
                ? formatRuntime(Date.now() - p.startedAt)
                : formatRuntime(p.runtimeMs);

            const dotColor =
              p.state === 'RUNNING'
                ? 'var(--accent-write, #22c55e)'
                : p.state === 'TIMED_OUT'
                ? 'var(--accent-edit, #f59e0b)'
                : (p.exitCode ?? 0) !== 0
                ? 'var(--accent-edit, #f59e0b)'
                : 'var(--fg-muted, #6b7280)';

            const stateLabel =
              p.state === 'RUNNING'
                ? 'running'
                : p.state === 'TIMED_OUT'
                ? 'timed out'
                : p.state === 'KILLED'
                ? 'killed'
                : (p.exitCode ?? 0) !== 0
                ? `exit ${p.exitCode}`
                : 'done';

            return (
              <div
                key={p.bgId}
                className="flex items-center gap-2 py-1 px-1 text-[11px] rounded"
                style={{ color: 'var(--fg, #e5e7eb)' }}
              >
                {/* State dot */}
                <span
                  className="inline-block shrink-0 rounded-full"
                  style={{
                    width: '7px',
                    height: '7px',
                    background: dotColor,
                    ...(p.state === 'RUNNING'
                      ? { animation: 'pulse 2s ease-in-out infinite' }
                      : {}),
                  }}
                />
                {/* bgId */}
                <code
                  className="shrink-0 text-[10px]"
                  style={{ color: 'var(--fg-muted, #6b7280)' }}
                >
                  {p.bgId}
                </code>
                {/* label */}
                <span
                  className="flex-1 truncate"
                  style={{ color: 'var(--fg-secondary, #9ca3af)' }}
                  title={p.label}
                >
                  {truncate(p.label, 60)}
                </span>
                {/* state + runtime */}
                <span
                  className="shrink-0 tabular-nums"
                  style={{ color: 'var(--fg-muted, #6b7280)' }}
                >
                  {stateLabel} · {runtime}
                </span>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
