import { memo, useMemo } from 'react';
import { useChatStore } from '@/stores/chatStore';
import { kotlinBridge } from '@/bridge/jcef-bridge';

/**
 * Top bar for the agent chat — shows token budget indicator and new chat button.
 * Token count color-codes based on BudgetEnforcer thresholds:
 *   <80% → accent (normal), 80-88% → warning, 88-97% → error, >97% → error pulse
 */
export const TopBar = memo(function TopBar() {
  const tokenBudget = useChatStore(s => s.tokenBudget);
  const busy = useChatStore(s => s.busy);
  const debugVisible = useChatStore(s => s.debugLogVisible);
  const debugEntries = useChatStore(s => s.debugLogEntries);
  const hasErrors = debugEntries.some(e => e.level === 'error');
  const pendingApproval = useChatStore(s => s.pendingApproval);

  const { used, max } = tokenBudget;
  const hasTokenData = max > 0;
  const fillPercent = hasTokenData ? Math.min((used / max) * 100, 100) : 0;

  // Color thresholds matching BudgetEnforcer: OK <80%, COMPRESS 80-88%, NUDGE 88-97%, TERMINATE >97%
  const tokenColor = useMemo(() => {
    if (!hasTokenData) return 'var(--fg-muted, #6b7280)';
    if (fillPercent >= 97) return 'var(--error, #ef4444)';
    if (fillPercent >= 88) return 'var(--error, #ef4444)';
    if (fillPercent >= 80) return 'var(--accent-edit, #f59e0b)';
    return 'var(--fg-secondary, #9ca3af)';
  }, [fillPercent, hasTokenData]);

  const tokenLabel = useMemo(() => {
    if (!hasTokenData) return '';
    const formatK = (n: number) => {
      if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
      if (n >= 1_000) return `${(n / 1_000).toFixed(0)}K`;
      return `${n}`;
    };
    return `${formatK(used)} / ${formatK(max)}`;
  }, [used, max, hasTokenData]);

  const statusLabel = useMemo(() => {
    if (!hasTokenData) return '';
    if (fillPercent >= 97) return 'Critical';
    if (fillPercent >= 88) return 'High';
    if (fillPercent >= 80) return 'Compressing';
    return '';
  }, [fillPercent, hasTokenData]);

  return (
    <div
      className="flex items-center justify-between px-3 py-1.5 shrink-0 select-none"
      style={{
        borderBottom: '1px solid var(--border, #333)',
        background: 'var(--toolbar-bg, var(--bg, #1e1e1e))',
      }}
    >
      {/* Left: Token budget + approval indicator */}
      <div className="flex items-center gap-2 min-w-0">
        {hasTokenData ? (
          <div className="flex items-center gap-1.5" title={`Context: ${used.toLocaleString()} / ${max.toLocaleString()} tokens (${fillPercent.toFixed(1)}%)`}>
            {/* Mini progress bar */}
            <div
              className="relative h-1.5 rounded-full overflow-hidden"
              style={{
                width: '48px',
                background: 'var(--hover-overlay, rgba(255,255,255,0.06))',
              }}
            >
              <div
                className={`absolute inset-y-0 left-0 rounded-full transition-all duration-500 ${fillPercent >= 97 ? 'animate-pulse' : ''}`}
                style={{
                  width: `${fillPercent}%`,
                  background: tokenColor,
                }}
              />
            </div>
            {/* Token count text */}
            <span
              className="text-[10px] font-medium tabular-nums whitespace-nowrap"
              style={{ color: tokenColor }}
            >
              {tokenLabel}
            </span>
            {statusLabel && (
              <span
                className="text-[9px] font-medium uppercase tracking-wider"
                style={{ color: tokenColor, opacity: 0.8 }}
              >
                {statusLabel}
              </span>
            )}
          </div>
        ) : (
          <span className="text-[10px]" style={{ color: 'var(--fg-muted, #6b7280)' }}>
            Agent
          </span>
        )}

        {/* Waiting for approval indicator */}
        {pendingApproval && (
          <button
            onClick={() => document.dispatchEvent(new CustomEvent('scroll-to-approval'))}
            className="flex items-center gap-1.5 rounded-md px-2 py-0.5 transition-colors hover:bg-[var(--hover-overlay)]"
            title="Click to scroll to pending approval"
          >
            <span
              className="inline-block size-1.5 rounded-full"
              style={{
                background: 'var(--accent-edit, #f59e0b)',
                animation: 'approval-breathe 2s ease-in-out infinite',
              }}
            />
            <span
              className="text-[10px] font-medium"
              style={{
                color: 'var(--accent-edit, #f59e0b)',
                animation: 'approval-breathe 2s ease-in-out infinite',
              }}
            >
              Waiting for approval
            </span>
          </button>
        )}
      </div>

      {/* Right: Debug toggle + View in Editor + New chat button */}
      <div className="flex items-center gap-1">
      <button
        onClick={() => useChatStore.getState().setDebugLogVisible(!debugVisible)}
        className="flex items-center rounded px-1 py-0.5 transition-colors hover:bg-[var(--hover-overlay)]"
        style={{ color: hasErrors ? 'var(--error, #ef4444)' : debugVisible ? 'var(--accent, #3b82f6)' : 'var(--fg-muted, #6b7280)' }}
        title={debugVisible ? 'Hide debug log' : 'Show debug log'}
        aria-label="Toggle debug log"
      >
        {/* Terminal SVG icon - small */}
        <svg width="12" height="12" viewBox="0 0 16 16" fill="none">
          <path d="M2 3l4 4-4 4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
          <path d="M8 13h6" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
        </svg>
      </button>
      <button
        onClick={() => kotlinBridge.viewInEditor()}
        className="flex items-center rounded px-1 py-0.5 transition-colors hover:bg-[var(--hover-overlay)]"
        style={{ color: 'var(--fg-muted, #6b7280)' }}
        title="Open in editor tab for a larger view"
        aria-label="View in editor"
      >
        {/* Expand/external-link SVG icon */}
        <svg width="12" height="12" viewBox="0 0 16 16" fill="none">
          <path d="M10 2h4v4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
          <path d="M14 2L8 8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
          <path d="M7 3H3a1 1 0 00-1 1v9a1 1 0 001 1h9a1 1 0 001-1V9" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
        </svg>
      </button>
      <button
        onClick={() => kotlinBridge.newChat()}
        disabled={busy}
        className="flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] font-medium transition-colors hover:bg-[var(--hover-overlay,rgba(255,255,255,0.06))] disabled:opacity-40 disabled:cursor-not-allowed"
        style={{ color: 'var(--fg-secondary, #9ca3af)' }}
        title="New conversation (clears chat)"
        aria-label="New conversation"
      >
        {/* Compose/new chat SVG icon */}
        <svg width="12" height="12" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path
            d="M13.5 2.5L7 9H4.5V6.5L11 0L13.5 2.5Z"
            stroke="currentColor"
            strokeWidth="1.2"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
          <path
            d="M2 4H1C0.5 4 0 4.5 0 5V15C0 15.5 0.5 16 1 16H11C11.5 16 12 15.5 12 15V14"
            stroke="currentColor"
            strokeWidth="1.2"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
          <path
            d="M9.5 4L12 1.5"
            stroke="currentColor"
            strokeWidth="1.2"
            strokeLinecap="round"
          />
        </svg>
        New
      </button>
      </div>
    </div>
  );
});
