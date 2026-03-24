import { memo, useEffect, useCallback, useState } from 'react';
import type { MentionSearchResult } from '@/bridge/types';
import {
  Command,
  CommandInput,
  CommandList,
  CommandEmpty,
  CommandGroup,
  CommandItem,
} from '@/components/ui/command';

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
}

export const TicketDropdown = memo(function TicketDropdown({
  query,
  onSelect,
  onDismiss: _onDismiss,
}: TicketDropdownProps) {
  const [results, setResults] = useState<MentionSearchResult[]>([]);

  // Search tickets via Kotlin bridge
  useEffect(() => {
    const handler = (json: string) => {
      try {
        setResults(JSON.parse(json));
      } catch { /* ignore */ }
    };
    (window as any).__ticketSearchCallback = handler;

    if (query) {
      window._searchTickets?.(query);
    } else {
      window._searchTickets?.('');  // Show recent/active tickets
    }

    return () => { delete (window as any).__ticketSearchCallback; };
  }, [query]);

  const handleSelect = useCallback((value: string) => {
    const result = results.find(r => r.label === value || r.path === value);
    if (result) onSelect(result);
  }, [results, onSelect]);

  return (
    <div className="absolute bottom-full left-0 mb-1 w-96 z-50">
      <Command
        shouldFilter={false}
        className="rounded-lg"
        style={{
          backgroundColor: 'var(--surface-elevated, var(--toolbar-bg, var(--popover)))',
          border: '1px solid var(--border)',
          boxShadow: '0 8px 30px rgba(0, 0, 0, 0.5), 0 2px 8px rgba(0, 0, 0, 0.3)',
        }}
      >
        <CommandInput
          placeholder="Search tickets (key or summary)..."
          value={query}
          className="text-xs"
        />
        <CommandList className="max-h-64">
          <CommandEmpty className="text-xs py-4 text-center" style={{ color: 'var(--fg-muted)' }}>
            No tickets found.
          </CommandEmpty>
          <CommandGroup heading="Tickets">
            {results.map((r) => (
              <CommandItem
                key={r.path ?? r.label}
                value={r.path ?? r.label}
                onSelect={handleSelect}
                className="text-xs gap-2"
              >
                <TicketIcon className="size-3.5 shrink-0" style={{ color: 'var(--accent-read, #3b82f6)' }} />
                <span className="font-mono font-medium shrink-0" style={{ color: 'var(--accent-read, #3b82f6)' }}>
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
              </CommandItem>
            ))}
          </CommandGroup>
        </CommandList>
      </Command>
    </div>
  );
});
