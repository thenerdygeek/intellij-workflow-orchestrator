import { useEffect, useRef, useState } from 'react';
import { useChatStore } from '../../stores/chatStore';

function truncate(s: string, max: number): string {
  return s.length > max ? s.slice(0, max - 1) + '…' : s;
}

/**
 * Read-only top-bar chip that shows active monitors for the current session.
 * Populated via `useChatStore.monitorHandles`, pushed from Kotlin through
 * `window.__receiveMonitorUpdate` and hydrated on session load via
 * `window._loadMonitorSnapshot`. Renders nothing when the list is empty.
 */
export function MonitorIndicator() {
  const monitors = useChatStore((s) => s.monitorHandles);
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  const hasRunning = monitors.some((m) => m.state === 'RUNNING');

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

  if (monitors.length === 0) return null;

  const chipStyle: React.CSSProperties = {
    border: '1px solid var(--border, #333)',
    background: 'transparent',
    color: 'var(--fg-secondary, #9ca3af)',
  };

  return (
    <div ref={ref} style={{ position: 'relative', display: 'inline-block' }}>
      <button
        type="button"
        aria-label={`${monitors.length} monitor${monitors.length !== 1 ? 's' : ''}, click to view`}
        onClick={() => setOpen((o) => !o)}
        className="flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] font-medium transition-colors hover:bg-[var(--hover-overlay,rgba(255,255,255,0.06))]"
        style={chipStyle}
      >
        {/* Eye icon */}
        <svg width="10" height="10" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
          <path d="M1 8s3-5 7-5 7 5 7 5-3 5-7 5-7-5-7-5z" />
          <circle cx="8" cy="8" r="2.5" />
        </svg>
        <span>{monitors.length} monitor{monitors.length !== 1 ? 's' : ''}</span>
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
            width: '360px',
            border: '1px solid var(--border, #333)',
            background: 'var(--bg, #1e1e1e)',
            padding: '6px',
          }}
        >
          <div
            className="text-[11px] font-medium px-1 mb-1"
            style={{ color: 'var(--fg-secondary, #9ca3af)' }}
          >
            Active monitors
          </div>
          {monitors.map((m) => {
            const dotColor =
              m.state === 'RUNNING'
                ? 'var(--accent-write, #22c55e)'
                : m.state === 'EXITED'
                ? 'var(--fg-muted, #6b7280)'
                : 'var(--fg-muted, #6b7280)';

            return (
              <div
                key={m.id}
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
                    ...(m.state === 'RUNNING'
                      ? { animation: 'pulse 2s ease-in-out infinite' }
                      : {}),
                  }}
                />
                {/* id */}
                <code
                  className="shrink-0 text-[10px]"
                  style={{ color: 'var(--fg-muted, #6b7280)' }}
                >
                  {m.id}
                </code>
                {/* label */}
                <span
                  className="flex-1 truncate"
                  style={{ color: 'var(--fg-secondary, #9ca3af)' }}
                  title={m.label}
                >
                  {truncate(m.label, 50)}
                </span>
                {/* state */}
                <span
                  className="shrink-0"
                  style={{ color: 'var(--fg-muted, #6b7280)' }}
                >
                  {m.state.toLowerCase()}
                </span>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
