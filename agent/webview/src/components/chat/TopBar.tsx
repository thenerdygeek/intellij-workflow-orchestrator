import { memo, useMemo } from 'react';
import { useChatStore } from '@/stores/chatStore';
import { kotlinBridge } from '@/bridge/jcef-bridge';
import type { UiMessage } from '@/bridge/types';

/**
 * Top bar for the agent chat — shows token budget indicator and new chat button.
 * Token count color-codes based on BudgetEnforcer thresholds:
 *   <80% → accent (normal), 80-88% → warning, 88-97% → error, >97% → error pulse
 */
export const TopBar = memo(function TopBar() {
  const tokenBudget = useChatStore(s => s.tokenBudget);
  const memoryStats = useChatStore(s => s.memoryStats);
  const busy = useChatStore(s => s.busy);
  const sessionTitle = useChatStore(s => s.sessionTitle);
  const debugVisible = useChatStore(s => s.debugLogVisible);
  const debugEntries = useChatStore(s => s.debugLogEntries);
  const hasErrors = debugEntries.some(e => e.level === 'error');
  const pendingApproval = useChatStore(s => s.pendingApproval);
  const skillBanner = useChatStore(s => s.skillBanner);
  const messages = useChatStore(s => s.messages);

  // Count running sub-agents from messages
  const runningAgentCount = useMemo(() => {
    return messages.filter((m: UiMessage) => m.subagentData?.status === 'RUNNING').length;
  }, [messages]);

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

        {/* Memory stats indicator — click to open Memory sub-page */}
        {memoryStats && (memoryStats.coreChars > 0 || memoryStats.archivalCount > 0) && (
          <button
            onClick={() => kotlinBridge.openMemorySettings()}
            className="flex items-center gap-1 px-1.5 py-0.5 rounded text-xs opacity-50 hover:opacity-90 transition-opacity"
            style={{
              color: 'var(--fg-secondary, #9ca3af)',
              border: '1px solid var(--border, #333)',
            }}
            title={`Agent memory: ${memoryStats.coreChars} chars core, ${memoryStats.archivalCount} archival entries. Click to manage.`}
          >
            <span style={{ fontSize: '10px' }}>◆</span>
            <span>{memoryStats.coreChars >= 1000 ? `${(memoryStats.coreChars / 1000).toFixed(1)}K` : memoryStats.coreChars}</span>
            <span style={{ opacity: 0.4 }}>|</span>
            <span>{memoryStats.archivalCount}</span>
          </button>
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

        {/* Active skill indicator */}
        {skillBanner && (
          <div
            className="flex items-center gap-1 rounded-full px-2 py-0.5 animate-[fade-in_200ms_ease-out]"
            style={{ background: 'color-mix(in srgb, var(--accent, #3b82f6) 12%, transparent)' }}
            title={`Skill active: ${skillBanner}`}
          >
            <svg width="10" height="10" viewBox="0 0 16 16" fill="none" style={{ color: 'var(--accent, #3b82f6)' }}>
              <path d="M8 1L10 5.5L15 6.5L11.5 10L12.5 15L8 12.5L3.5 15L4.5 10L1 6.5L6 5.5L8 1Z" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round"/>
            </svg>
            <span className="text-[10px] font-medium" style={{ color: 'var(--accent, #3b82f6)' }}>
              {skillBanner}
            </span>
          </div>
        )}

        {/* Running sub-agents indicator */}
        {runningAgentCount > 0 && (
          <div
            className="flex items-center gap-1 rounded-full px-2 py-0.5 animate-[fade-in_200ms_ease-out]"
            style={{ background: 'color-mix(in srgb, var(--accent-write, #22c55e) 12%, transparent)' }}
            title={`${runningAgentCount} sub-agent${runningAgentCount > 1 ? 's' : ''} running`}
          >
            <span
              className="inline-block size-1.5 rounded-full"
              style={{
                background: 'var(--accent-write, #22c55e)',
                animation: 'pulse 2s ease-in-out infinite',
              }}
            />
            <span className="text-[10px] font-medium" style={{ color: 'var(--accent-write, #22c55e)' }}>
              {runningAgentCount} agent{runningAgentCount > 1 ? 's' : ''}
            </span>
          </div>
        )}
      </div>

      {/* Center: Session title */}
      {sessionTitle && (
        <span
          className="text-[11px] font-medium truncate max-w-[200px]"
          style={{ color: 'var(--fg-secondary, #9ca3af)' }}
          title={sessionTitle}
        >
          {sessionTitle}
        </span>
      )}

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
        onClick={() => kotlinBridge.requestHistory()}
        className="flex items-center rounded px-1 py-0.5 transition-colors hover:bg-[var(--hover-overlay)]"
        style={{ color: 'var(--fg-muted, #6b7280)' }}
        title="Session history"
        aria-label="Session history"
      >
        {/* Clock/history SVG icon */}
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <circle cx="12" cy="12" r="10" />
          <polyline points="12 6 12 12 16 14" />
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
