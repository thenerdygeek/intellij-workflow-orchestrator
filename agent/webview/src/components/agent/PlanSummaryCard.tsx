import { useCallback } from 'react';
import { FileText, Check, RotateCcw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { openInEditorTab } from '@/bridge/jcef-bridge';
import type { Plan } from '@/bridge/types';

interface PlanSummaryCardProps {
  plan: Plan;
}

export function PlanSummaryCard({ plan }: PlanSummaryCardProps) {
  if (plan.approved) return null;

  const stepCount = plan.steps.length;
  const pendingCount = plan.steps.filter(s => s.status === 'pending').length;

  const handleViewPlan = useCallback(() => {
    openInEditorTab('plan', JSON.stringify(plan));
  }, [plan]);

  const handleApprove = useCallback(() => {
    (window as any)._approvePlan?.();
  }, []);

  const handleRevise = useCallback(() => {
    (window as any)._revisePlan?.('');
  }, []);

  return (
    <div
      className="my-3 overflow-hidden rounded-lg border"
      role="region"
      aria-label="Plan summary"
      style={{
        borderColor: 'var(--border)',
        backgroundColor: 'var(--tool-bg)',
      }}
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

      {/* Actions */}
      <div className="flex gap-2 px-4 py-3">
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
          className="text-[12px] font-medium"
          size="sm"
          style={{
            backgroundColor: 'var(--accent)',
            color: 'var(--bg)',
          }}
        >
          <Check size={14} />
          Approve
        </Button>
        <Button
          onClick={handleRevise}
          className="text-[12px] font-medium"
          size="sm"
          variant="outline"
          style={{
            borderColor: 'var(--border)',
            color: 'var(--fg-secondary)',
          }}
        >
          <RotateCcw size={14} />
          Revise
        </Button>
      </div>
    </div>
  );
}
