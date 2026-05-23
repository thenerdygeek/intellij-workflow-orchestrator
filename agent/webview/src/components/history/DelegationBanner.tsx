import { memo } from 'react';
import { useChatStore } from '@/stores/chatStore';

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

/**
 * Banner rendered at the top of the chat view when the active session was
 * delegated from another IDE instance. Shows delegator IDE, repo, start time,
 * and close status.
 */
export const DelegationBanner = memo(function DelegationBanner() {
  const delegated = useChatStore((s) => s.activeSessionDelegated);

  if (!delegated) return null;

  const startedTime = formatTimeAgo(delegated.startedAt);
  const closedSegment = delegated.closedAt
    ? ` · Closed ${formatTimeAgo(delegated.closedAt)}${delegated.closeReason ? ` (${delegated.closeReason})` : ''}`
    : ' · Active';

  return (
    <div
      role="status"
      aria-live="polite"
      className="flex items-center gap-2 px-3 py-1 shrink-0 select-none animate-[fade-in_200ms_ease-out]"
      style={{
        borderBottom: '1px solid var(--border, #333)',
        background: 'color-mix(in srgb, var(--badge-read-fg, #569cd6) 8%, var(--bg, #1e1e1e))',
        fontSize: 11,
      }}
    >
      <span style={{ flexShrink: 0 }}>📨</span>
      <span style={{ color: 'var(--fg-secondary, #aaa)' }}>
        Delegated by{' '}
        <span style={{ color: 'var(--badge-read-fg, #569cd6)', fontWeight: 600 }}>
          {delegated.delegatorIde}
        </span>{' '}
        from{' '}
        <span style={{ color: 'var(--fg, #ccc)', fontWeight: 600 }}>
          {delegated.delegatorRepo}
        </span>
        {' · '}Started {startedTime}{closedSegment}
      </span>
    </div>
  );
});
