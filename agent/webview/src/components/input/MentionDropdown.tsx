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
  symbol: 'var(--accent-search, #a78bfa)',
};

const typeLabels: Record<string, string> = {
  file: 'Files',
  folder: 'Folders',
  symbol: 'Symbols',
};


/**
 * Score a result against a query for relevance ranking.
 * Higher score = more relevant. Name matches always beat path-only matches.
 * No fuzzy matching — results must contain the query as a substring.
 */
function relevanceScore(label: string | undefined, path: string | undefined, query: string): number {
  if (!query || !label) return 0;
  const q = query.toLowerCase();
  const l = label.toLowerCase();
  const nameNoExt = l.replace(/\.[^.]+$/, '').replace(/\/$/, '');
  const p = (path ?? '').toLowerCase();

  // Name (without extension) starts with query → highest
  if (nameNoExt.startsWith(q)) return 100;

  // File name starts with query (handles label being a path)
  const fileName = l.includes('/') ? l.split('/').pop()! : l;
  if (fileName.replace(/\.[^.]+$/, '').startsWith(q)) return 95;

  // Word boundary match in name (e.g. "test" in "MyServiceTest")
  const escaped = q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  if (new RegExp(`(?:^|[_\\-A-Z])${escaped}`, 'i').test(nameNoExt)) return 85;

  // Substring anywhere in label/name
  if (l.includes(q)) return 75;

  // Path-only match — name doesn't contain query but path does
  if (p.includes(q)) return 30;

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

  // Request search results from Kotlin — search files, folders, symbols together
  useEffect(() => {
    const timer = setTimeout(() => {
      if (query) {
        window._searchMentions?.(`all:${query}`);
      } else {
        window._searchMentions?.('categories:');
      }
    }, 200);
    return () => clearTimeout(timer);
  }, [query]);

  // Filter to only file/folder/symbol types, score, and group by type
  const maxPerGroup = query ? 5 : 2;

  const groups = useMemo(() => {
    const scored = mentionResults
      .filter(r => r.type === 'file' || r.type === 'folder' || r.type === 'symbol')
      .map(r => ({ ...r, score: relevanceScore(r.label, r.path, query) }));

    // Group by type, sort each group by score, limit per group
    const grouped: Record<string, typeof scored> = {};
    for (const r of scored) {
      (grouped[r.type] ??= []).push(r);
    }
    for (const type in grouped) {
      grouped[type] = grouped[type]!
        .sort((a, b) => b.score - a.score)
        .slice(0, maxPerGroup);
    }
    return grouped;
  }, [mentionResults, query, maxPerGroup]);

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

          {/* Always grouped by type: Files, Folders, Symbols */}
          {(['file', 'folder', 'symbol'] as const).map(type => {
            const results = groups[type];
            if (!results?.length) return null;
            return (
              <CommandGroup key={type} heading={typeLabels[type] ?? type}>
                {results.map((r) => (
                  <MentionItem key={r.path ?? r.label} result={r} onSelect={handleSelect} />
                ))}
              </CommandGroup>
            );
          })}
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
