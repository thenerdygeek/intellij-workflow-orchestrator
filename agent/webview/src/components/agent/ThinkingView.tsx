import { useState, useEffect, useRef, useCallback } from 'react';
import {
  Reasoning,
  ReasoningTrigger,
  ReasoningContent,
} from '../ui/prompt-kit/reasoning';
import { TextShimmer } from '../ui/prompt-kit/text-shimmer';
import { Brain } from 'lucide-react';

// ── Thinking Timer ──

function useThinkingTimer(isStreaming: boolean): number {
  const startRef = useRef<number>(Date.now());
  const [elapsed, setElapsed] = useState(0);

  useEffect(() => {
    if (isStreaming) {
      startRef.current = Date.now();
      setElapsed(0);
      const interval = setInterval(() => setElapsed(Date.now() - startRef.current), 1000);
      return () => clearInterval(interval);
    }
  }, [isStreaming]);

  return elapsed;
}

function formatDuration(ms: number, isStreaming: boolean): string {
  if (isStreaming) return 'Thinking...';
  const seconds = Math.round(ms / 1000);
  if (seconds <= 0) return 'Thought for <1s';
  return `Thought for ${seconds}s`;
}

// ── ThinkingView ──

interface ThinkingViewProps {
  content: string;
  isStreaming: boolean;
}

export function ThinkingView({ content, isStreaming }: ThinkingViewProps) {
  const elapsed = useThinkingTimer(isStreaming);
  const [open, setOpen] = useState(true);
  const hasAutoCollapsed = useRef(false);

  useEffect(() => {
    if (!isStreaming && !hasAutoCollapsed.current && content.length > 0) {
      const timer = setTimeout(() => {
        setOpen(false);
        hasAutoCollapsed.current = true;
      }, 600);
      return () => clearTimeout(timer);
    }
  }, [isStreaming, content]);

  const handleOpenChange = useCallback((v: boolean) => setOpen(v), []);
  const label = formatDuration(elapsed, isStreaming);

  return (
    <Reasoning
      open={open}
      onOpenChange={handleOpenChange}
      isStreaming={isStreaming}
      className="mb-2"
    >
      <div role="region" aria-label="Agent reasoning">
        <ReasoningTrigger
          className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-[12px] transition-colors hover:bg-[var(--hover-overlay)]"
          aria-label={`${open ? 'Collapse' : 'Expand'} reasoning`}
        >
          <Brain className="size-3 shrink-0" style={{ color: 'var(--fg-muted)' }} />
          <span style={{ color: 'var(--fg-secondary)' }}>
            {isStreaming ? (
              <TextShimmer duration={2} spread={30}>{label}</TextShimmer>
            ) : (
              label
            )}
          </span>
        </ReasoningTrigger>

        <ReasoningContent
          className="transition-all duration-300 ease-in-out"
          contentClassName="ml-7 pb-2 pt-1 text-[11px] leading-relaxed border-l-2 pl-3"
          style={{
            color: 'var(--fg-muted)',
            borderColor: 'var(--border)',
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
          }}
        >
          {content}
        </ReasoningContent>
      </div>
    </Reasoning>
  );
}
