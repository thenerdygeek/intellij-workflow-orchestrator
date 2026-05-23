import { memo } from 'react';

/**
 * Banner shown above the InputBar when a delegated question is in-flight in IDE-B.
 * Informs the user that a question was forwarded to the delegator's agent and that
 * typing an answer here will short-circuit the remote question.
 *
 * Plan 4 spec §5.5.
 */
interface DelegationQuestionBannerProps {
  delegatorRepo: string | undefined;
}

export const DelegationQuestionBanner = memo(function DelegationQuestionBanner({
  delegatorRepo,
}: DelegationQuestionBannerProps) {
  const repoLabel = delegatorRepo ?? 'the delegator';
  return (
    <div
      role="status"
      aria-live="polite"
      className="flex items-center gap-2 px-3 py-1.5 shrink-0 select-none animate-[fade-in_200ms_ease-out]"
      style={{
        borderBottom: '1px solid var(--border, #333)',
        background: 'color-mix(in srgb, var(--warning, #d97706) 10%, var(--bg, #1e1e1e))',
      }}
    >
      {/* Outbox icon */}
      <svg
        width="12"
        height="12"
        viewBox="0 0 16 16"
        fill="none"
        aria-hidden="true"
        style={{ color: 'var(--warning, #d97706)', flexShrink: 0 }}
      >
        <path
          d="M2 10V13H14V10M8 2V9M8 9L5 6M8 9L11 6"
          stroke="currentColor"
          strokeWidth="1.4"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
      <span className="text-[11px]" style={{ color: 'var(--fg-muted, #9ca3af)' }}>
        Question forwarded to{' '}
        <span className="font-medium" style={{ color: 'var(--warning, #d97706)' }}>
          {repoLabel}
        </span>
        . Type an answer here to short-circuit and answer it yourself.
      </span>
    </div>
  );
});
