import { memo, useRef, useEffect } from 'react';
import { useChatStore } from '@/stores/chatStore';

export const DebugPanel = memo(function DebugPanel() {
  const visible = useChatStore(s => s.debugLogVisible);
  const entries = useChatStore(s => s.debugLogEntries);
  const scrollRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to bottom on new entries
  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [entries.length]);

  if (!visible) return null;

  // Color by level
  const levelColor = (level: string) => {
    switch (level) {
      case 'error': return 'var(--error, #ef4444)';
      case 'warn': return 'var(--accent-edit, #f59e0b)';
      default: return 'var(--fg-muted, #6b7280)';
    }
  };

  return (
    <div className="shrink-0 overflow-hidden border-t"
      style={{ borderColor: 'var(--border, #333)', maxHeight: '180px', background: 'var(--tool-bg, rgba(0,0,0,0.2))' }}>

      {/* Header */}
      <div className="flex items-center justify-between px-3 py-1"
        style={{ borderBottom: '1px solid var(--border, #333)' }}>
        <span className="text-[10px] font-medium uppercase tracking-wider"
          style={{ color: 'var(--fg-muted)' }}>
          Debug Log ({entries.length})
        </span>
        <button onClick={() => useChatStore.getState().clearDebugLog()}
          className="text-[9px] px-1.5 py-0.5 rounded hover:bg-[var(--hover-overlay)]"
          style={{ color: 'var(--fg-muted)' }}>
          Clear
        </button>
      </div>

      {/* Entries */}
      <div ref={scrollRef} className="overflow-y-auto px-2 py-1" style={{ maxHeight: '150px' }}>
        {entries.length === 0 ? (
          <div className="text-[10px] py-2 text-center" style={{ color: 'var(--fg-muted)' }}>
            No activity yet. Send a message to see debug logs.
          </div>
        ) : entries.map((entry, i) => (
          <div key={i} className="flex items-start gap-1.5 py-0.5 text-[10px] font-mono leading-tight">
            <span className="shrink-0 tabular-nums" style={{ color: 'var(--fg-muted)', width: '52px' }}>
              {new Date(entry.ts).toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' })}
            </span>
            <span className="shrink-0 w-[48px] text-[9px] font-medium uppercase"
              style={{ color: levelColor(entry.level) }}>
              {entry.event.replace('_', ' ')}
            </span>
            <span style={{ color: levelColor(entry.level) }}>
              {entry.detail}
            </span>
            {entry.meta?.duration && (
              <span className="shrink-0 tabular-nums" style={{ color: 'var(--fg-muted)' }}>
                {entry.meta.duration}ms
              </span>
            )}
          </div>
        ))}
      </div>
    </div>
  );
});
