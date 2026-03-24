import { useMemo } from 'react';
import { Check } from 'lucide-react';
import { Progress } from '@/components/ui/progress';
import { Badge } from '@/components/ui/badge';
import type { Plan, PlanStep } from '@/bridge/types';

// ── Phase grouping ──

interface Phase {
  name: string;
  steps: PlanStep[];
}

function groupStepsIntoPhases(steps: PlanStep[]): Phase[] {
  const phases: Phase[] = [];
  let currentPhase: Phase | null = null;

  for (const step of steps) {
    const phaseMatch = step.title.match(/^Phase\s+(\d+)\s*:\s*(.*)/i);

    if (phaseMatch) {
      currentPhase = {
        name: `Phase ${phaseMatch[1]}: ${phaseMatch[2]}`,
        steps: [],
      };
      phases.push(currentPhase);
    } else if (!currentPhase) {
      // Steps before any phase header go into a default group
      currentPhase = { name: 'Implementation', steps: [] };
      phases.push(currentPhase);
    }

    currentPhase.steps.push(step);
  }

  return phases.length > 0 ? phases : [{ name: 'Implementation', steps }];
}

function getPhaseStatus(steps: PlanStep[]): 'completed' | 'in-progress' | 'pending' {
  const allCompleted = steps.every(s => s.status === 'completed' || s.status === 'skipped');
  const anyActive = steps.some(s => s.status === 'running' || s.status === 'completed' || s.status === 'failed');

  if (allCompleted) return 'completed';
  if (anyActive) return 'in-progress';
  return 'pending';
}

// ── PlanProgressWidget ──

interface PlanProgressWidgetProps {
  plan: Plan;
}

export function PlanProgressWidget({ plan }: PlanProgressWidgetProps) {
  if (!plan.approved) return null;

  const phases = useMemo(() => groupStepsIntoPhases(plan.steps), [plan.steps]);

  const totalCompleted = plan.steps.filter(s => s.status === 'completed').length;
  const totalSteps = plan.steps.length;
  const overallProgress = totalSteps > 0 ? Math.round((totalCompleted / totalSteps) * 100) : 0;

  return (
    <div
      className="my-3 overflow-hidden rounded-lg border"
      role="region"
      aria-label="Plan progress"
      style={{
        borderColor: 'var(--border, #333)',
        backgroundColor: 'var(--card-bg, #252525)',
      }}
    >
      {/* Overall header */}
      <div
        className="flex items-center gap-3 px-4 py-3"
        style={{ borderBottom: '1px solid var(--border, #333)' }}
      >
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
            {totalCompleted}/{totalSteps} steps completed
          </div>
        </div>
        <span
          className="text-[12px] font-semibold tabular-nums"
          style={{ color: 'var(--accent, #6366f1)' }}
        >
          {overallProgress}%
        </span>
      </div>

      {/* Overall progress bar */}
      <div className="px-4 pt-2">
        <Progress
          value={overallProgress}
          className="h-1.5"
          style={{
            backgroundColor: 'var(--code-bg, #1a1a1a)',
          }}
        />
      </div>

      {/* Phase list */}
      <div className="px-4 py-3 space-y-3">
        {phases.map((phase, i) => {
          const status = getPhaseStatus(phase.steps);
          const completed = phase.steps.filter(s => s.status === 'completed').length;
          const total = phase.steps.length;
          const progress = total > 0 ? Math.round((completed / total) * 100) : 0;

          return (
            <PhaseRow
              key={i}
              name={phase.name}
              status={status}
              completed={completed}
              total={total}
              progress={progress}
            />
          );
        })}
      </div>
    </div>
  );
}

// ── PhaseRow ──

function PhaseRow({
  name,
  status,
  completed,
  total,
  progress,
}: {
  name: string;
  status: 'completed' | 'in-progress' | 'pending';
  completed: number;
  total: number;
  progress: number;
}) {
  const isActive = status === 'in-progress';
  const isDone = status === 'completed';

  return (
    <div
      className="rounded-md px-3 py-2 transition-colors"
      style={{
        backgroundColor: isActive ? 'var(--hover-overlay, rgba(255,255,255,0.04))' : 'transparent',
        borderLeft: isActive
          ? '2px solid var(--accent, #6366f1)'
          : '2px solid transparent',
      }}
    >
      <div className="flex items-center gap-2 mb-1.5">
        {isDone ? (
          <div
            className="flex items-center justify-center rounded-full"
            style={{
              width: 18,
              height: 18,
              backgroundColor: 'var(--badge-cmd-bg, #1a2e1a)',
            }}
          >
            <Check
              size={12}
              style={{ color: 'var(--badge-cmd-fg, #6ee77a)' }}
            />
          </div>
        ) : (
          <div
            className="rounded-full"
            style={{
              width: 18,
              height: 18,
              border: isActive
                ? '2px solid var(--accent, #6366f1)'
                : '2px solid var(--fg-muted, #888)',
              backgroundColor: 'transparent',
            }}
          />
        )}

        <span
          className="flex-1 text-[12px] font-medium truncate"
          style={{
            color: isDone
              ? 'var(--fg-muted, #888)'
              : isActive
                ? 'var(--fg, #ccc)'
                : 'var(--fg-secondary, #aaa)',
          }}
        >
          {name}
        </span>

        {isActive && (
          <Badge
            variant="outline"
            className="text-[9px] px-1.5 py-0"
            style={{
              borderColor: 'var(--accent, #6366f1)',
              color: 'var(--accent, #6366f1)',
            }}
          >
            In Progress
          </Badge>
        )}

        <span
          className="text-[10px] tabular-nums"
          style={{ color: 'var(--fg-muted, #888)' }}
        >
          {completed}/{total}
        </span>
      </div>

      <Progress
        value={progress}
        className="h-1"
        style={{
          backgroundColor: 'var(--code-bg, #1a1a1a)',
        }}
      />
    </div>
  );
}
