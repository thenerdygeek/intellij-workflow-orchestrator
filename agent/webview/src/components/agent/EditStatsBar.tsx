import { useState } from 'react';
import type { AggregateDiff } from '@/bridge/types';

interface Props { diff: AggregateDiff | null; }

export function EditStatsBar({ diff }: Props) {
  const [expanded, setExpanded] = useState(false);
  if (!diff || (diff.totalAdded === 0 && diff.totalRemoved === 0 && diff.files.length === 0)) return null;

  return (
    <div className="border-t" style={{ borderColor: 'var(--border)', background: 'var(--toolbar-bg)' }}>
      <div className="flex items-center gap-3 px-4 py-1.5 text-[11px] font-mono">
        <span style={{ color: 'var(--diff-add-fg, #b5cea8)' }}>+{diff.totalAdded}</span>
        <span style={{ color: 'var(--diff-rem-fg, #f4a5a5)' }}>-{diff.totalRemoved}</span>
        <span className="w-px h-3" style={{ background: 'var(--border)' }} />
        <span style={{ color: 'var(--fg-muted)' }}>
          {diff.files.length} file{diff.files.length !== 1 ? 's' : ''}
        </span>
        <button
          aria-label={expanded ? 'collapse' : 'expand'}
          onClick={() => setExpanded(e => !e)}
          className="hover:underline"
          style={{ color: 'var(--link)' }}
        >
          {expanded ? '▴' : '▾'}
        </button>
        <div className="flex-1" />
        <button
          onClick={() => window._revertAll?.()}
          className="hover:underline"
          style={{ color: 'var(--link)' }}
        >
          ⟲ Revert all
        </button>
      </div>
      {expanded && (
        <div className="px-4 py-1.5 border-t" style={{ borderColor: 'var(--border)' }}>
          {diff.files.map(f => (
            <div
              key={f.path}
              data-testid="file-row"
              className="flex items-center gap-3 py-0.5 text-[11px] font-mono"
            >
              <span className="flex-1 truncate" style={{ color: 'var(--fg)' }}>{f.path}</span>
              <span style={{ color: 'var(--diff-add-fg, #b5cea8)' }}>+{f.added}</span>
              <span style={{ color: 'var(--diff-rem-fg, #f4a5a5)' }}>-{f.removed}</span>
              <button
                aria-label="Revert this file"
                onClick={() => window._revertFileToBaseline?.(f.path)}
                className="hover:underline"
                style={{ color: 'var(--link)' }}
              >
                ⟲
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
