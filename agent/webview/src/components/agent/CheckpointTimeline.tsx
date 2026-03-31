import type { CheckpointInfo } from '@/bridge/types';

interface Props {
  checkpoints: CheckpointInfo[];
  onRevert: (id: string) => void;
}

export function CheckpointTimeline({ checkpoints, onRevert }: Props) {
  return (
    <div
      className="border-t px-4 py-2 space-y-1.5 max-h-48 overflow-y-auto"
      style={{ borderColor: 'var(--border)', background: 'var(--bg)' }}
    >
      {checkpoints.map((cp) => (
        <div key={cp.id} className="flex items-center gap-2 text-[11px]">
          <div className="w-1.5 h-1.5 rounded-full flex-shrink-0" style={{ background: 'var(--accent)' }} />
          <span className="flex-1 truncate" style={{ color: 'var(--fg)' }}>{cp.description}</span>
          <span className="flex-shrink-0 font-mono" style={{ color: 'var(--diff-add-fg)' }}>
            +{cp.totalLinesAdded}
          </span>
          <span className="flex-shrink-0 font-mono" style={{ color: 'var(--diff-rem-fg)' }}>
            -{cp.totalLinesRemoved}
          </span>
          <button
            onClick={() => onRevert(cp.id)}
            className="flex-shrink-0 text-[10px] px-2 py-0.5 rounded hover:opacity-80 transition-opacity"
            style={{ background: 'var(--error)', color: 'var(--bg)' }}
          >
            Revert
          </button>
        </div>
      ))}
    </div>
  );
}
