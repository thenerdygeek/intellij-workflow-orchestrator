import { memo } from 'react';
import type { Mention } from '@/bridge/types';

interface ContextChipProps {
  mention: Mention;
  onRemove: () => void;
}

// Color tokens per type — use theme-aware variables where possible,
// fall back to alpha-blended accents that work on both themes.
const typeStyles: Record<string, { color: string; bg: string; border: string; icon: string }> = {
  file:   { color: 'var(--accent-read, #3b82f6)',   bg: 'rgba(59,130,246,0.1)',   border: 'rgba(59,130,246,0.25)',   icon: '📄' },
  folder: { color: 'var(--accent-read, #3b82f6)',   bg: 'rgba(59,130,246,0.08)',  border: 'rgba(59,130,246,0.2)',    icon: '📁' },
  symbol: { color: '#a78bfa',                        bg: 'rgba(139,92,246,0.1)',   border: 'rgba(139,92,246,0.25)',   icon: '#' },
  tool:   { color: 'var(--accent-write, #22c55e)',   bg: 'rgba(34,197,94,0.1)',    border: 'rgba(34,197,94,0.25)',    icon: '🔧' },
  skill:  { color: 'var(--accent-edit, #f59e0b)',    bg: 'rgba(251,191,36,0.1)',   border: 'rgba(251,191,36,0.25)',   icon: '✨' },
};

const fallback = { color: 'var(--fg-secondary)', bg: 'var(--chip-bg)', border: 'var(--chip-border)', icon: '@' };

export const ContextChip = memo(function ContextChip({ mention, onRemove }: ContextChipProps) {
  const style = typeStyles[mention.type] ?? fallback;

  return (
    <span
      className="inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[11px] font-medium transition-colors duration-150"
      style={{ color: style.color, backgroundColor: style.bg, border: `1px solid ${style.border}` }}
    >
      <span className="text-[10px] leading-none opacity-70">{mention.icon ?? style.icon}</span>
      <span className="max-w-[120px] truncate">{mention.label}</span>
      <button
        onClick={onRemove}
        aria-label={`Remove ${mention.label}`}
        className="ml-0.5 flex h-3.5 w-3.5 items-center justify-center rounded-sm opacity-60 transition-opacity duration-100 hover:opacity-100"
        style={{ color: style.color }}
      >
        <svg width="8" height="8" viewBox="0 0 8 8" fill="none">
          <path d="M1 1l6 6M7 1l-6 6" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
        </svg>
      </button>
    </span>
  );
});
