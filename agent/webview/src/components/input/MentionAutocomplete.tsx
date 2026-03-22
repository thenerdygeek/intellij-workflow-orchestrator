import { memo, useCallback, useEffect, useRef, useState } from 'react';
import { useChatStore } from '@/stores/chatStore';
import type { MentionSearchResult } from '@/bridge/types';

interface MentionAutocompleteProps {
  query: string;
  onSelect: (result: MentionSearchResult) => void;
  onDismiss: () => void;
}

// ── Per-type visual config ──

const typeConfig: Record<string, { label: string; icon: string; hint: string; color: string; bg: string }> = {
  file:   { label: 'Files',   icon: '📄', hint: 'Search project files',    color: 'var(--accent-read, #3b82f6)',  bg: 'rgba(59,130,246,0.08)' },
  folder: { label: 'Folders', icon: '📁', hint: 'Search folders',          color: 'var(--accent-read, #3b82f6)',  bg: 'rgba(59,130,246,0.08)' },
  symbol: { label: 'Symbols', icon: '#',  hint: 'Search code symbols',     color: '#a78bfa',                       bg: 'rgba(139,92,246,0.08)' },
  tool:   { label: 'Tools',   icon: '🔧', hint: 'Reference agent tools',   color: 'var(--accent-write, #22c55e)', bg: 'rgba(34,197,94,0.08)'  },
  skill:  { label: 'Skills',  icon: '✨', hint: 'Activate a skill',        color: 'var(--accent-edit, #f59e0b)',  bg: 'rgba(251,191,36,0.08)' },
};

// ── Category picker (shown on bare @) ──

function CategoryPicker({ categories, onPick }: {
  categories: MentionSearchResult[];
  onPick: (type: string) => void;
}) {
  return (
    <div className="flex flex-col gap-0.5 p-1.5">
      <div className="px-2 pb-1 text-[10px] font-semibold uppercase tracking-wider" style={{ color: 'var(--fg-muted, #6b7280)' }}>
        Mention
      </div>
      {categories.map(cat => {
        const cfg = typeConfig[cat.type] ?? typeConfig.file!;
        return (
          <button
            key={cat.type}
            onClick={() => onPick(cat.type)}
            className="flex items-center gap-2.5 rounded-lg px-2 py-1.5 text-left transition-colors duration-100 hover:bg-[var(--hover-overlay-strong)]"
            style={{ backgroundColor: 'transparent' }}
          >
            <span
              className="flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-md text-[13px]"
              style={{ backgroundColor: cfg.bg }}
            >
              {cfg.icon}
            </span>
            <div className="flex flex-col min-w-0">
              <span className="text-[12px] font-medium" style={{ color: 'var(--fg, #ccc)' }}>{cfg.label}</span>
              <span className="text-[10px]" style={{ color: 'var(--fg-muted, #6b7280)' }}>{cat.description || cfg.hint}</span>
            </div>
          </button>
        );
      })}
    </div>
  );
}

// ── Search results (shown after category picked or typing) ──

function SearchResults({ results, selectedIndex, onSelect, onMouseEnter }: {
  results: MentionSearchResult[];
  selectedIndex: number;
  onSelect: (r: MentionSearchResult) => void;
  onMouseEnter: (i: number) => void;
}) {
  // Group by type
  const grouped = results.reduce<Record<string, MentionSearchResult[]>>((acc, r) => {
    if (!acc[r.type]) acc[r.type] = [];
    acc[r.type]!.push(r);
    return acc;
  }, {});

  let flatIndex = 0;

  return (
    <div className="flex flex-col">
      {Object.entries(grouped).map(([type, items]) => {
        const cfg = typeConfig[type];
        return (
          <div key={type}>
            {/* Group header */}
            <div
              className="flex items-center gap-1.5 px-3 py-1 text-[10px] font-semibold uppercase tracking-wider"
              style={{ color: 'var(--fg-muted, #6b7280)' }}
            >
              <span>{cfg?.icon ?? '@'}</span>
              <span>{cfg?.label ?? type}</span>
            </div>

            {items.map(result => {
              const thisIndex = flatIndex++;
              const isSelected = thisIndex === selectedIndex;
              return (
                <button
                  key={`${result.type}-${result.label}`}
                  onClick={() => onSelect(result)}
                  onMouseEnter={() => onMouseEnter(thisIndex)}
                  className="flex w-full items-center gap-2 px-3 py-1.5 text-left transition-colors duration-100"
                  style={{
                    backgroundColor: isSelected ? 'var(--hover-overlay-strong, rgba(255,255,255,0.05))' : 'transparent',
                    color: isSelected ? 'var(--fg, #ccc)' : 'var(--fg-secondary, #94a3b8)',
                  }}
                >
                  <span className="flex-1 truncate text-[12px] font-medium">{result.label}</span>
                  {result.description && (
                    <span className="flex-shrink-0 text-[10px]" style={{ color: 'var(--fg-muted, #6b7280)' }}>
                      {result.description}
                    </span>
                  )}
                </button>
              );
            })}
          </div>
        );
      })}
    </div>
  );
}

// ── MentionAutocomplete ──

export const MentionAutocomplete = memo(function MentionAutocomplete({
  query,
  onSelect,
  onDismiss,
}: MentionAutocompleteProps) {
  const mentionResults = useChatStore(s => s.mentionResults);
  const [selectedIndex, setSelectedIndex] = useState(0);
  // Track whether we're in category-pick mode or search mode
  const [activeType, setActiveType] = useState<string | null>(null);
  const listRef = useRef<HTMLDivElement>(null);

  const isCategory = query.length === 0 && activeType === null;

  // Request data whenever query or activeType changes
  useEffect(() => {
    if (query.length === 0 && activeType === null) {
      // Bare @ — show categories
      (window as any)._searchMentions?.('categories:');
    } else if (activeType !== null && query.length === 0) {
      // Category picked, empty query — show all of that type
      (window as any)._searchMentions?.(`${activeType}:`);
    } else {
      // Has a query — search files by default, or the active type
      const type = activeType ?? 'file';
      (window as any)._searchMentions?.(`${type}:${query}`);
    }
  }, [query, activeType]);

  // Reset when query changes externally
  useEffect(() => {
    if (query.length === 0) setActiveType(null);
    setSelectedIndex(0);
  }, [query]);

  useEffect(() => { setSelectedIndex(0); }, [mentionResults]);

  const handleKeyDown = useCallback((e: KeyboardEvent) => {
    switch (e.key) {
      case 'ArrowDown': e.preventDefault(); setSelectedIndex(i => Math.min(i + 1, mentionResults.length - 1)); break;
      case 'ArrowUp':   e.preventDefault(); setSelectedIndex(i => Math.max(i - 1, 0)); break;
      case 'Enter':     e.preventDefault(); if (mentionResults[selectedIndex]) onSelect(mentionResults[selectedIndex]); break;
      case 'Escape':    e.preventDefault(); onDismiss(); break;
      case 'Backspace': if (isCategory) onDismiss(); break;
    }
  }, [mentionResults, selectedIndex, onSelect, onDismiss, isCategory]);

  useEffect(() => {
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [handleKeyDown]);

  // Scroll selected item into view
  useEffect(() => {
    if (!listRef.current) return;
    const items = listRef.current.querySelectorAll('button[data-result]');
    (items[selectedIndex] as HTMLElement | undefined)?.scrollIntoView({ block: 'nearest' });
  }, [selectedIndex]);

  const handleCategoryPick = (type: string) => {
    setActiveType(type);
    setSelectedIndex(0);
  };

  return (
    <div
      className="w-full overflow-hidden rounded-xl border shadow-xl animate-[message-enter_150ms_ease-out_both]"
      style={{
        backgroundColor: 'var(--toolbar-bg, #1e2028)',
        borderColor: 'var(--border, #3f3f46)',
        maxHeight: 280,
        overflowY: 'auto',
      }}
    >
      <div ref={listRef}>
        {isCategory ? (
          <CategoryPicker categories={mentionResults} onPick={handleCategoryPick} />
        ) : mentionResults.length === 0 ? (
          <div className="px-3 py-3 text-[12px]" style={{ color: 'var(--fg-muted, #6b7280)' }}>
            No results{query ? ` for "${query}"` : ''}
          </div>
        ) : (
          <SearchResults
            results={mentionResults}
            selectedIndex={selectedIndex}
            onSelect={onSelect}
            onMouseEnter={setSelectedIndex}
          />
        )}
      </div>
    </div>
  );
});
