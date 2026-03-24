import { useCallback } from 'react';
import { FileText } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import type { Plan } from '@/bridge/types';

interface PlanSummaryCardProps {
  plan: Plan;
}

export function PlanSummaryCard({ plan }: PlanSummaryCardProps) {
  if (plan.approved) return null;

  const stepCount = plan.steps.length;
  const pendingCount = plan.steps.filter(s => s.status === 'pending').length;

  const handleViewPlan = useCallback(() => {
    (window as any)._approvePlan?.();
  }, []);

  return (
    <div
      className="my-3 overflow-hidden rounded-lg border"
      role="region"
      aria-label="Plan summary"
      style={{
        borderColor: 'var(--border, #333)',
        backgroundColor: 'var(--card-bg, #252525)',
      }}
    >
      {/* Header */}
      <div
        className="flex items-center gap-3 px-4 py-3"
        style={{ borderBottom: '1px solid var(--border, #333)' }}
      >
        <div
          className="flex items-center justify-center rounded-md p-2"
          style={{ backgroundColor: 'var(--code-bg, #1a1a1a)' }}
        >
          <FileText
            size={18}
            style={{ color: 'var(--accent, #6366f1)' }}
          />
        </div>
        <div className="flex-1 min-w-0">
          <div
            className="text-[13px] font-semibold truncate"
            style={{ color: 'var(--fg, #ccc)' }}
          >
            {plan.title}
          </div>
          <div
            className="text-[11px] mt-0.5"
            style={{ color: 'var(--fg-secondary, #aaa)' }}
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
            borderColor: 'var(--accent, #6366f1)',
            color: 'var(--accent, #6366f1)',
          }}
        >
          Awaiting Approval
        </Badge>
      </div>

      {/* Action */}
      <div className="px-4 py-3">
        <Button
          onClick={handleViewPlan}
          className="w-full text-[12px] font-medium"
          size="sm"
          style={{
            backgroundColor: 'var(--accent, #6366f1)',
            color: '#fff',
          }}
        >
          <FileText size={14} />
          View Implementation Plan
        </Button>
      </div>
    </div>
  );
}
