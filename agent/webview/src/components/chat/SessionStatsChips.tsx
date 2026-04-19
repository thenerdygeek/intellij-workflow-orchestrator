import { memo } from 'react';
import { useChatStore } from '@/stores/chatStore';
import { kotlinBridge } from '@/bridge/jcef-bridge';

export const SessionStatsChips = memo(function SessionStatsChips() {
  const stats = useChatStore(s => s.sessionStats);
  if (!stats) return null;

  const showCost = stats.estimatedCostUsd !== null && stats.estimatedCostUsd >= 0.005;
  const formatCost = (c: number) => c >= 0.01 ? `≈ $${c.toFixed(2)}` : `≈ $${c.toFixed(3)}`;
  const formatTokens = (n: number) => {
    if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
    if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`;
    return `${n}`;
  };

  return (
    <div className="flex items-center gap-1.5">
      {(stats.tokensIn > 0 || stats.tokensOut > 0) && (
        <span
          className="text-[10px] tabular-nums"
          style={{ color: 'var(--fg-muted, #6b7280)' }}
          title={`Tokens: ${stats.tokensIn.toLocaleString()} in / ${stats.tokensOut.toLocaleString()} out (cumulative this session)`}
        >
          ↕ {formatTokens(stats.tokensIn)}/{formatTokens(stats.tokensOut)}
        </span>
      )}

      {showCost && (
        <button
          onClick={() => kotlinBridge.openInsightsTab()}
          className="text-[10px] font-medium tabular-nums transition-opacity hover:opacity-80"
          style={{ color: 'var(--fg-muted, #6b7280)' }}
          title="Est. cost at public list pricing. Click to open Insights."
        >
          {formatCost(stats.estimatedCostUsd!)}
        </button>
      )}
    </div>
  );
});
