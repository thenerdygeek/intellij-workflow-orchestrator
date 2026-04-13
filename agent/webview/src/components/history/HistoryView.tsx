import { Search, Plus, MessageSquareDashed, X, CheckSquare, Download, Trash2 } from 'lucide-react';
import { useCallback, useMemo, useState } from 'react';
import { useChatStore } from '../../stores/chatStore';
import { SessionCard } from './SessionCard';
import { SessionContextMenu } from './SessionContextMenu';
import { kotlinBridge } from '../../bridge/jcef-bridge';
import type { HistoryItem } from '../../bridge/types';

export function HistoryView() {
  const historyItems = useChatStore((s) => s.historyItems);
  const historySearch = useChatStore((s) => s.historySearch);
  const setHistorySearch = useChatStore((s) => s.setHistorySearch);

  // Bulk selection state
  const [selectionMode, setSelectionMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());

  // Active card state (shows action bar on click)
  const [activeCardId, setActiveCardId] = useState<string | null>(null);

  // Context menu state
  const [contextMenu, setContextMenu] = useState<{
    x: number; y: number; item: HistoryItem;
  } | null>(null);

  const filteredItems = useMemo(() => {
    if (!historySearch.trim()) return historyItems;
    const query = historySearch.toLowerCase();
    return historyItems.filter((item) =>
      item.task.toLowerCase().includes(query)
    );
  }, [historyItems, historySearch]);

  const handleResume = (id: string) => {
    kotlinBridge.showSession(id);
  };

  const handleDelete = (id: string) => {
    kotlinBridge.deleteSession(id);
  };

  const handleToggleFavorite = (id: string) => {
    kotlinBridge.toggleFavorite(id);
  };

  const handleNewChat = () => {
    kotlinBridge.startNewSession();
  };

  const handleExport = (id: string) => {
    kotlinBridge.exportSession(id);
  };

  const handleExportAll = () => {
    kotlinBridge.exportAllSessions();
  };

  // ── Bulk selection handlers ──

  const toggleSelectionMode = () => {
    if (selectionMode) {
      setSelectionMode(false);
      setSelectedIds(new Set());
    } else {
      setSelectionMode(true);
      setSelectedIds(new Set());
    }
  };

  const handleToggleSelect = useCallback((id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  const handleSelectAll = () => {
    setSelectedIds(new Set(filteredItems.map((item) => item.id)));
  };

  const handleDeselectAll = () => {
    setSelectedIds(new Set());
  };

  const handleBulkDelete = () => {
    if (selectedIds.size === 0) return;
    kotlinBridge.bulkDeleteSessions(JSON.stringify(Array.from(selectedIds)));
    setSelectionMode(false);
    setSelectedIds(new Set());
  };

  // ── Context menu handler ──

  const handleContextMenu = useCallback((e: React.MouseEvent, item: HistoryItem) => {
    setContextMenu({ x: e.clientX, y: e.clientY, item });
  }, []);

  const closeContextMenu = useCallback(() => {
    setContextMenu(null);
  }, []);

  const isEmpty = historyItems.length === 0;

  return (
    <div className="flex flex-col h-full bg-[var(--bg)]">
      {/* Header */}
      <div className="flex items-center justify-between px-3 py-2 shrink-0">
        {selectionMode ? (
          <>
            <span className="text-xs font-medium text-[var(--fg-secondary)]">
              {selectedIds.size} selected
            </span>
            <div className="flex items-center gap-2">
              <button
                onClick={handleBulkDelete}
                disabled={selectedIds.size === 0}
                className="flex items-center gap-1 px-2 py-1 rounded-md text-[11px] font-medium
                  bg-[var(--error)] text-white hover:opacity-90 transition-opacity
                  disabled:opacity-40 disabled:cursor-not-allowed"
              >
                <Trash2 size={11} />
                Delete Selected
              </button>
              <button
                onClick={toggleSelectionMode}
                className="px-2 py-1 rounded-md text-[11px] font-medium
                  text-[var(--fg-muted)] hover:text-[var(--fg)] transition-colors"
              >
                Cancel
              </button>
            </div>
          </>
        ) : (
          <>
            <h2 className="text-sm font-semibold text-[var(--fg)]">History</h2>
            <div className="flex items-center gap-1.5">
              {!isEmpty && (
                <>
                  <button
                    onClick={handleExportAll}
                    className="flex items-center gap-1 px-2 py-1 rounded-md text-[11px] font-medium
                      text-[var(--fg-muted)] hover:text-[var(--fg)] transition-colors"
                    title="Export all sessions to clipboard"
                  >
                    <Download size={11} />
                    Export All
                  </button>
                  <button
                    onClick={toggleSelectionMode}
                    className="flex items-center gap-1 px-2 py-1 rounded-md text-[11px] font-medium
                      text-[var(--fg-muted)] hover:text-[var(--fg)] transition-colors"
                    title="Select sessions for bulk actions"
                  >
                    <CheckSquare size={11} />
                    Select
                  </button>
                </>
              )}
              <button
                onClick={handleNewChat}
                className="flex items-center gap-1 px-2.5 py-1 rounded-md text-xs font-medium
                  bg-[var(--accent)] text-white hover:opacity-90 transition-opacity"
              >
                <Plus size={12} />
                New Chat
              </button>
            </div>
          </>
        )}
      </div>

      {/* Select All / Deselect All bar (only in selection mode) */}
      {selectionMode && filteredItems.length > 0 && (
        <div className="px-3 pb-1.5 shrink-0">
          <div className="flex items-center gap-2 text-[11px]">
            {selectedIds.size < filteredItems.length ? (
              <button
                onClick={handleSelectAll}
                className="text-[var(--accent)] hover:underline"
              >
                Select All
              </button>
            ) : (
              <button
                onClick={handleDeselectAll}
                className="text-[var(--accent)] hover:underline"
              >
                Deselect All
              </button>
            )}
          </div>
        </div>
      )}

      {/* Search bar (only when there are items and not in selection mode) */}
      {!isEmpty && !selectionMode && (
        <div className="px-3 pb-2 shrink-0">
          <div className="relative">
            <Search size={14} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[var(--fg-muted)]" />
            <input
              type="text"
              placeholder="Search sessions..."
              value={historySearch}
              onChange={(e) => setHistorySearch(e.target.value)}
              className="w-full h-8 pl-8 pr-8 rounded-md text-xs
                bg-[var(--input-bg)] text-[var(--fg)] border border-[var(--input-border)]
                placeholder:text-[var(--fg-muted)]
                focus:outline-none focus:border-[var(--accent)] focus:ring-1 focus:ring-[var(--accent)]/20
                transition-colors"
            />
            {historySearch && (
              <button
                onClick={() => setHistorySearch('')}
                className="absolute right-2.5 top-1/2 -translate-y-1/2 text-[var(--fg-muted)] hover:text-[var(--fg)]"
              >
                <X size={14} />
              </button>
            )}
          </div>
        </div>
      )}

      {/* Content area */}
      <div className="flex-1 overflow-y-auto px-3 pb-3">
        {isEmpty ? (
          <div className="flex flex-col items-center justify-center h-full text-center">
            <MessageSquareDashed size={48} className="text-[var(--fg-muted)] mb-3" />
            <p className="text-base font-semibold text-[var(--fg-secondary)] mb-1">No sessions yet</p>
            <p className="text-xs text-[var(--fg-muted)] max-w-[240px] mb-4">
              Start a conversation with the AI agent to see your session history here.
            </p>
            <button
              onClick={handleNewChat}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium
                bg-[var(--accent)] text-white hover:opacity-90 transition-opacity"
            >
              <Plus size={12} />
              Start New Chat
            </button>
          </div>
        ) : filteredItems.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full text-center">
            <Search size={32} className="text-[var(--fg-muted)] mb-3" />
            <p className="text-sm text-[var(--fg-secondary)]">No matching sessions</p>
            <p className="text-xs text-[var(--fg-muted)] mt-1">
              Try a different search term
            </p>
          </div>
        ) : (
          <div className="flex flex-col gap-1.5">
            {filteredItems.map((item) => (
              <SessionCard
                key={item.id}
                item={item}
                onResume={handleResume}
                onDelete={handleDelete}
                onToggleFavorite={handleToggleFavorite}
                showCheckbox={selectionMode}
                selected={selectedIds.has(item.id)}
                onToggleSelect={handleToggleSelect}
                isActive={activeCardId === item.id}
                onActivate={setActiveCardId}
                onContextMenu={handleContextMenu}
              />
            ))}
          </div>
        )}
      </div>

      {/* Context menu */}
      {contextMenu && (
        <SessionContextMenu
          x={contextMenu.x}
          y={contextMenu.y}
          item={contextMenu.item}
          onResume={handleResume}
          onToggleFavorite={handleToggleFavorite}
          onExport={handleExport}
          onDelete={handleDelete}
          onClose={closeContextMenu}
        />
      )}
    </div>
  );
}
