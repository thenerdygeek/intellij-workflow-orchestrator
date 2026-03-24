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
import { File, Folder, Hash, Wrench, Sparkles, AtSign } from 'lucide-react';

const typeIconComponents: Record<string, React.ElementType> = {
  file: File,
  folder: Folder,
  symbol: Hash,
  tool: Wrench,
  skill: Sparkles,
};

const typeIconColors: Record<string, string> = {
  file: 'var(--accent-read, #3b82f6)',
  folder: 'var(--accent-read, #3b82f6)',
  symbol: '#a78bfa',
  tool: 'var(--accent-write, #22c55e)',
  skill: 'var(--accent-edit, #f59e0b)',
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
        className="rounded-lg"
        style={{
          backgroundColor: 'var(--surface-elevated, var(--toolbar-bg, var(--popover)))',
          border: '1px solid var(--border)',
          boxShadow: '0 8px 30px rgba(0, 0, 0, 0.5), 0 2px 8px rgba(0, 0, 0, 0.3)',
        }}
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
                  {(() => {
                    const IconComponent = typeIconComponents[r.type] ?? AtSign;
                    const iconColor = typeIconColors[r.type] ?? 'var(--fg-muted)';
                    return <IconComponent className="size-3.5 shrink-0" style={{ color: iconColor }} />;
                  })()}
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
