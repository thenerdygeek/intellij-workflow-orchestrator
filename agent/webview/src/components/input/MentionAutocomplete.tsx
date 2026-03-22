import { memo, useCallback, useEffect, useRef, useState } from 'react';
import { useChatStore } from '@/stores/chatStore';
import type { MentionSearchResult, MentionType } from '@/bridge/types';

interface MentionAutocompleteProps {
  query: string;
  onSelect: (result: MentionSearchResult) => void;
  onDismiss: () => void;
}

const categoryLabels: Record<MentionType, string> = {
  file: 'Files', folder: 'Folders', symbol: 'Symbols', tool: 'Tools', skill: 'Skills',
};

const categoryIcons: Record<MentionType, string> = {
  file: '\u{1F4C4}', folder: '\u{1F4C1}', symbol: '#', tool: '\u{1F527}', skill: '\u2728',
};

export const MentionAutocomplete = memo(function MentionAutocomplete({ query, onSelect, onDismiss }: MentionAutocompleteProps) {
  const mentionResults = useChatStore(s => s.mentionResults);
  const [selectedIndex, setSelectedIndex] = useState(0);
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (query.length === 0) {
      (window as any)._searchMentions?.('categories:');
    } else {
      const colonIndex = query.indexOf(':');
      if (colonIndex > 0) {
        const type = query.slice(0, colonIndex);
        const searchQuery = query.slice(colonIndex + 1);
        (window as any)._searchMentions?.(`${type}:${searchQuery}`);
      } else {
        (window as any)._searchMentions?.(`file:${query}`);
      }
    }
  }, [query]);

  useEffect(() => { setSelectedIndex(0); }, [mentionResults]);

  const handleKeyDown = useCallback((e: KeyboardEvent) => {
    switch (e.key) {
      case 'ArrowDown': e.preventDefault(); setSelectedIndex(i => Math.min(i + 1, mentionResults.length - 1)); break;
      case 'ArrowUp': e.preventDefault(); setSelectedIndex(i => Math.max(i - 1, 0)); break;
      case 'Enter': e.preventDefault(); if (mentionResults[selectedIndex]) onSelect(mentionResults[selectedIndex]); break;
      case 'Escape': e.preventDefault(); onDismiss(); break;
    }
  }, [mentionResults, selectedIndex, onSelect, onDismiss]);

  useEffect(() => {
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [handleKeyDown]);

  useEffect(() => {
    const list = listRef.current;
    if (!list) return;
    const selected = list.children[selectedIndex] as HTMLElement | undefined;
    selected?.scrollIntoView({ block: 'nearest' });
  }, [selectedIndex]);

  if (mentionResults.length === 0 && query.length > 0) {
    return (
      <div className="absolute bottom-full left-0 mb-1 w-72 rounded-lg border border-[var(--border)] bg-[var(--bg)] p-3 text-[12px] text-[var(--fg-muted)] shadow-lg">
        No results for &ldquo;{query}&rdquo;
      </div>
    );
  }

  const grouped = mentionResults.reduce<Record<string, MentionSearchResult[]>>((acc, result) => {
    const key = result.type;
    if (!acc[key]) acc[key] = [];
    acc[key]!.push(result);
    return acc;
  }, {});

  let flatIndex = 0;

  return (
    <div className="absolute bottom-full left-0 z-50 mb-1 w-80 max-h-64 overflow-y-auto rounded-lg border border-[var(--border)] bg-[var(--bg)] shadow-lg animate-[message-enter_150ms_ease-out_both]">
      <div ref={listRef}>
        {Object.entries(grouped).map(([type, results]) => (
          <div key={type}>
            <div className="sticky top-0 flex items-center gap-1.5 bg-[var(--bg)] px-3 py-1.5 text-[10px] font-semibold uppercase tracking-wider text-[var(--fg-muted)]">
              <span>{categoryIcons[type as MentionType] ?? '@'}</span>
              <span>{categoryLabels[type as MentionType] ?? type}</span>
            </div>
            {results.map(result => {
              const thisIndex = flatIndex++;
              return (
                <button
                  key={`${result.type}-${result.label}-${thisIndex}`}
                  className={`flex w-full items-center gap-2 px-3 py-1.5 text-left text-[12px] transition-colors duration-100 ${thisIndex === selectedIndex ? 'bg-[var(--hover-overlay-strong)] text-[var(--fg)]' : 'text-[var(--fg-secondary)] hover:bg-[var(--hover-overlay)]'}`}
                  onClick={() => onSelect(result)}
                  onMouseEnter={() => setSelectedIndex(thisIndex)}
                >
                  <span className="flex-1 truncate font-medium">{result.label}</span>
                  {result.description && <span className="flex-shrink-0 text-[10px] text-[var(--fg-muted)]">{result.description}</span>}
                </button>
              );
            })}
          </div>
        ))}
      </div>
    </div>
  );
});
