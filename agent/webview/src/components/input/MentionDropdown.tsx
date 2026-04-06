import { memo, useEffect, useMemo } from 'react';
import type { MentionSearchResult } from '@/bridge/types';
import { useChatStore } from '@/stores/chatStore';
import { useDropdownKeyboard } from '@/hooks/useDropdownKeyboard';
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
 */
function relevanceScore(label: string | undefined, path: string | undefined, query: string): number {
  if (!query || !label) return 0;
  const q = query.toLowerCase();
  const l = label.toLowerCase();
  const nameNoExt = l.replace(/\.[^.]+$/, '').replace(/\/$/, '');
  const p = (path ?? '').toLowerCase();

  if (nameNoExt.startsWith(q)) return 100;

  const fileName = l.includes('/') ? l.split('/').pop()! : l;
  if (fileName.replace(/\.[^.]+$/, '').startsWith(q)) return 95;

  const escaped = q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  if (new RegExp(`(?:^|[_\\-A-Z])${escaped}`, 'i').test(nameNoExt)) return 85;

  if (l.includes(q)) return 75;

  if (p.includes(q)) return 30;

  return 0;
}

interface MentionDropdownProps {
  query: string;
  onSelect: (result: MentionSearchResult) => void;
  onDismiss: () => void;
  selectedIndex: number;
  setSelectedIndex: (i: number) => void;
  listRef: React.RefObject<HTMLDivElement>;
}

export const MentionDropdown = memo(function MentionDropdown({
  query,
  onSelect,
  onDismiss: _onDismiss,
  selectedIndex,
  setSelectedIndex,
  listRef,
}: MentionDropdownProps) {
  const mentionResults = useChatStore(s => s.mentionResults);

  // Request search results from Kotlin
  // Always request `all:` — for empty query, Kotlin returns open editor tabs (active file first)
  useEffect(() => {
    const timer = setTimeout(() => {
      window._searchMentions?.(`all:${query}`);
    }, 200);
    return () => clearTimeout(timer);
  }, [query]);

  const maxPerGroup = query ? 5 : 8;

  // Build a flat ordered list of results for keyboard navigation
  const flatItems = useMemo(() => {
    const scored = mentionResults
      .filter(r => r.type === 'file' || r.type === 'folder' || r.type === 'symbol')
      .map(r => ({ ...r, score: relevanceScore(r.label, r.path, query) }));

    const grouped: Record<string, typeof scored> = {};
    for (const r of scored) {
      (grouped[r.type] ??= []).push(r);
    }
    for (const type in grouped) {
      grouped[type] = grouped[type]!
        .sort((a, b) => b.score - a.score)
        .slice(0, maxPerGroup);
    }

    // Maintain stable group order: file → folder → symbol
    return (['file', 'folder', 'symbol'] as const).flatMap(t => grouped[t] ?? []);
  }, [mentionResults, query, maxPerGroup]);

  // Group boundaries for rendering headings
  const groups = useMemo(() => {
    const result: { type: string; items: typeof flatItems; startIndex: number }[] = [];
    let cursor = 0;
    for (const type of ['file', 'folder', 'symbol'] as const) {
      const items = flatItems.filter(r => r.type === type);
      if (items.length > 0) {
        result.push({ type, items, startIndex: cursor });
        cursor += items.length;
      }
    }
    return result;
  }, [flatItems]);

  const isEmpty = flatItems.length === 0;

  return (
    <div className="absolute bottom-full left-0 mb-1 min-w-[420px] max-w-[560px] w-max z-50">
      <div
        className="rounded-lg overflow-hidden"
        style={{
          backgroundColor: 'var(--surface-elevated, var(--toolbar-bg, var(--popover)))',
          border: '1px solid var(--border)',
          boxShadow: '0 8px 30px rgba(0, 0, 0, 0.5), 0 2px 8px rgba(0, 0, 0, 0.3)',
        }}
      >
        {isEmpty ? (
          <div
            className="text-xs py-4 text-center"
            style={{ color: 'var(--fg-muted)' }}
          >
            No results found.
          </div>
        ) : (
          <div
            ref={listRef}
            className="max-h-64 overflow-y-auto p-1"
            style={{ scrollbarWidth: 'thin' }}
          >
            {/* Empty query: label the section as open tabs */}
            {!query && groups.some(g => g.type === 'file') && (
              <div
                className="px-2 py-1 text-[10px] font-medium uppercase tracking-wider"
                style={{ color: 'var(--fg-muted)' }}
              >
                Open Tabs
              </div>
            )}
            {groups.map(({ type, items, startIndex }) => (
              <div key={type}>
                {/* Group heading (only when searching — open tabs header shown above) */}
                {query && (
                  <div
                    className="px-2 py-1 text-[10px] font-medium uppercase tracking-wider"
                    style={{ color: 'var(--fg-muted)' }}
                  >
                    {typeLabels[type] ?? type}
                  </div>
                )}
                {items.map((r, localIdx) => {
                  const globalIdx = startIndex + localIdx;
                  const highlighted = globalIdx === selectedIndex;
                  const IconComponent = typeIconComponents[r.type] ?? File;
                  const iconColor = typeIconColors[r.type] ?? 'var(--fg-muted)';

                  return (
                    <div
                      key={r.path ?? r.label}
                      data-highlighted={highlighted ? 'true' : undefined}
                      onClick={() => onSelect(r)}
                      onMouseEnter={() => setSelectedIndex(globalIdx)}
                      className="dropdown-item flex items-center gap-2 px-2 py-1.5 rounded text-xs cursor-default"
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
                      <IconComponent
                        className="size-3.5 shrink-0"
                        style={{ color: iconColor }}
                      />
                      <span className="truncate">{r.label}</span>
                      {r.description && (
                        <span
                          className="ml-auto text-[10px] truncate max-w-[280px]"
                          style={{ color: 'var(--fg-muted)' }}
                        >
                          {r.description}
                        </span>
                      )}
                    </div>
                  );
                })}
              </div>
            ))}

            {/* Empty query hint: show searchable types */}
            {!query && (
              <div
                className="flex items-center gap-3 px-2 py-1.5 mt-0.5 border-t text-[10px]"
                style={{ color: 'var(--fg-muted)', borderColor: 'color-mix(in srgb, var(--border) 50%, transparent)' }}
              >
                <span>Type to search</span>
                <span className="flex items-center gap-1"><File className="size-2.5" style={{ color: typeIconColors.file }} /> files</span>
                <span className="flex items-center gap-1"><Folder className="size-2.5" style={{ color: typeIconColors.folder }} /> folders</span>
                <span className="flex items-center gap-1"><Hash className="size-2.5" style={{ color: typeIconColors.symbol }} /> symbols</span>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
});

// ── Re-export the hook wiring helper so InputBar can call it ──

export { useDropdownKeyboard };
export type { MentionDropdownProps };

/**
 * Convenience wrapper: creates keyboard state for MentionDropdown.
 * Call in InputBar, pass the returned props to MentionDropdown.
 */
export function useMentionDropdownKeyboard(
  flatItems: MentionSearchResult[],
  onSelect: (result: MentionSearchResult) => void,
  onDismiss: () => void,
  isOpen: boolean,
) {
  return useDropdownKeyboard({ items: flatItems, onSelect, onDismiss, isOpen });
}
