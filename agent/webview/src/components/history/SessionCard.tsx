import { Clock, Star, MessageSquare, Trash2, Check, X, CheckSquare2, Square } from 'lucide-react';
import { useState } from 'react';
import type { HistoryItem } from '../../bridge/types';

function formatTimeAgo(ts: number): string {
  const seconds = Math.floor((Date.now() - ts) / 1000);
  if (seconds < 60) return 'just now';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}d ago`;
  const weeks = Math.floor(days / 7);
  return `${weeks}w ago`;
}

function formatTokens(count: number): string {
  if (count >= 1000) return `${(count / 1000).toFixed(1)}K tok`;
  return `${count} tok`;
}

function formatCost(cost: number): string {
  if (cost <= 0) return '';
  return `$${cost.toFixed(2)}`;
}

function formatModelId(modelId?: string | null): string {
  if (!modelId) return '';
  return modelId.replace(/^claude-/, '');
}

interface SessionCardProps {
  item: HistoryItem;
  onResume: (id: string) => void;
  onDelete: (id: string) => void;
  onToggleFavorite: (id: string) => void;
  /** Selection mode props */
  showCheckbox?: boolean;
  selected?: boolean;
  onToggleSelect?: (id: string) => void;
  /** Card-level selection (shows action bar) */
  isActive?: boolean;
  onActivate?: (id: string | null) => void;
  /** Context menu handler */
  onContextMenu?: (e: React.MouseEvent, item: HistoryItem) => void;
}

export function SessionCard({
  item, onResume, onDelete, onToggleFavorite,
  showCheckbox, selected, onToggleSelect,
  isActive, onActivate, onContextMenu,
}: SessionCardProps) {
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  const modelLabel = formatModelId(item.modelId);
  const costLabel = formatCost(item.totalCost);
  const tokenLabel = formatTokens(item.tokensIn + item.tokensOut);

  const handleCardClick = () => {
    if (showCheckbox && onToggleSelect) {
      onToggleSelect(item.id);
    } else {
      // Toggle selection: click to show actions, click again to deselect
      if (isActive) {
        onActivate?.(null);
      } else {
        setConfirmingDelete(false);
        onActivate?.(item.id);
      }
    }
  };

  return (
    <div
      className={`
        rounded-[10px] p-2.5 cursor-pointer transition-colors duration-150
        bg-[var(--toolbar-bg)] border border-[var(--border)]
        ${isActive ? 'border-[var(--accent)] bg-[var(--hover-overlay-strong)]' : ''}
        ${selected ? 'border-[var(--accent)]' : ''}
      `}
      onClick={handleCardClick}
      onContextMenu={(e) => {
        e.preventDefault();
        onContextMenu?.(e, item);
      }}
    >
      {/* Title + Checkbox/Star */}
      <div className="flex items-start gap-2">
        {showCheckbox && (
          <button
            className="shrink-0 mt-0.5 p-0.5"
            onClick={(e) => { e.stopPropagation(); onToggleSelect?.(item.id); }}
          >
            {selected ? (
              <CheckSquare2 size={14} className="text-[var(--accent)]" />
            ) : (
              <Square size={14} className="text-[var(--fg-muted)]" />
            )}
          </button>
        )}
        <p className="flex-1 text-[13px] font-semibold text-[var(--fg)] leading-tight line-clamp-2">
          {item.task}
        </p>
        {!showCheckbox && (
          <button
            className="shrink-0 mt-0.5 p-0.5 hover:opacity-80 transition-opacity"
            onClick={(e) => {
              e.stopPropagation();
              onToggleFavorite(item.id);
            }}
            title={item.isFavorited ? 'Unfavorite' : 'Favorite'}
          >
            <Star
              size={14}
              className={item.isFavorited
                ? 'fill-[var(--warning)] text-[var(--warning)]'
                : 'text-[var(--fg-muted)]'
              }
            />
          </button>
        )}
      </div>

      {/* Metadata row */}
      <div className="flex items-center gap-1.5 mt-1.5 text-[11px] text-[var(--fg-muted)] tracking-wide">
        <Clock size={11} className="shrink-0" />
        <span>{formatTimeAgo(item.ts)}</span>

        {modelLabel && (
          <span className="px-1.5 py-0.5 rounded-full bg-[var(--chip-bg)] text-[var(--fg-secondary)] text-[9px] font-medium uppercase">
            {modelLabel}
          </span>
        )}

        {costLabel && <span>{costLabel}</span>}
        <span>{tokenLabel}</span>
      </div>

      {/* Action bar — shown when card is selected (clicked) */}
      {isActive && !showCheckbox && (
        <div className="flex items-center gap-3 mt-2 pt-1.5 border-t border-[var(--border)] animate-[fade-in_150ms_ease-out]">
          {confirmingDelete ? (
            <>
              <span className="text-[11px] text-[var(--fg-secondary)]">Delete this session?</span>
              <button
                className="flex items-center gap-1 text-[11px] text-[var(--error)] hover:underline font-medium"
                onClick={(e) => { e.stopPropagation(); onDelete(item.id); }}
              >
                <Check size={11} />
                Confirm
              </button>
              <button
                className="flex items-center gap-1 text-[11px] text-[var(--fg-muted)] hover:underline"
                onClick={(e) => { e.stopPropagation(); setConfirmingDelete(false); }}
              >
                <X size={11} />
                Cancel
              </button>
            </>
          ) : (
            <>
              <button
                className="flex items-center gap-1 text-[11px] text-[var(--accent)] hover:underline"
                onClick={(e) => { e.stopPropagation(); onResume(item.id); }}
              >
                <MessageSquare size={11} />
                Resume
              </button>
              <button
                className="flex items-center gap-1 text-[11px] text-[var(--error)] hover:underline"
                onClick={(e) => { e.stopPropagation(); setConfirmingDelete(true); }}
              >
                <Trash2 size={11} />
                Delete
              </button>
            </>
          )}
        </div>
      )}
    </div>
  );
}
