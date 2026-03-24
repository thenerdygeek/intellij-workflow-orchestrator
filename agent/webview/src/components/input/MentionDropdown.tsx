import { memo, useEffect, useCallback, useMemo } from 'react';
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
import { File, Folder, Hash } from 'lucide-react';

const typeIconComponents: Record<string, React.ElementType> = {
  file: File,
  folder: Folder,
  symbol: Hash,
};

const typeIconColors: Record<string, string> = {
  file: 'var(--accent-read, #3b82f6)',
  folder: 'var(--accent-read, #3b82f6)',
  symbol: '#a78bfa',
};

const typeLabels: Record<string, string> = {
  file: 'Files',
  folder: 'Folders',
  symbol: 'Symbols',
};

const TYPE_ORDER: Record<string, number> = { file: 0, folder: 1, symbol: 2 };

/**
 * Score a result against a query for relevance ranking.
 * Higher score = more relevant. Supports:
 * - Exact prefix match (highest)
 * - Word boundary match
 * - Substring match (anywhere in text)
 * - Path segment match
 */
function relevanceScore(label: string, path: string | undefined, query: string): number {
  if (!query) return 0;
  const q = query.toLowerCase();
  const l = label.toLowerCase();
  const p = (path ?? '').toLowerCase();

  // Exact name start → highest relevance
  if (l.startsWith(q)) return 100;

  // File name (last segment) starts with query
  const fileName = l.includes('/') ? l.split('/').pop()! : l;
  if (fileName.startsWith(q)) return 90;

  // Word boundary match in label (e.g. "chat" matches "chatStore.ts")
  if (new RegExp(`\\b${q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`).test(l)) return 80;

  // Substring in label
  if (l.includes(q)) return 70;

  // Substring in full path
  if (p.includes(q)) return 50;

  // Fuzzy: all query chars appear in order in label
  let qi = 0;
  for (let i = 0; i < l.length && qi < q.length; i++) {
    if (l[i] === q[qi]) qi++;
  }
  if (qi === q.length) return 30;

  return 0;
}

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

  // Request search results from Kotlin — search files, folders, symbols
  useEffect(() => {
    const timer = setTimeout(() => {
      if (query) {
        window._searchMentions?.(`file:${query}`);
      } else {
        window._searchMentions?.('categories:');
      }
    }, 200);
    return () => clearTimeout(timer);
  }, [query]);

  // Filter to only file/folder/symbol types and sort by relevance
  const contextResults = useMemo(() => {
    return mentionResults
      .filter(r => r.type === 'file' || r.type === 'folder' || r.type === 'symbol')
      .map(r => ({ ...r, score: relevanceScore(r.label, r.path, query) }))
      .sort((a, b) => {
        if (query && b.score !== a.score) return b.score - a.score;
        return (TYPE_ORDER[a.type] ?? 3) - (TYPE_ORDER[b.type] ?? 3);
      });
  }, [mentionResults, query]);

  // When there's a query, show as a flat relevance-ranked list
  // When empty, group by type with 2 items each
  const showGrouped = !query;

  // Group by type for initial display
  const groups = showGrouped
    ? contextResults.reduce<Record<string, typeof contextResults>>((acc, r) => {
        (acc[r.type] ??= []).push(r);
        return acc;
      }, {})
    : {};

  // Limit initial groups to 2 items each
  if (showGrouped) {
    for (const type in groups) {
      groups[type] = groups[type]!.slice(0, 2);
    }
  }

  const handleSelect = useCallback((value: string) => {
    const result = mentionResults.find(r => r.label === value || r.path === value);
    if (result) onSelect(result);
  }, [mentionResults, onSelect]);

  return (
    <div className="absolute bottom-full left-0 mb-1 w-80 z-50">
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
          placeholder="Search files, folders, symbols..."
          value={query}
          className="text-xs"
        />
        <CommandList className="max-h-64">
          <CommandEmpty className="text-xs py-4 text-center" style={{ color: 'var(--fg-muted)' }}>
            No results found.
          </CommandEmpty>

          {showGrouped ? (
            /* Initial state: grouped by type, 2 items each */
            Object.entries(groups).map(([type, results]) => (
              <CommandGroup key={type} heading={typeLabels[type] ?? type}>
                {results.map((r) => (
                  <MentionItem key={r.path ?? r.label} result={r} onSelect={handleSelect} />
                ))}
              </CommandGroup>
            ))
          ) : (
            /* Search state: flat relevance-ranked list */
            <CommandGroup heading="Results">
              {contextResults.slice(0, 12).map((r) => (
                <MentionItem key={r.path ?? r.label} result={r} onSelect={handleSelect} />
              ))}
            </CommandGroup>
          )}
        </CommandList>
      </Command>
    </div>
  );
});

function MentionItem({ result, onSelect }: { result: MentionSearchResult; onSelect: (value: string) => void }) {
  const IconComponent = typeIconComponents[result.type] ?? File;
  const iconColor = typeIconColors[result.type] ?? 'var(--fg-muted)';

  return (
    <CommandItem
      value={result.path ?? result.label}
      onSelect={onSelect}
      className="text-xs gap-2"
    >
      <IconComponent className="size-3.5 shrink-0" style={{ color: iconColor }} />
      <span className="truncate">{result.label}</span>
      {result.description && (
        <span className="ml-auto text-[10px] truncate max-w-[140px]" style={{ color: 'var(--fg-muted)' }}>
          {result.description}
        </span>
      )}
    </CommandItem>
  );
}
