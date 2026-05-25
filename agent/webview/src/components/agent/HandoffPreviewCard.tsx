import { useCallback, useState } from 'react';
import { GitFork, MessageSquare, ChevronDown, ChevronRight } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardFooter } from '@/components/ui/card';
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer';
import type { Handoff } from '@/bridge/types';

interface HandoffPreviewCardProps {
  handoff: Handoff;
}

/**
 * new_task preview card (restored Cline contract). The LLM proposes a handoff with a
 * 5-section summary; the user picks "Start fresh session" (fork) or "Keep chatting"
 * (stay). The decision is delivered to Kotlin exactly once via the window bridges
 * `_handoffFork` / `_handoffKeep`, which feed a sentinel into the suspended AgentLoop.
 */
export function HandoffPreviewCard({ handoff }: HandoffPreviewCardProps) {
  const [decided, setDecided] = useState(false);
  const [expanded, setExpanded] = useState(false);

  const handleFork = useCallback(() => {
    if (decided) return;
    setDecided(true);
    (window as any)._handoffFork?.();
  }, [decided]);

  const handleKeep = useCallback(() => {
    if (decided) return;
    setDecided(true);
    (window as any)._handoffKeep?.();
  }, [decided]);

  return (
    <Card
      className="my-3 gap-0 overflow-hidden py-0 border-[var(--border)] bg-[var(--tool-bg,hsl(var(--card)))]"
      role="region"
      aria-label="Handoff proposal"
    >
      <div
        className="flex items-center gap-3 px-4 py-3"
        style={{ borderBottom: '1px solid var(--border)' }}
      >
        <div className="flex items-center justify-center rounded-md p-2" style={{ backgroundColor: 'var(--code-bg)' }}>
          <GitFork size={18} style={{ color: 'var(--accent)' }} />
        </div>
        <div className="flex-1 min-w-0">
          <div className="text-[13px] font-semibold" style={{ color: 'var(--fg)' }}>
            Continue in a fresh session?
          </div>
          <div className="text-[11px]" style={{ color: 'var(--fg-secondary)' }}>
            The agent prepared a handoff summary to carry into a clean context.
          </div>
        </div>
      </div>

      <CardContent className="px-4 py-3">
        <button
          type="button"
          onClick={() => setExpanded(e => !e)}
          className="flex items-center gap-1 text-[12px] font-medium mb-2"
          style={{ color: 'var(--accent)' }}
          aria-expanded={expanded}
        >
          {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
          {expanded ? 'Hide summary' : 'Show summary'}
        </button>
        {expanded && (
          <div className="text-[12px] leading-relaxed max-h-[320px] overflow-auto" style={{ color: 'var(--fg-secondary)' }}>
            <MarkdownRenderer content={handoff.summary} isStreaming={false} />
          </div>
        )}
      </CardContent>

      <CardFooter className="gap-2 px-4 py-3" style={{ borderTop: '1px solid var(--border)' }}>
        <Button
          onClick={handleKeep}
          className="text-[12px] font-medium"
          size="sm"
          variant="outline"
          disabled={decided}
          style={{ color: 'var(--fg-secondary)' }}
        >
          <MessageSquare size={14} />
          Keep chatting here
        </Button>
        <Button
          onClick={handleFork}
          className="glow-btn flex-1 text-[12px] font-medium"
          size="sm"
          disabled={decided}
        >
          <GitFork size={14} />
          Start fresh session
        </Button>
      </CardFooter>
    </Card>
  );
}
