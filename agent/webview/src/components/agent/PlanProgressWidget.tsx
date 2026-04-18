import { useChatStore } from '@/stores/chatStore';
import { Plan as PlanCard } from '@/components/ui/tool-ui/plan';
import type { Task, TaskStatus } from '@/bridge/types';

function mapStatus(s: TaskStatus): 'pending' | 'in_progress' | 'completed' | 'cancelled' {
  switch (s) {
    case 'completed': return 'completed';
    case 'in_progress': return 'in_progress';
    case 'deleted': return 'cancelled';
    default: return 'pending';
  }
}

export function PlanProgressWidget() {
  const tasks = useChatStore((s) => s.tasks);
  const visible = tasks.filter((t: Task) => t.status !== 'deleted');
  if (visible.length === 0) return null;

  const todos = visible.map((t: Task) => ({
    id: t.id,
    label: t.activeForm && t.status === 'in_progress' ? t.activeForm : t.subject,
    status: mapStatus(t.status),
    description: t.description,
  }));

  return (
    <div className="my-3" role="region" aria-label="Task progress">
      <PlanCard id="task-progress" title="Tasks" todos={todos} maxVisibleTodos={3} />
    </div>
  );
}
