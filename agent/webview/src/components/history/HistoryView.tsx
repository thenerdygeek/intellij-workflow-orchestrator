import { Search, Plus, MessageSquareDashed, X, CheckSquare, Download, Trash2 } from 'lucide-react';
import { useCallback, useMemo, useState } from 'react';
import { Virtuoso } from 'react-virtuoso';
import { useChatStore } from '../../stores/chatStore';
import { SessionCard } from './SessionCard';
import { SessionContextMenu } from './SessionContextMenu';
import { kotlinBridge } from '../../bridge/jcef-bridge';
import type { HistoryItem } from '../../bridge/types';

// Module-scope Virtuoso slot components (same convention as MessageList):
// inline lambdas would get a fresh identity every render and force Virtuoso
// to remount the Header/Footer slots.
function HistoryHeaderSpacer() {
  return <div className="h-1.5" />;
}
function HistoryFooterSpacer() {
  return <div className="h-3" />;
}
const HISTORY_LIST_COMPONENTS = {
  Header: HistoryHeaderSpacer,
  Footer: HistoryFooterSpacer,
};

export function HistoryView() {
  const historyItems = useChatStore((s) => s.historyItems);
  const historySearch = useChatStore((s) => s.historySearch);
  const setHistorySearch = useChatStore((s) => s.setHistorySearch);
  const setActiveSessionDelegated = useChatStore((s) => s.setActiveSessionDelegated);

  // Bulk selection state
  const [selectionMode, setSelectionMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [confirmingDelete, setConfirmingDelete] = useState(false);

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

  // Reconcile the selection against the visible (filtered) list (bug #14): a
  // selection made before a filter narrowed the list must never delete sessions
  // the user can no longer see. The visible selection is the source of truth for
  // the count, the Select-All toggle, and the delete payload.
  const visibleSelectedIds = useMemo(
    () => filteredItems.filter((item) => selectedIds.has(item.id)).map((item) => item.id),
    [filteredItems, selectedIds],
  );

  // P1-16: useCallback so memoized SessionCard can bail out on unchanged handlers
  const handleResume = useCallback((id: string) => {
    // Stash delegation metadata before handing off to Kotlin so the banner
    // is ready by the time _loadSessionState switches the view to 'chat'.
    const item = historyItems.find((h) => h.id === id);
    setActiveSessionDelegated(item?.delegated ?? null);
    kotlinBridge.showSession(id);
  }, [historyItems, setActiveSessionDelegated]);

  const handleDelete = useCallback((id: string) => {
    kotlinBridge.deleteSession(id);
  }, []);

  const handleToggleFavorite = useCallback((id: string) => {
    kotlinBridge.toggleFavorite(id);
  }, []);

  const handleNewChat = useCallback(() => {
    kotlinBridge.startNewSession();
  }, []);

  const handleExport = useCallback((id: string) => {
    kotlinBridge.exportSession(id);
  }, []);

  const handleExportAll = useCallback(() => {
    kotlinBridge.exportAllSessions();
  }, []);

  // ── Bulk selection handlers ──

  const toggleSelectionMode = () => {
    setConfirmingDelete(false);
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

  // Two-step: first click opens the confirmation; the dialog actually deletes.
  const handleBulkDelete = () => {
    if (visibleSelectedIds.length === 0) return;
    setConfirmingDelete(true);
  };

  const confirmBulkDelete = () => {
    if (visibleSelectedIds.length === 0) {
      setConfirmingDelete(false);
      return;
    }
    kotlinBridge.bulkDeleteSessions(JSON.stringify(visibleSelectedIds));
    setConfirmingDelete(false);
    setSelectionMode(false);
    setSelectedIds(new Set());
  };

  const cancelBulkDelete = () => setConfirmingDelete(false);

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
              {visibleSelectedIds.length} selected
            </span>
            <div className="flex items-center gap-2">
              <button
                onClick={handleBulkDelete}
                disabled={visibleSelectedIds.length === 0}
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
            {visibleSelectedIds.length < filteredItems.length ? (
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
      <div className="flex-1 min-h-0">
        {isEmpty ? (
          <div className="flex flex-col items-center justify-center h-full text-center px-3">
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
          <div className="flex flex-col items-center justify-center h-full text-center px-3">
            <Search size={32} className="text-[var(--fg-muted)] mb-3" />
            <p className="text-sm text-[var(--fg-secondary)]">No matching sessions</p>
            <p className="text-xs text-[var(--fg-muted)] mt-1">
              Try a different search term
            </p>
          </div>
        ) : (
          // P1-16: virtualize with react-virtuoso so large history lists (100s of
          // sessions) don't mount all DOM nodes at once. SessionCard is memoized
          // so only the changed row re-renders when e.g. a card is clicked.
          // Do NOT wrap Virtuoso in a flex container — it must size itself.
          <Virtuoso
            style={{ height: '100%' }}
            totalCount={filteredItems.length}
            computeItemKey={(index) => filteredItems[index]!.id}
            itemContent={(index) => {
              const item = filteredItems[index]!;
              return (
                <div className="px-3 pb-1.5">
                  <SessionCard
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
                </div>
              );
            }}
            components={HISTORY_LIST_COMPONENTS}
          />
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

      {/* Bulk-delete confirmation — destructive + irreversible, so it's gated (bug #14) */}
      {confirmingDelete && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
          onClick={cancelBulkDelete}
        >
          <div
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="bulk-delete-title"
            className="mx-4 w-[320px] rounded-lg border p-4 shadow-xl"
            style={{ borderColor: 'var(--border)', backgroundColor: 'var(--card, var(--bg))' }}
            onClick={(e) => e.stopPropagation()}
          >
            <h3 id="bulk-delete-title" className="text-sm font-semibold mb-1.5" style={{ color: 'var(--fg)' }}>
              Delete {visibleSelectedIds.length} session{visibleSelectedIds.length === 1 ? '' : 's'}?
            </h3>
            <p className="text-xs mb-4" style={{ color: 'var(--fg-muted)' }}>
              This permanently removes the selected session{visibleSelectedIds.length === 1 ? '' : 's'} and can’t be undone.
            </p>
            <div className="flex justify-end gap-2">
              <button
                onClick={cancelBulkDelete}
                className="px-3 py-1.5 rounded-md text-[11px] font-medium
                  text-[var(--fg-muted)] hover:text-[var(--fg)] transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={confirmBulkDelete}
                className="flex items-center gap-1 px-3 py-1.5 rounded-md text-[11px] font-medium
                  bg-[var(--error)] text-white hover:opacity-90 transition-opacity"
              >
                <Trash2 size={11} />
                Delete {visibleSelectedIds.length}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
