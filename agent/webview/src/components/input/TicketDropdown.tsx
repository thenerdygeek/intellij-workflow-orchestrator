import { memo, useEffect, useState } from 'react';
import type { MentionSearchResult } from '@/bridge/types';

// Inline Jira ticket icon (blue square with ticket lines)
function TicketIcon({ className, style }: { className?: string; style?: React.CSSProperties }) {
  return (
    <svg className={className} style={style} viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
      <rect x="1" y="2" width="14" height="12" rx="2" stroke="currentColor" strokeWidth="1.5" fill="none" />
      <path d="M4.5 5.5h7M4.5 8h5M4.5 10.5h3" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" />
    </svg>
  );
}

const statusColors: Record<string, string> = {
  'To Do': 'var(--fg-muted, #6b7280)',
  'In Progress': 'var(--accent, #6366f1)',
  'In Review': 'var(--accent-search, #06b6d4)',
  'Done': 'var(--success, #22c55e)',
  'Closed': 'var(--success, #22c55e)',
};

interface TicketDropdownProps {
  query: string;
  onSelect: (result: MentionSearchResult) => void;
  onDismiss: () => void;
  selectedIndex: number;
  setSelectedIndex: (i: number) => void;
  listRef: React.RefObject<HTMLDivElement>;
  /** Called whenever ticket search results update — lets InputBar sync for keyboard nav */
  onResultsChange?: (results: MentionSearchResult[]) => void;
}

export const TicketDropdown = memo(function TicketDropdown({
  query,
  onSelect,
  onDismiss: _onDismiss,
  selectedIndex,
  setSelectedIndex,
  listRef,
  onResultsChange,
}: TicketDropdownProps) {
  const [results, setResults] = useState<MentionSearchResult[]>([]);
  const [loading, setLoading] = useState(false);

  // Search tickets via Kotlin bridge
  useEffect(() => {
    const cbId = `__ticketSearch_${Math.random().toString(36).slice(2)}`;
    setLoading(true);

    const handler = (json: string) => {
      setLoading(false);
      try {
        const parsed: MentionSearchResult[] = JSON.parse(json);
        setResults(parsed);
        onResultsChange?.(parsed);
      } catch { /* ignore */ }
    };
    (window as any)[cbId] = handler;
    (window as any).__ticketSearchCallback = handler;

    window._searchTickets?.(query ?? '');

    return () => {
      delete (window as any)[cbId];
      if ((window as any).__ticketSearchCallback === handler) {
        delete (window as any).__ticketSearchCallback;
      }
    };
  }, [query]);

  const isEmpty = results.length === 0;

  return (
    <div className="absolute bottom-full left-0 mb-1 w-96 z-50">
      <div
        className="rounded-lg overflow-hidden"
        style={{
          backgroundColor: 'var(--surface-elevated, var(--toolbar-bg, var(--popover)))',
          border: '1px solid var(--border)',
          boxShadow: '0 8px 30px rgba(0, 0, 0, 0.5), 0 2px 8px rgba(0, 0, 0, 0.3)',
        }}
      >
        {/* Group heading */}
        <div
          className="px-3 py-1.5 text-[10px] font-medium uppercase tracking-wider border-b"
          style={{ color: 'var(--fg-muted)', borderColor: 'var(--border)' }}
        >
          Tickets
        </div>

        {loading && isEmpty ? (
          // Loading pulse skeleton
          <div className="p-2 space-y-1">
            {[1, 2, 3].map(i => (
              <div
                key={i}
                className="flex items-center gap-2 px-2 py-1.5 rounded"
                style={{ animation: 'pulse 1.5s ease-in-out infinite' }}
              >
                <div
                  className="size-3.5 rounded shrink-0"
                  style={{ backgroundColor: 'var(--hover-overlay, rgba(255,255,255,0.08))' }}
                />
                <div
                  className="h-3 rounded flex-1"
                  style={{ backgroundColor: 'var(--hover-overlay, rgba(255,255,255,0.08))' }}
                />
                <div
                  className="h-3 w-16 rounded"
                  style={{ backgroundColor: 'var(--hover-overlay, rgba(255,255,255,0.08))' }}
                />
              </div>
            ))}
          </div>
        ) : isEmpty ? (
          <div
            className="text-xs py-4 text-center"
            style={{ color: 'var(--fg-muted)' }}
          >
            No tickets found.
          </div>
        ) : (
          <div
            ref={listRef}
            className="max-h-64 overflow-y-auto p-1"
            style={{ scrollbarWidth: 'thin' }}
          >
            {results.map((r, i) => {
              const highlighted = i === selectedIndex;
              return (
                <div
                  key={r.path ?? r.label}
                  data-highlighted={highlighted ? 'true' : undefined}
                  onClick={() => onSelect(r)}
                  onMouseEnter={() => setSelectedIndex(i)}
                  className="flex items-center gap-2 px-2 py-1.5 rounded text-xs cursor-default"
                  style={{
                    backgroundColor: highlighted
                      ? 'var(--hover-overlay, rgba(255,255,255,0.08))'
                      : 'transparent',
                    borderLeft: highlighted
                      ? '2px solid var(--accent, #6366f1)'
                      : '2px solid transparent',
                    transition: 'background-color 0.1s ease, border-color 0.1s ease',
                    fontWeight: highlighted ? 500 : 400,
                  }}
                >
                  <TicketIcon
                    className="size-3.5 shrink-0"
                    style={{ color: 'var(--accent-read, #3b82f6)' }}
                  />
                  <span
                    className="font-mono font-medium shrink-0"
                    style={{ color: 'var(--accent-read, #3b82f6)' }}
                  >
                    {r.label}
                  </span>
                  {r.description && (
                    <span className="truncate" style={{ color: 'var(--fg-secondary)' }}>
                      {r.description}
                    </span>
                  )}
                  {r.icon && (
                    <span
                      className="ml-auto text-[9px] font-medium px-1.5 py-0.5 rounded shrink-0"
                      style={{
                        color: statusColors[r.icon] ?? 'var(--fg-muted)',
                        backgroundColor: 'var(--hover-overlay-strong, rgba(255,255,255,0.05))',
                      }}
                    >
                      {r.icon}
                    </span>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
});

export type { TicketDropdownProps };
// Export results accessor so InputBar can build the flat item list
export { TicketDropdown as default };
