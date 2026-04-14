import { memo, useState, useRef, useEffect, useCallback } from 'react';
import type { SubAgentState } from '@/bridge/types';
import { AgentMessage } from '@/components/chat/AgentMessage';
import { ToolCallChain } from '@/components/agent/ToolCallChain';
import { CopyButton } from '@/components/ui/copy-button';
import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';
import { Bot, Loader2, XCircle, AlertCircle, Check, ChevronDown, Square } from 'lucide-react';
import { useChatStore } from '@/stores/chatStore';
import { useStickToBottomContext } from 'use-stick-to-bottom';

// ── Helpers ──

function formatElapsed(ms: number): string {
  const totalSec = Math.floor(ms / 1000);
  if (totalSec < 60) return `${totalSec}s`;
  const m = Math.floor(totalSec / 60);
  const s = totalSec % 60;
  return `${m}m ${s}s`;
}

function extractType(label: string): string | null {
  // Label format from Kotlin: "description (type)"
  const match = label.match(/\(([^)]+)\)\s*$/);
  return match?.[1] ?? null;
}

function extractName(label: string): string {
  return label.replace(/\s*\([^)]+\)\s*$/, '');
}

// ── Ring color by status ──

function getRingStyle(status: SubAgentState['status']): React.CSSProperties {
  switch (status) {
    case 'RUNNING':
      return { boxShadow: `0 0 0 1px var(--accent, #6366f1)` };
    case 'COMPLETED':
      return { boxShadow: `0 0 0 1px var(--success, #22c55e)` };
    case 'ERROR':
      return { boxShadow: `0 0 0 1px var(--error, #ef4444)` };
    case 'KILLED':
      return { boxShadow: `0 0 0 1px var(--fg-muted, #666)` };
  }
}

function StatusIndicator({ status }: { status: SubAgentState['status'] }) {
  switch (status) {
    case 'RUNNING':
      return <Loader2 className="size-3 animate-spin" style={{ color: 'var(--accent)' }} />;
    case 'COMPLETED':
      return <Check className="size-3" style={{ color: 'var(--success)' }} />;
    case 'ERROR':
      return <AlertCircle className="size-3" style={{ color: 'var(--error)' }} />;
    case 'KILLED':
      return <XCircle className="size-3" style={{ color: 'var(--fg-muted)' }} />;
  }
}

// ── Live Timer Hook ──

function useLiveTimer(startedAt: number, isRunning: boolean): string {
  const [elapsed, setElapsed] = useState(() => Date.now() - startedAt);

  useEffect(() => {
    if (!isRunning) {
      // Freeze at final value
      setElapsed(Date.now() - startedAt);
      return;
    }
    const tick = () => setElapsed(Date.now() - startedAt);
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [startedAt, isRunning]);

  return formatElapsed(elapsed);
}

// ── Component ──

interface SubAgentViewProps {
  subAgent: SubAgentState;
}

export const SubAgentView = memo(function SubAgentView({ subAgent }: SubAgentViewProps) {
  const killSubAgent = useChatStore((state) => state.killSubAgent);
  const isRunning = subAgent.status === 'RUNNING';
  const [isOpen, setIsOpen] = useState(true);

  const agentType = extractType(subAgent.label);
  const agentName = extractName(subAgent.label);
  const elapsedStr = useLiveTimer(subAgent.startedAt, isRunning);

  // ── Outer chat scroll context ──
  // SubAgentView has max-h-[400px] on its body, so once full the outer StickToBottom
  // ResizeObserver sees no further height changes. We reach up and nudge it ourselves
  // so the outer chat keeps scrolling to show the sub-agent while it is active.
  const { scrollToBottom, isAtBottom: outerIsAtBottom } = useStickToBottomContext();

  // ── Internal scroll (within the 400px body) ──
  const scrollRef = useRef<HTMLDivElement>(null);
  const userScrolledUp = useRef(false);

  const handleScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    const isAtBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 30;
    userScrolledUp.current = !isAtBottom;
  }, []);

  useEffect(() => {
    // Scroll the inner body to the latest content
    if (!userScrolledUp.current && scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
    // Also nudge the outer StickToBottom — once the card's body hits max-h the outer
    // ResizeObserver sees no resize, so we trigger the scroll manually.
    if (outerIsAtBottom) {
      scrollToBottom('instant');
    }
  }, [subAgent.messages.length, subAgent.activeToolChain?.length, outerIsAtBottom, scrollToBottom]);

  const handleKill = useCallback((e: React.MouseEvent) => {
    e.stopPropagation(); // Don't toggle collapse
    if (isRunning) {
      killSubAgent(subAgent.agentId);
    }
  }, [isRunning, killSubAgent, subAgent.agentId]);

  const toggleOpen = useCallback(() => {
    setIsOpen(prev => !prev);
  }, []);

  return (
    <div
      className="my-2 animate-message-enter rounded-lg overflow-hidden"
      style={getRingStyle(subAgent.status)}
    >
      {/* ── Header ── */}
      <button
        type="button"
        onClick={toggleOpen}
        className={cn(
          'w-full flex items-center gap-2 px-3 py-2 cursor-pointer',
          'transition-colors hover:brightness-110',
        )}
        style={{ backgroundColor: 'var(--tool-bg, rgba(0,0,0,0.15))' }}
      >
        {/* Bot icon */}
        <div
          className="flex items-center justify-center size-5 rounded-full shrink-0"
          style={{
            backgroundColor: 'color-mix(in srgb, var(--accent, #6366f1) 15%, transparent)',
            border: '1px solid color-mix(in srgb, var(--accent, #6366f1) 25%, transparent)',
          }}
        >
          <Bot className="size-3" style={{ color: 'var(--accent)' }} />
        </div>

        {/* SUB-AGENT badge */}
        <Badge
          variant="secondary"
          className="rounded px-1 py-0 text-[9px] font-semibold uppercase tracking-wider border-0 shrink-0"
          style={{
            backgroundColor: 'var(--badge-write-bg, #3b2e1a)',
            color: 'var(--badge-write-fg, #e8a84c)',
          }}
        >
          SUB-AGENT
        </Badge>

        {/* Agent name */}
        <span
          className="text-[12px] font-semibold truncate min-w-0"
          style={{ color: 'var(--fg)' }}
        >
          {agentName}
        </span>

        {/* Agent type chip */}
        {agentType && (
          <Badge
            variant="secondary"
            className="rounded px-1.5 py-0 text-[9px] font-medium tracking-wide border-0 shrink-0"
            style={{
              backgroundColor: 'var(--chip-bg, #2a2a2a)',
              color: 'var(--fg-muted)',
            }}
          >
            {agentType}
          </Badge>
        )}

        {/* Spacer */}
        <span className="flex-1" />

        {/* Live timer */}
        <span
          className="text-[10px] font-mono tabular-nums shrink-0"
          style={{ color: 'var(--fg-muted)' }}
        >
          {elapsedStr}
        </span>

        {/* Status indicator */}
        <StatusIndicator status={subAgent.status} />

        {/* Iteration + tokens */}
        <span
          className="text-[10px] font-mono tabular-nums shrink-0 flex items-center gap-1"
          style={{ color: 'var(--fg-muted)' }}
        >
          {isRunning && (
            <>
              <span>iter {subAgent.iteration}</span>
              <span className="opacity-40">·</span>
            </>
          )}
          {(subAgent.tokensUsed || 0).toLocaleString()} tkn
        </span>

        {/* Kill button (only when running) */}
        {isRunning && (
          <button
            type="button"
            onClick={handleKill}
            title="Kill Sub-Agent"
            className="flex items-center justify-center p-0.5 rounded-sm shrink-0 transition-colors"
            style={{ color: 'var(--error, #ef4444)' }}
          >
            <Square className="size-3" />
          </button>
        )}

        {/* Chevron (collapse/expand) */}
        <ChevronDown
          className={cn(
            'size-3.5 shrink-0 transition-transform duration-200',
            isOpen && 'rotate-180',
          )}
          style={{ color: 'var(--fg-muted)' }}
        />
      </button>

      {/* ── Indeterminate progress bar ── */}
      {isRunning && (
        <div
          className="h-[2px] w-full overflow-hidden"
          style={{ backgroundColor: 'var(--border, rgba(255,255,255,0.08))' }}
        >
          <div
            className="h-full w-1/3"
            style={{
              backgroundColor: 'var(--accent, #6366f1)',
              animation: 'indeterminate 1.8s linear infinite',
            }}
          />
        </div>
      )}

      {/* ── Collapsible body ── */}
      {isOpen && (
        <div
          ref={scrollRef}
          onScroll={handleScroll}
          className="sub-agent-scroll-container max-h-[400px] overflow-y-auto overflow-x-hidden p-3"
          style={{ backgroundColor: 'var(--tool-bg, rgba(0,0,0,0.08))' }}
        >
          <div className="space-y-3">
            {subAgent.messages.map((msg, i) => (
              <div key={String(msg.ts)} className="relative">
                {/* Nesting guard — 1 level only */}
                {msg.subagentData ? (
                  <div
                    className="text-[11px] italic flex items-center gap-1 px-2 py-1 rounded"
                    style={{ color: 'var(--fg-muted)', backgroundColor: 'var(--code-bg)' }}
                  >
                    <AlertCircle className="size-3" />
                    Nested sub-agent (not rendered inline)
                  </div>
                ) : (
                  <>
                    {msg.toolCallData && (
                      <ToolCallChain toolCalls={[{
                        id: msg.toolCallData.toolCallId,
                        name: msg.toolCallData.toolName,
                        args: msg.toolCallData.args ?? '',
                        status: (msg.toolCallData.status as any) ?? 'COMPLETED',
                        result: msg.toolCallData.result,
                        output: msg.toolCallData.output,
                        durationMs: msg.toolCallData.durationMs,
                        diff: msg.toolCallData.diff,
                      }]} />
                    )}
                    {msg.say === 'REASONING' && msg.text && (
                      <div className="text-[11px] px-2 py-1 rounded" style={{ color: 'var(--fg-muted)', backgroundColor: 'var(--thinking-bg, rgba(0,0,0,0.04))' }}>
                        {msg.text}
                      </div>
                    )}
                    {msg.say === 'ERROR' && msg.text && (
                      <div className="text-[11px] px-2 py-1 rounded" style={{ color: 'var(--error, #ef4444)' }}>
                        {msg.text}
                      </div>
                    )}
                    {msg.text && msg.say !== 'REASONING' && msg.say !== 'ERROR' && !msg.toolCallData && (
                      <AgentMessage
                        message={msg}
                        isStreaming={isRunning && i === subAgent.messages.length - 1}
                      />
                    )}
                  </>
                )}
              </div>
            ))}

            {/* Active (in-progress) tool calls */}
            {subAgent.activeToolChain && subAgent.activeToolChain.length > 0 && (
              <ToolCallChain toolCalls={subAgent.activeToolChain} />
            )}

            {/* Completion summary */}
            {!isRunning && subAgent.summary && (
              <div
                className="group mt-2 px-3 py-2 rounded-md text-[11px] border relative"
                style={{
                  color: 'var(--fg-muted)',
                  backgroundColor: 'var(--code-bg)',
                  borderColor: 'var(--border)',
                }}
              >
                <CopyButton text={subAgent.summary} size="sm" hoverOnly className="absolute top-1.5 right-1.5" label="Copy result" />
                <div className="font-semibold mb-1" style={{ color: 'var(--fg-secondary)' }}>
                  Result
                </div>
                <div className="whitespace-pre-wrap font-sans">{subAgent.summary}</div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
});
