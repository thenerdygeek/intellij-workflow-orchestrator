import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { FileText, Check, RotateCcw, Loader2, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardFooter } from '@/components/ui/card';
import type { Plan } from '@/bridge/types';
import { Badge } from '@/components/ui/badge';
import { useChatStore } from '@/stores/chatStore';
import { kotlinBridge } from '@/bridge/jcef-bridge';
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer';

interface PlanSummaryCardProps {
  plan: Plan;
}

/**
 * The single identity formula for a plan instance (bug #11). When the store has
 * assigned a `revision` (the normal path) that monotonic number IS the identity —
 * so a same-title, still-unapproved revision is correctly distinct. Falls back to
 * a content hash only for plans rendered outside the store (tests / legacy).
 */
function planIdentity(plan: Plan): string {
  if (plan.revision != null) return `rev:${plan.revision}`;
  return `${plan.title}::${plan.summary ?? plan.markdown ?? ''}`;
}

/**
 * Bug 8 — Typewriter that reveals text character-by-character ONCE per plan
 * identity. Subsequent remounts of the card with the same identity render the
 * full text immediately (no animation). The "have I typed this before" state
 * lives in the chatStore so it survives parent reconciliation that drops and
 * remounts the card. Calls `onComplete` exactly when the text is fully revealed.
 */
function TypewriterReveal({
  text,
  speed = 12,
  skipAnimation,
  onComplete,
}: {
  text: string;
  speed?: number;
  skipAnimation: boolean;
  onComplete: () => void;
}) {
  const [displayed, setDisplayed] = useState(skipAnimation ? text : '');
  const indexRef = useRef(skipAnimation ? text.length : 0);

  useEffect(() => {
    if (skipAnimation) {
      setDisplayed(text);
      indexRef.current = text.length;
      return;
    }
    const timer = setInterval(() => {
      if (indexRef.current < text.length) {
        indexRef.current++;
        setDisplayed(text.slice(0, indexRef.current));
      } else {
        clearInterval(timer);
        onComplete();
      }
    }, speed);
    return () => clearInterval(timer);
  }, [text, speed, skipAnimation, onComplete]);

  const isStreaming = displayed.length < text.length;
  return (
    <>
      <MarkdownRenderer content={displayed} isStreaming={isStreaming} />
      {isStreaming && (
        <span className="inline-block w-[2px] h-[13px] align-middle ml-[1px] animate-pulse"
          style={{ backgroundColor: 'var(--accent)' }} />
      )}
    </>
  );
}

/**
 * Bug 8 — extracted to its own component so the typewriter "skipAnimation"
 * decision is taken on mount (deterministic, no flashing). The seen-set is
 * read once and the result is memoized for the lifetime of the mount.
 */
function PlanSummaryContent({ plan }: { plan: Plan }) {
  const text = plan.summary ?? (plan.markdown ? plan.markdown.slice(0, 300) + (plan.markdown.length > 300 ? '...' : '') : '');
  const identity = useMemo(() => planIdentity(plan), [plan]);
  const seenRef = useRef<boolean>(useChatStore.getState().seenPlanSummaries.has(identity));
  const markPlanSummarySeen = useChatStore(s => s.markPlanSummarySeen);

  const handleComplete = useCallback(() => {
    markPlanSummarySeen(identity);
  }, [markPlanSummarySeen, identity]);

  return (
    <CardContent className="px-4 py-3">
      <div
        className="text-[12px] leading-relaxed line-clamp-4"
        style={{ color: 'var(--fg-secondary)' }}
      >
        <TypewriterReveal
          text={text}
          skipAnimation={seenRef.current}
          onComplete={handleComplete}
        />
      </div>
    </CardContent>
  );
}

export function PlanSummaryCard({ plan }: PlanSummaryCardProps) {
  // NOTE: the `plan.approved` early-return lives AFTER all hooks (below) — never
  // before them — or React's hook order changes when an approved plan is
  // rendered through this component, crashing with "rendered fewer hooks".
  const [pending, setPending] = useState<'approve' | 'revise' | null>(null);
  const planCommentCount = useChatStore(s => s.planCommentCount);

  // Reset loading state when the plan is replaced (e.g., LLM calls create_plan
  // again after a ChatMessage or revision). Keyed on the stable plan identity so
  // a same-title, still-unapproved revision still resets the button (bug #11) —
  // the old `${title}:${approved}` key didn't change across such revisions.
  const identity = planIdentity(plan);
  useEffect(() => {
    setPending(null);
  }, [identity]);

  const hasComments = planCommentCount > 0;

  const handleViewPlan = useCallback(() => {
    (window as any)._focusPlanEditor?.();
  }, []);

  const handleApprove = useCallback(() => {
    setPending('approve');
    (window as any)._approvePlan?.();
  }, []);

  const handleRevise = useCallback(() => {
    setPending('revise');
    // Trigger revision via the plan editor tab's bridge — the editor holds the comments
    (window as any)._revisePlanFromEditor?.();
  }, []);

  const handleDismiss = useCallback(() => {
    kotlinBridge.dismissPlan();
  }, []);

  // Hooks are all above this line — safe to bail out now.
  if (plan.approved) return null;

  return (
    <Card
      className="my-3 gap-0 overflow-hidden py-0 border-[var(--border)] bg-[var(--tool-bg,hsl(var(--card)))]"
      role="region"
      aria-label="Plan summary"
    >
      {/* Header */}
      <div
        className="flex items-center gap-3 px-4 py-3"
        style={{ borderBottom: '1px solid var(--border)' }}
      >
        <div
          className="flex items-center justify-center rounded-md p-2"
          style={{ backgroundColor: 'var(--code-bg)' }}
        >
          <FileText
            size={18}
            style={{ color: 'var(--accent)' }}
          />
        </div>
        <div className="flex-1 min-w-0">
          <div
            className="text-[13px] font-semibold truncate"
            style={{ color: 'var(--fg)' }}
          >
            {plan.title}
          </div>
        </div>
        <Badge
          variant="outline"
          className="text-[10px] shrink-0"
          style={{
            borderColor: hasComments ? 'var(--warning, #eab308)' : 'var(--accent)',
            color: hasComments ? 'var(--warning, #eab308)' : 'var(--accent)',
          }}
        >
          {hasComments ? `${planCommentCount} comment${planCommentCount !== 1 ? 's' : ''}` : 'Awaiting Approval'}
        </Badge>
      </div>

      {/* Content preview — markdown-rendered summary with one-shot typewriter reveal */}
      {(plan.summary || plan.markdown) && (
        <PlanSummaryContent plan={plan} />
      )}

      {/* Actions */}
      <CardFooter className="gap-2 px-4 py-3" style={{ borderTop: '1px solid var(--border)' }}>
        <Button
          onClick={handleDismiss}
          className="text-[12px] font-medium shrink-0"
          size="sm"
          variant="ghost"
          disabled={pending !== null}
          style={{ color: 'var(--fg-secondary)' }}
          title="Dismiss plan"
        >
          <X size={14} />
          Dismiss
        </Button>
        <Button
          onClick={handleViewPlan}
          className="flex-1 text-[12px] font-medium"
          size="sm"
          variant="outline"
          style={{
            borderColor: 'var(--accent)',
            color: 'var(--accent)',
          }}
        >
          <FileText size={14} />
          View Implementation Plan
        </Button>
        {hasComments ? (
          <Button
            onClick={handleRevise}
            className="text-[12px] font-medium"
            size="sm"
            variant="outline"
            disabled={pending !== null}
            style={{
              borderColor: 'var(--warning, #eab308)',
              color: 'var(--warning, #eab308)',
            }}
          >
            {pending === 'revise' ? (
              <><Loader2 size={14} className="animate-spin" /> Revising…</>
            ) : (
              <><RotateCcw size={14} /> Revise ({planCommentCount})</>
            )}
          </Button>
        ) : (
          <Button
            onClick={handleApprove}
            className="glow-btn text-[12px] font-medium"
            size="sm"
            disabled={pending !== null}
          >
            {pending === 'approve' ? (
              <><Loader2 size={14} className="animate-spin" /> Approving…</>
            ) : (
              <><Check size={14} /> Approve</>
            )}
          </Button>
        )}
      </CardFooter>
    </Card>
  );
}
