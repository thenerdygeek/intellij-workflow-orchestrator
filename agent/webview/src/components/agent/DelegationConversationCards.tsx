import { memo } from 'react';
import type { DelegationCardData } from '@/bridge/types';
import { formatElapsedSeconds } from '@/lib/time';

/**
 * IDE-B delegation CONVERSATION narration cards. These render the delegation
 * conversation framing on the panel of the IDE that RECEIVED the delegated task —
 * distinct from the agent's actual work (streaming text + tool cards + sub-agents).
 *
 * CRITICAL NAMING RULE: the "other side" is always the delegator's REPO NAME
 * (`delegatorRepo`), never "IDE-A" / "IDE-B".
 *
 * Direction is conveyed by an arrow glyph + the shared delegation accent color
 * (`--badge-read-fg`, the same accent the top-bar DelegationBanner uses):
 *   (b) ↗ Asked {repo}          — DelegationQuestionCard (waiting ⏳ → answered)
 *   (c) ↘ {repo} answered       — DelegationAnswerCard
 *   (d) ✓ Result sent to {repo} — DelegationResultCard (status-colored)
 */

const ACCENT = 'var(--badge-read-fg, #569cd6)';

function cardShellStyle(borderColor: string): React.CSSProperties {
  return {
    border: `1px solid ${borderColor}`,
    borderRadius: 8,
    padding: '8px 10px',
    margin: '4px 0',
    background: 'color-mix(in srgb, var(--badge-read-fg, #569cd6) 6%, var(--bg, #1e1e1e))',
    fontSize: 12,
  };
}

function headerStyle(): React.CSSProperties {
  return {
    display: 'flex',
    alignItems: 'center',
    gap: 6,
    fontWeight: 600,
    color: ACCENT,
    marginBottom: 4,
  };
}

// ── (b) Asked {repo} ─────────────────────────────────────────────────────────

export const DelegationQuestionCard = memo(function DelegationQuestionCard({
  data,
}: {
  data: DelegationCardData;
}) {
  const answered = data.answered === true;
  return (
    <div role="group" aria-label="delegation-question" style={cardShellStyle(ACCENT)}>
      <div style={headerStyle()}>
        <span aria-hidden>↗</span>
        <span>Asked {data.delegatorRepo}</span>
        {answered ? (
          <span
            style={{
              marginLeft: 'auto',
              fontWeight: 500,
              color: 'var(--success, #22c55e)',
              fontSize: 11,
            }}
          >
            ✓ answered
          </span>
        ) : (
          <span
            style={{
              marginLeft: 'auto',
              fontWeight: 500,
              color: 'var(--warning, #d8a657)',
              fontSize: 11,
            }}
          >
            ⏳ waiting
          </span>
        )}
      </div>
      {data.text ? (
        <div style={{ color: 'var(--fg, #ccc)', whiteSpace: 'pre-wrap' }}>{data.text}</div>
      ) : null}
      {data.options && data.options.length > 0 ? (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginTop: 4 }}>
          {data.options.map((opt, i) => (
            <span
              key={i}
              style={{
                border: '1px solid var(--border, #333)',
                borderRadius: 4,
                padding: '1px 6px',
                fontSize: 11,
                color: 'var(--fg-secondary, #aaa)',
              }}
            >
              {opt}
            </span>
          ))}
        </div>
      ) : null}
    </div>
  );
});

// ── (c) {repo} answered ──────────────────────────────────────────────────────

export const DelegationAnswerCard = memo(function DelegationAnswerCard({
  data,
}: {
  data: DelegationCardData;
}) {
  return (
    <div
      role="group"
      aria-label="delegation-answer"
      // Visually pairs beneath the matching ASKED card: indented + same accent.
      style={{ ...cardShellStyle('var(--border, #333)'), marginLeft: 12 }}
    >
      <div style={{ ...headerStyle(), color: 'var(--fg-secondary, #aaa)' }}>
        <span aria-hidden>↘</span>
        <span>{data.delegatorRepo} answered</span>
      </div>
      {data.text ? (
        <div style={{ color: 'var(--fg, #ccc)', whiteSpace: 'pre-wrap' }}>{data.text}</div>
      ) : null}
    </div>
  );
});

// ── (d) Result sent to {repo} ────────────────────────────────────────────────

function statusColor(status?: string | null): string {
  switch ((status ?? '').toUpperCase()) {
    case 'COMPLETED':
      return 'var(--success, #22c55e)';
    case 'FAILED':
    case 'REJECTED':
      return 'var(--error, #ef4444)';
    case 'CANCELED':
    case 'CANCELLED':
      return 'var(--warning, #d8a657)';
    default:
      return ACCENT;
  }
}

export const DelegationResultCard = memo(function DelegationResultCard({
  data,
}: {
  data: DelegationCardData;
}) {
  const color = statusColor(data.resultStatus);
  const isError = ['FAILED', 'REJECTED'].includes((data.resultStatus ?? '').toUpperCase());
  return (
    <div role="group" aria-label="delegation-result" style={cardShellStyle(color)}>
      <div style={{ ...headerStyle(), color }}>
        <span aria-hidden>✓</span>
        <span>Result sent to {data.delegatorRepo}</span>
        <span
          style={{
            marginLeft: 'auto',
            fontWeight: 600,
            color,
            fontSize: 11,
          }}
        >
          {data.resultStatus ?? 'DONE'}
          {data.durationSeconds ? ` · ${formatElapsedSeconds(data.durationSeconds)}` : ''}
        </span>
      </div>
      {data.text ? (
        <div style={{ color: 'var(--fg, #ccc)', whiteSpace: 'pre-wrap' }}>{data.text}</div>
      ) : null}
      {isError && data.reason ? (
        <div style={{ color: 'var(--error, #ef4444)', marginTop: 2, fontSize: 11 }}>{data.reason}</div>
      ) : null}
    </div>
  );
});
