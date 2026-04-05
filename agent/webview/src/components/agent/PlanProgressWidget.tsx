import type { Plan as PlanType } from '@/bridge/types';
import { Plan as PlanCard } from '@/components/ui/tool-ui/plan';

interface PlanProgressWidgetProps {
  plan: PlanType;
}

export function PlanProgressWidget({ plan }: PlanProgressWidgetProps) {
  if (!plan.approved) return null;

  // Map our PlanStep to tool-ui PlanTodo
  const todos = plan.steps.map(step => ({
    id: step.id,
    label: step.title,
    status: step.status === 'failed' ? 'cancelled' as const
      : step.status === 'skipped' ? 'cancelled' as const
      : step.status === 'running' ? 'in_progress' as const
      : (step.status === 'done' || step.status === 'completed') ? 'completed' as const
      : 'pending' as const,
    description: step.description,
  }));

  return (
    <div className="my-3" role="region" aria-label="Plan progress">
      <PlanCard
        id={`plan-${plan.title}`}
        title={plan.title}
        todos={todos}
        maxVisibleTodos={3}
      />
    </div>
  );
}
