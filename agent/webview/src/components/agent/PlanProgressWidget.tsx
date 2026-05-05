import { memo, useMemo } from 'react';
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

/**
 * Bug 7 — wrapped in React.memo so parent re-renders (every chat-message append,
 * every streaming token, every activeToolCall change) don't re-run this component.
 * Combined with the chatStore short-circuit in setTasks/applyTask*, this means the
 * widget only re-renders when the task array's content actually changed.
 *
 * The visible/todos derivations are memoized on `tasks` so a re-render that does
 * happen (e.g. after setTasks with truly-new content) doesn't churn child props
 * for entries that didn't change.
 */
export const PlanProgressWidget = memo(function PlanProgressWidget() {
  const tasks = useChatStore((s) => s.tasks);
  const visible = useMemo(() => tasks.filter((t: Task) => t.status !== 'deleted'), [tasks]);
  const todos = useMemo(
    () => visible.map((t: Task) => ({
      id: t.id,
      label: t.activeForm && t.status === 'in_progress' ? t.activeForm : t.subject,
      status: mapStatus(t.status),
      description: t.description,
    })),
    [visible],
  );
  if (todos.length === 0) return null;

  return (
    <div className="my-3" role="region" aria-label="Task progress">
      <PlanCard id="task-progress" title="Tasks" todos={todos} maxVisibleTodos={3} />
    </div>
  );
});
