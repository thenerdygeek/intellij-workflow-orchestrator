import { useState, useEffect, useRef, useCallback } from 'react';

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

// ── ThinkingBlock Component ──

interface ThinkingBlockProps {
  content: string;
  isStreaming: boolean;
}

export function ThinkingBlock({ content, isStreaming }: ThinkingBlockProps) {
  const elapsed = useThinkingTimer(isStreaming);
  const [expanded, setExpanded] = useState(true);
  const hasAutoCollapsed = useRef(false);
  const contentRef = useRef<HTMLDivElement>(null);
  const [contentHeight, setContentHeight] = useState<number | undefined>(undefined);

  // Auto-collapse when streaming ends (600ms delay, only once)
  useEffect(() => {
    if (!isStreaming && !hasAutoCollapsed.current && content.length > 0) {
      const timer = setTimeout(() => {
        setExpanded(false);
        hasAutoCollapsed.current = true;
      }, 600);
      return () => clearTimeout(timer);
    }
  }, [isStreaming, content]);

  // Measure content height for smooth animation
  useEffect(() => {
    if (contentRef.current) {
      setContentHeight(contentRef.current.scrollHeight);
    }
  }, [content, expanded]);

  const toggleExpanded = useCallback(() => {
    setExpanded(prev => !prev);
  }, []);

  const label = formatThinkingDuration(elapsed, isStreaming);

  return (
    <div
      className="mb-3 overflow-hidden rounded-lg"
      role="region"
      aria-label="Agent reasoning"
      style={{
        borderLeft: '3px solid var(--accent-thinking, #8b5cf6)',
        backgroundColor: 'var(--thinking-bg, #1f2937)',
      }}
    >
      {/* Header */}
      <button
        onClick={toggleExpanded}
        aria-expanded={expanded}
        aria-label={`${expanded ? 'Collapse' : 'Expand'} reasoning`}
        className="flex w-full items-center gap-2 px-3 py-2 text-left transition-colors duration-100 hover:brightness-110"
        style={{ background: 'transparent' }}
      >
        {/* Brain icon with animated dashed circle */}
        <div className="relative shrink-0" style={{ width: 18, height: 18 }}>
          {/* Dashed circle — animated spin during streaming */}
          <svg
            width="18"
            height="18"
            viewBox="0 0 18 18"
            fill="none"
            className={isStreaming ? 'animate-spin' : ''}
            style={{ animationDuration: '3s', position: 'absolute', top: 0, left: 0 }}
          >
            <circle
              cx="9"
              cy="9"
              r="7.5"
              stroke="var(--accent-thinking, #8b5cf6)"
              strokeWidth="1"
              strokeDasharray={isStreaming ? '4 3' : '0'}
              opacity={isStreaming ? 0.6 : 0}
            />
          </svg>
          {/* Brain icon */}
          <svg
            width="14"
            height="14"
            viewBox="0 0 24 24"
            fill="none"
            style={{ position: 'absolute', top: 2, left: 2 }}
          >
            <path
              d="M12 2C9.5 2 7.5 3.5 7 5.5C5.5 5.8 4 7.2 4 9c0 1.5.8 2.8 2 3.5v6a2.5 2.5 0 005 0v-1h2v1a2.5 2.5 0 005 0v-6c1.2-.7 2-2 2-3.5 0-1.8-1.5-3.2-3-3.5C16.5 3.5 14.5 2 12 2z"
              stroke="var(--accent-thinking, #8b5cf6)"
              strokeWidth="1.5"
              strokeLinecap="round"
              strokeLinejoin="round"
              fill="none"
            />
            <path
              d="M12 2v16M8 8h8M9 12h6"
              stroke="var(--accent-thinking, #8b5cf6)"
              strokeWidth="1"
              strokeLinecap="round"
              opacity="0.5"
            />
          </svg>
        </div>

        {/* Label */}
        <span
          className="text-[12px] font-medium"
          style={{ color: 'var(--accent-thinking, #8b5cf6)' }}
        >
          {label}
        </span>

        {/* Spacer */}
        <span className="flex-1" />

        {/* Expand/collapse chevron */}
        <svg
          width="12"
          height="12"
          viewBox="0 0 16 16"
          fill="none"
          className="shrink-0 transition-transform duration-200"
          style={{
            transform: expanded ? 'rotate(180deg)' : 'rotate(0deg)',
            color: 'var(--fg-muted, #888)',
          }}
        >
          <path
            d="M4 6l4 4 4-4"
            stroke="currentColor"
            strokeWidth="1.5"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </button>

      {/* Shimmer bar during streaming */}
      {isStreaming && (
        <div
          className="h-[2px] w-full overflow-hidden"
          style={{ backgroundColor: 'rgba(139, 92, 246, 0.15)' }}
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

      {/* Collapsible content with smooth height transition */}
      <div
        className="overflow-hidden transition-all duration-300 ease-in-out"
        style={{
          maxHeight: expanded ? (contentHeight ?? 500) + 16 : 0,
          opacity: expanded ? 1 : 0,
        }}
      >
        <div
          ref={contentRef}
          className="px-3 pb-2 pt-1 text-[12px] leading-relaxed"
          style={{
            color: 'var(--fg-muted, #888)',
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
        </div>
      </div>
    </div>
  );
}
