import { useEffect, useState } from 'react';
import { Check, X } from 'lucide-react';
import type { AggregateDiff } from '@/bridge/types';
import { kotlinBridge } from '@/bridge/jcef-bridge';

interface Props { diff: AggregateDiff | null; }

export function EditStatsBar({ diff }: Props) {
  const [expanded, setExpanded] = useState(false);
  // Inline two-step confirm — native window.confirm() does not work in the JCEF
  // webview (see UserMessageRevertButton). Auto-dismisses after a few seconds.
  const [confirmingRevertAll, setConfirmingRevertAll] = useState(false);
  useEffect(() => {
    if (!confirmingRevertAll) return;
    const id = setTimeout(() => setConfirmingRevertAll(false), 4000);
    return () => clearTimeout(id);
  }, [confirmingRevertAll]);

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
        {confirmingRevertAll ? (
          <span className="flex items-center gap-2" role="group" aria-label="Confirm revert all">
            <span style={{ color: 'var(--fg-secondary)' }}>Revert all changes?</span>
            <button
              onClick={() => { setConfirmingRevertAll(false); window._revertAll?.(); }}
              className="flex items-center gap-1 hover:underline"
              style={{ color: 'var(--link)' }}
              aria-label="Confirm revert all"
              title="Revert all files to their state before the conversation started, remove all chat messages, and restore the first message to your input"
            >
              <Check size={11} /> Confirm
            </button>
            <button
              onClick={() => setConfirmingRevertAll(false)}
              className="flex items-center gap-1 hover:underline"
              style={{ color: 'var(--fg-muted)' }}
              aria-label="Cancel revert all"
            >
              <X size={11} /> Cancel
            </button>
          </span>
        ) : (
          <button
            onClick={() => setConfirmingRevertAll(true)}
            className="hover:underline"
            style={{ color: 'var(--link)' }}
            aria-label="Revert all"
          >
            ⟲ Revert all
          </button>
        )}
      </div>
      {expanded && (
        <div className="px-4 py-1.5 border-t" style={{ borderColor: 'var(--border)' }}>
          {diff.files
            .filter(f => f.status !== 'MODIFIED' || f.added > 0 || f.removed > 0)
            .map(f => (
              <div
                key={f.path}
                data-testid="file-row"
                className="flex items-center gap-3 py-0.5 text-[11px] font-mono"
              >
                <button
                  className="flex-1 truncate text-left hover:underline cursor-pointer"
                  style={{ color: 'var(--fg)' }}
                  onClick={() => kotlinBridge.navigateToFile(f.path)}
                  title={`Open ${f.path} in the IDE`}
                  aria-label={`Open ${f.path}`}
                >
                  {f.path}
                </button>
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
