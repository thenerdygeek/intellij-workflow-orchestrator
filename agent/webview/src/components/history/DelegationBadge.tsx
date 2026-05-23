import React from 'react';

interface DelegationBadgeProps {
  delegatorRepo: string;
}

/**
 * Small inline pill shown on a history-list SessionCard when the session
 * was delegated to this IDE from another IDE instance.
 */
export const DelegationBadge: React.FC<DelegationBadgeProps> = ({ delegatorRepo }) => {
  return (
    <span
      className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-full text-[9px] font-medium"
      style={{
        background: 'var(--badge-read-bg, #1a3a5c)',
        color: 'var(--badge-read-fg, #569cd6)',
        whiteSpace: 'nowrap',
        flexShrink: 0,
      }}
      title={`This session was delegated from ${delegatorRepo}`}
    >
      📨 {delegatorRepo}
    </span>
  );
};
