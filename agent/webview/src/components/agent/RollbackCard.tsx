import { useState } from 'react';
import type { RollbackInfo } from '@/bridge/types';

interface Props {
  rollback: RollbackInfo;
}

export function RollbackCard({ rollback }: Props) {
  const [expanded, setExpanded] = useState(false);

  const sourceLabel = rollback.source === 'LLM_TOOL' ? 'Agent' : 'You';
  const scopeLabel = rollback.scope === 'FULL_CHECKPOINT' ? 'All changes' : 'Single file';
  const mechanismLabel = rollback.mechanism === 'LOCAL_HISTORY' ? 'LocalHistory' : 'Git';

  return (
    <div
      className="mx-3 my-2 rounded-lg border px-4 py-3"
      style={{
        borderColor: 'var(--warning, #e5a100)',
        background: 'color-mix(in srgb, var(--warning, #e5a100) 8%, var(--bg))',
      }}
    >
      <div className="flex items-center gap-2 text-[12px]">
        <svg width="14" height="14" viewBox="0 0 16 16" fill="none" className="flex-shrink-0">
          <path
            d="M2 8a6 6 0 1 1 6 6H3m0 0 2-2m-2 2 2 2"
            stroke="var(--warning, #e5a100)"
            strokeWidth="1.5"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
        <span className="font-medium" style={{ color: 'var(--fg)' }}>
          Rolled back to checkpoint
        </span>
        <code
          className="text-[10px] px-1.5 py-0.5 rounded"
          style={{ background: 'var(--code-bg)', color: 'var(--fg-muted)' }}
        >
          {rollback.checkpointId}
        </code>
      </div>

      <p className="mt-1 text-[11px]" style={{ color: 'var(--fg-secondary)' }}>
        {rollback.description}
      </p>

      <div className="flex items-center gap-2 mt-2">
        <span
          className="text-[10px] px-1.5 py-0.5 rounded"
          style={{
            background: sourceLabel === 'Agent' ? 'var(--badge-edit-bg)' : 'var(--badge-read-bg)',
            color: sourceLabel === 'Agent' ? 'var(--badge-edit-fg)' : 'var(--badge-read-fg)',
          }}
        >
          {sourceLabel}
        </span>
        <span
          className="text-[10px] px-1.5 py-0.5 rounded"
          style={{ background: 'var(--code-bg)', color: 'var(--fg-muted)' }}
        >
          {scopeLabel}
        </span>
        <span
          className="text-[10px] px-1.5 py-0.5 rounded"
          style={{ background: 'var(--code-bg)', color: 'var(--fg-muted)' }}
        >
          {mechanismLabel}
        </span>
      </div>

      {rollback.affectedFiles.length > 0 && (
        <div className="mt-2">
          <button
            onClick={() => setExpanded(!expanded)}
            className="text-[10px] hover:underline"
            style={{ color: 'var(--link)' }}
          >
            {rollback.affectedFiles.length} file{rollback.affectedFiles.length !== 1 ? 's' : ''} reverted
            <span className="ml-1">{expanded ? '\u25B2' : '\u25BC'}</span>
          </button>
          {expanded && (
            <ul className="mt-1 space-y-0.5">
              {rollback.affectedFiles.map((f) => (
                <li key={f} className="text-[10px] font-mono pl-3" style={{ color: 'var(--fg-muted)' }}>
                  {f.split('/').pop()}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
