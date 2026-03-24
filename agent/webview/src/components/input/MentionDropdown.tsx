import { memo, useEffect, useCallback } from 'react';
import type { MentionSearchResult } from '@/bridge/types';
import { useChatStore } from '@/stores/chatStore';
import {
  Command,
  CommandInput,
  CommandList,
  CommandEmpty,
  CommandGroup,
  CommandItem,
} from '@/components/ui/command';

const typeIcons: Record<string, string> = {
  file: '📄',
  folder: '📁',
  symbol: '#',
  tool: '🔧',
  skill: '✨',
};

const typeLabels: Record<string, string> = {
  file: 'Files',
  folder: 'Folders',
  symbol: 'Symbols',
  tool: 'Tools',
  skill: 'Skills',
};

interface MentionDropdownProps {
  query: string;
  onSelect: (result: MentionSearchResult) => void;
  onDismiss: () => void;
}

export const MentionDropdown = memo(function MentionDropdown({
  query,
  onSelect,
  onDismiss: _onDismiss,
}: MentionDropdownProps) {
  const mentionResults = useChatStore(s => s.mentionResults);

  // Request search results from Kotlin
  useEffect(() => {
    if (query) {
      window._searchMentions?.(query);
    } else {
      window._searchMentions?.('categories:');
    }
  }, [query]);

  // Group results by type
  const groups = mentionResults.reduce<Record<string, MentionSearchResult[]>>((acc, r) => {
    (acc[r.type] ??= []).push(r);
    return acc;
  }, {});

  const handleSelect = useCallback((value: string) => {
    const result = mentionResults.find(r => r.label === value || r.path === value);
    if (result) onSelect(result);
  }, [mentionResults, onSelect]);

  return (
    <div className="absolute bottom-full left-0 mb-1 w-72 z-50">
      <Command
        className="rounded-lg border shadow-lg"
        style={{ backgroundColor: 'var(--popover)', borderColor: 'var(--border)' }}
      >
        <CommandInput
          placeholder="Search files, symbols, tools..."
          value={query}
          className="text-xs"
        />
        <CommandList className="max-h-60">
          <CommandEmpty className="text-xs py-4 text-center" style={{ color: 'var(--fg-muted)' }}>
            No results found.
          </CommandEmpty>
          {Object.entries(groups).map(([type, results]) => (
            <CommandGroup key={type} heading={typeLabels[type] ?? type}>
              {results.map((r) => (
                <CommandItem
                  key={r.path ?? r.label}
                  value={r.path ?? r.label}
                  onSelect={handleSelect}
                  className="text-xs gap-2"
                >
                  <span className="text-[10px] opacity-60">{r.icon ?? typeIcons[r.type] ?? '@'}</span>
                  <span className="truncate">{r.label}</span>
                  {r.description && (
                    <span className="ml-auto text-[10px] truncate max-w-[120px]" style={{ color: 'var(--fg-muted)' }}>
                      {r.description}
                    </span>
                  )}
                </CommandItem>
              ))}
            </CommandGroup>
          ))}
        </CommandList>
      </Command>
    </div>
  );
});
