import { useCallback, useState } from 'react';
import { FileText, Check, RotateCcw, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardFooter } from '@/components/ui/card';
import { PlanCompact } from '@/components/ui/tool-ui/plan';
import { openInEditorTab } from '@/bridge/jcef-bridge';
import type { Plan } from '@/bridge/types';
import { Badge } from '@/components/ui/badge';

interface PlanSummaryCardProps {
  plan: Plan;
}

export function PlanSummaryCard({ plan }: PlanSummaryCardProps) {
  if (plan.approved) return null;

  const [pending, setPending] = useState<'approve' | 'revise' | null>(null);

  const stepCount = plan.steps.length;
  const pendingCount = plan.steps.filter(s => s.status === 'pending').length;

  // Map our PlanStep to tool-ui PlanTodo (all pending for unapproved plans)
  const todos = plan.steps.map(step => ({
    id: step.id,
    label: step.title,
    status: 'pending' as const,
    description: step.description,
  }));

  const handleViewPlan = useCallback(() => {
    openInEditorTab('plan', JSON.stringify(plan));
  }, [plan]);

  const handleApprove = useCallback(() => {
    setPending('approve');
    (window as any)._approvePlan?.();
  }, []);

  const handleRevise = useCallback(() => {
    // Open the plan editor tab so the user can add inline comments.
    // Previously sent empty string which broke Kotlin JSON parsing.
    openInEditorTab('plan', JSON.stringify(plan));
  }, [plan]);

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
          <div
            className="text-[11px] mt-0.5"
            style={{ color: 'var(--fg-secondary)' }}
          >
            {stepCount} step{stepCount !== 1 ? 's' : ''} planned
            {pendingCount < stepCount && (
              <span> &middot; {stepCount - pendingCount} in progress</span>
            )}
          </div>
        </div>
        <Badge
          variant="outline"
          className="text-[10px] shrink-0"
          style={{
            borderColor: 'var(--accent)',
            color: 'var(--accent)',
          }}
        >
          Awaiting Approval
        </Badge>
      </div>

      {/* Content preview */}
      <CardContent className="px-4 py-3">
        {plan.markdown ? (
          <div
            className="text-[12px] leading-relaxed line-clamp-4 whitespace-pre-wrap"
            style={{ color: 'var(--fg-secondary)' }}
          >
            {plan.markdown.slice(0, 300)}
            {plan.markdown.length > 300 && '...'}
          </div>
        ) : (
          <PlanCompact todos={todos} maxVisibleTodos={4} />
        )}
      </CardContent>

      {/* Actions */}
      <CardFooter className="gap-2 px-4 py-3" style={{ borderTop: '1px solid var(--border)' }}>
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
        <Button
          onClick={handleRevise}
          className="text-[12px] font-medium"
          size="sm"
          variant="outline"
          disabled={pending !== null}
          style={{
            borderColor: 'var(--border)',
            color: 'var(--fg-secondary)',
          }}
        >
          <RotateCcw size={14} /> Revise in Editor
        </Button>
      </CardFooter>
    </Card>
  );
}
