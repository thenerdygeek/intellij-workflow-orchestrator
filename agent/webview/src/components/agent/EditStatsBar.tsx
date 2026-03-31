import { useState } from 'react';
import type { EditStats, CheckpointInfo } from '@/bridge/types';
import { CheckpointTimeline } from './CheckpointTimeline';

interface EditStatsBarProps {
  stats: EditStats | null;
  checkpoints: CheckpointInfo[];
}

export function EditStatsBar({ stats, checkpoints }: EditStatsBarProps) {
  const [showCheckpoints, setShowCheckpoints] = useState(false);

  if (!stats || (stats.totalLinesAdded === 0 && stats.totalLinesRemoved === 0)) return null;

  return (
    <div>
      <div
        className="flex items-center gap-3 px-4 py-1.5 text-[11px] font-mono border-t"
        style={{ borderColor: 'var(--border)', background: 'var(--toolbar-bg)' }}
      >
        <span style={{ color: 'var(--diff-add-fg, #b5cea8)' }}>+{stats.totalLinesAdded}</span>
        <span style={{ color: 'var(--diff-rem-fg, #f4a5a5)' }}>-{stats.totalLinesRemoved}</span>
        <span className="w-px h-3" style={{ background: 'var(--border)' }} />
        <span style={{ color: 'var(--fg-muted)' }}>{stats.filesModified} file{stats.filesModified !== 1 ? 's' : ''}</span>
        {checkpoints.length > 0 && (
          <>
            <span className="w-px h-3" style={{ background: 'var(--border)' }} />
            <button
              onClick={() => setShowCheckpoints(!showCheckpoints)}
              className="hover:underline"
              style={{ color: 'var(--link)' }}
            >
              {checkpoints.length} checkpoint{checkpoints.length !== 1 ? 's' : ''}
              <span className="ml-1 text-[9px]">{showCheckpoints ? '\u25B2' : '\u25BC'}</span>
            </button>
          </>
        )}
      </div>
      {showCheckpoints && (
        <CheckpointTimeline
          checkpoints={checkpoints}
          onRevert={(id) => (window as any)._revertCheckpoint?.(id)}
        />
      )}
    </div>
  );
}
