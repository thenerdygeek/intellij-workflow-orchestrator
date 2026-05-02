import { memo } from 'react';
import type { UiMessageCompactionMarker } from '@/bridge/types';
import { Sparkles, Scissors } from 'lucide-react';

interface CompactionMarkerProps {
  payload: UiMessageCompactionMarker;
}

function formatTime(ts: number): string {
  const d = new Date(ts);
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function formatTokens(n: number): string {
  if (n >= 1000) return `${(n / 1000).toFixed(1)}K`;
  return String(n);
}

/**
 * Horizontal divider message inserted into the chat scrollback after manual
 * compaction completes. Shows the cutoff between pre- and post-compaction
 * history. Messages above it remain visible — but the LLM no longer has
 * direct access to them; it works from a summary plus the recent tail.
 */
export const CompactionMarker = memo(function CompactionMarker({ payload }: CompactionMarkerProps) {
  const tokensSaved = payload.tokensBefore - payload.tokensAfter;
  const messagesRemoved = payload.messagesBefore - payload.messagesAfter;
  const Icon = payload.ranLlmSummary ? Sparkles : Scissors;
  const label = payload.ranLlmSummary ? 'Compacted with LLM summary' : 'Compacted';

  return (
    <div
      className="my-3 flex items-center gap-2 px-2 select-none"
      role="separator"
      aria-label="Context compaction marker"
    >
      <span
        className="flex-1 h-px"
        style={{ background: 'var(--border, #d0d7de)' }}
      />
      <span
        className="flex items-center gap-2 px-3 py-1 rounded-full text-[10px] font-medium uppercase tracking-wider"
        style={{
          background: 'color-mix(in srgb, var(--accent, #6366f1) 10%, transparent)',
          color: 'var(--accent, #6366f1)',
          border: '1px solid color-mix(in srgb, var(--accent, #6366f1) 25%, transparent)',
        }}
        title={
          `${formatTime(payload.ts)} · ` +
          `${payload.messagesBefore} → ${payload.messagesAfter} messages · ` +
          `${formatTokens(payload.tokensBefore)} → ${formatTokens(payload.tokensAfter)} tokens · ` +
          `saved ${formatTokens(tokensSaved)} (${messagesRemoved} messages dropped)\n\n` +
          `Messages above this line remain visible in chat but are no longer in the LLM's working context. ` +
          `The LLM continues from ${payload.ranLlmSummary ? 'a structured summary plus recent tail' : 'the recent tail only'}.`
        }
      >
        <Icon className="size-3" />
        <span>{label}</span>
        <span style={{ opacity: 0.7 }}>·</span>
        <span>−{formatTokens(tokensSaved)} tokens</span>
        <span style={{ opacity: 0.7 }}>·</span>
        <span>−{messagesRemoved} msgs</span>
      </span>
      <span
        className="flex-1 h-px"
        style={{ background: 'var(--border, #d0d7de)' }}
      />
    </div>
  );
});
