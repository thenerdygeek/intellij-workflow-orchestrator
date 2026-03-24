import { useState, useEffect, useRef, useCallback } from 'react';
import {
  Reasoning,
  ReasoningTrigger,
  ReasoningContent,
} from '../ui/prompt-kit/reasoning';
import { TextShimmer } from '../ui/prompt-kit/text-shimmer';

// ── Thinking Timer Hook ──

function useThinkingTimer(isStreaming: boolean): number {
  const startRef = useRef<number>(Date.now());
  const [elapsed, setElapsed] = useState(0);

  useEffect(() => {
    if (isStreaming) {
      startRef.current = Date.now();
      setElapsed(0);
      const interval = setInterval(() => {
        setElapsed(Date.now() - startRef.current);
      }, 100);
      return () => clearInterval(interval);
    }
  }, [isStreaming]);

  return elapsed;
}

function formatThinkingDuration(elapsedMs: number, isStreaming: boolean): string {
  if (isStreaming) return 'Thinking...';
  const seconds = Math.round(elapsedMs / 1000);
  if (seconds <= 0) return 'Thought for <1s';
  return `Thought for ${seconds}s`;
}

// ── ThinkingView Component ──

interface ThinkingViewProps {
  content: string;
  isStreaming: boolean;
}

export function ThinkingView({ content, isStreaming }: ThinkingViewProps) {
  const elapsed = useThinkingTimer(isStreaming);
  const [open, setOpen] = useState(true);
  const hasAutoCollapsed = useRef(false);

  // Auto-collapse 600ms after streaming ends (only once)
  useEffect(() => {
    if (!isStreaming && !hasAutoCollapsed.current && content.length > 0) {
      const timer = setTimeout(() => {
        setOpen(false);
        hasAutoCollapsed.current = true;
      }, 600);
      return () => clearTimeout(timer);
    }
  }, [isStreaming, content]);

  const handleOpenChange = useCallback((newOpen: boolean) => {
    setOpen(newOpen);
  }, []);

  const label = formatThinkingDuration(elapsed, isStreaming);

  return (
    <Reasoning
      open={open}
      onOpenChange={handleOpenChange}
      isStreaming={isStreaming}
      className="mb-3 overflow-hidden rounded-lg"
    >
      <div
        role="region"
        aria-label="Agent reasoning"
        style={{
          borderLeft: '3px solid var(--accent-thinking, #8b5cf6)',
          backgroundColor: 'var(--thinking-bg, #1f2937)',
        }}
      >
        {/* Trigger / header */}
        <ReasoningTrigger
          className="w-full px-3 py-2 text-[12px] font-medium transition-colors duration-100 hover:brightness-110"
          aria-label={`${open ? 'Collapse' : 'Expand'} reasoning`}
          style={{ color: 'var(--accent-thinking, #8b5cf6)' }}
        >
          {isStreaming ? (
            <TextShimmer duration={2} spread={30}>{label}</TextShimmer>
          ) : (
            label
          )}
        </ReasoningTrigger>

        {/* Shimmer bar during streaming */}
        {isStreaming && (
          <div
            className="h-[2px] w-full overflow-hidden"
            style={{ backgroundColor: 'color-mix(in srgb, var(--accent-thinking, #8b5cf6) 15%, transparent)' }}
          >
            <div
              className="h-full w-1/4"
              style={{
                backgroundColor: 'var(--accent-thinking, #8b5cf6)',
                animation: 'thinking-shimmer 2.5s ease-in-out infinite',
              }}
            />
          </div>
        )}

        {/* Collapsible content */}
        <ReasoningContent
          className="transition-all duration-300 ease-in-out"
          contentClassName="px-3 pb-2 pt-1 text-[12px] leading-relaxed"
          style={{
            color: 'var(--fg-secondary, var(--fg-muted, #888))',
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
          }}
        >
          {content}
          {/* Blinking cursor during streaming */}
          {isStreaming && (
            <span
              className="ml-0.5 inline-block"
              style={{
                width: 2,
                height: '1em',
                backgroundColor: 'var(--accent-thinking, #8b5cf6)',
                animation: 'cursor-blink 1s step-end infinite',
                verticalAlign: 'text-bottom',
              }}
            />
          )}
        </ReasoningContent>
      </div>
    </Reasoning>
  );
}
