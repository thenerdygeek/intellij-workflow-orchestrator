import { memo } from 'react';
import type { Mention } from '@/bridge/types';

interface ContextChipProps {
  mention: Mention;
  onRemove: () => void;
}

const typeIcons: Record<string, string> = {
  file: '\u{1F4C4}',
  folder: '\u{1F4C1}',
  symbol: '#',
  tool: '\u{1F527}',
  skill: '\u2728',
};

export const ContextChip = memo(function ContextChip({ mention, onRemove }: ContextChipProps) {
  return (
    <span className="inline-flex items-center gap-1 rounded-md border border-[var(--chip-border)] bg-[var(--chip-bg)] px-1.5 py-0.5 text-[11px] text-[var(--fg-secondary)] transition-colors duration-150 hover:border-[var(--accent,#6366f1)]">
      <span className="text-[10px] opacity-60">{typeIcons[mention.type] ?? '@'}</span>
      <span className="max-w-[120px] truncate">{mention.label}</span>
      <button onClick={onRemove} className="ml-0.5 flex h-3.5 w-3.5 items-center justify-center rounded-sm text-[var(--fg-muted)] hover:bg-[var(--hover-overlay-strong)] hover:text-[var(--fg)]" aria-label={`Remove ${mention.label}`}>
        <svg width="8" height="8" viewBox="0 0 8 8" fill="none">
          <path d="M1 1l6 6M7 1l-6 6" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
        </svg>
      </button>
    </span>
  );
});
