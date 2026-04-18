import { useCallback, useEffect, useRef, useState } from 'react';
import { FileText, Check, RotateCcw, Loader2, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardFooter } from '@/components/ui/card';
import type { Plan } from '@/bridge/types';
import { Badge } from '@/components/ui/badge';
import { useChatStore } from '@/stores/chatStore';
import { kotlinBridge } from '@/bridge/jcef-bridge';

interface PlanSummaryCardProps {
  plan: Plan;
}

/** Typewriter effect — reveals text character by character. */
function TypewriterText({ text, speed = 12 }: { text: string; speed?: number }) {
  const [displayed, setDisplayed] = useState('');
  const indexRef = useRef(0);
  const prevTextRef = useRef('');

  useEffect(() => {
    // Reset when summary text changes (new plan)
    if (text !== prevTextRef.current) {
      prevTextRef.current = text;
      indexRef.current = 0;
      setDisplayed('');
    }

    const timer = setInterval(() => {
      if (indexRef.current < text.length) {
        indexRef.current++;
        setDisplayed(text.slice(0, indexRef.current));
      } else {
        clearInterval(timer);
      }
    }, speed);

    return () => clearInterval(timer);
  }, [text, speed]);

  return (
    <>
      {displayed}
      {displayed.length < text.length && (
        <span className="inline-block w-[2px] h-[13px] align-middle ml-[1px] animate-pulse"
          style={{ backgroundColor: 'var(--accent)' }} />
      )}
    </>
  );
}

export function PlanSummaryCard({ plan }: PlanSummaryCardProps) {
  if (plan.approved) return null;

  const [pending, setPending] = useState<'approve' | 'revise' | null>(null);
  const planCommentCount = useChatStore(s => s.planCommentCount);

  // Reset loading state when the plan is replaced (e.g., LLM calls create_plan
  // again after a ChatMessage or revision). Without this, the Revise/Approve
  // button stays stuck in loading if the plan resolves via a different path.
  const planIdentity = `${plan.title}:${plan.approved}`;
  useEffect(() => {
    setPending(null);
  }, [planIdentity]);

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

      {/* Content preview — typewriter summary or markdown snippet */}
      {(plan.summary || plan.markdown) && (
        <CardContent className="px-4 py-3">
          {plan.summary ? (
            <div
              className="text-[12px] leading-relaxed line-clamp-4 whitespace-pre-wrap"
              style={{ color: 'var(--fg-secondary)' }}
            >
              <TypewriterText text={plan.summary} />
            </div>
          ) : plan.markdown ? (
            <div
              className="text-[12px] leading-relaxed line-clamp-4 whitespace-pre-wrap"
              style={{ color: 'var(--fg-secondary)' }}
            >
              {plan.markdown.slice(0, 300)}
              {plan.markdown.length > 300 && '...'}
            </div>
          ) : null}
        </CardContent>
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
