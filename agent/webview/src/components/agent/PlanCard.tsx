import { useState, useCallback } from 'react';
import type { Plan, PlanStep, PlanStepStatus } from '@/bridge/types';

// ── StepStatusIcon ──

function StepStatusIcon({ status, index }: { status: PlanStepStatus; index: number }) {
  switch (status) {
    case 'pending':
      return (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" className="shrink-0">
          <circle cx="12" cy="12" r="10" stroke="var(--fg-muted, #888)" strokeWidth="1.5" fill="none" />
          <text
            x="12" y="12"
            textAnchor="middle" dominantBaseline="central"
            fill="var(--fg-muted, #888)"
            fontSize="10" fontWeight="600" fontFamily="var(--font-body)"
          >
            {index + 1}
          </text>
        </svg>
      );
    case 'running':
      return (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" className="shrink-0 animate-spin">
          <circle cx="12" cy="12" r="10" stroke="var(--fg-muted, #555)" strokeWidth="1.5" fill="none" />
          <path
            d="M12 2a10 10 0 0 1 10 10"
            stroke="var(--accent, #6366f1)"
            strokeWidth="2"
            strokeLinecap="round"
          />
        </svg>
      );
    case 'completed':
      return (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" className="shrink-0">
          <circle cx="12" cy="12" r="10" fill="var(--badge-cmd-bg, #1a2e1a)" />
          <path
            d="M8 12l3 3 5-5"
            stroke="var(--badge-cmd-fg, #6ee77a)"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      );
    case 'failed':
      return (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" className="shrink-0">
          <circle cx="12" cy="12" r="10" fill="var(--error-bg, #3b1a1a)" />
          <path
            d="M9 9l6 6M15 9l-6 6"
            stroke="var(--error-fg, #f06060)"
            strokeWidth="2"
            strokeLinecap="round"
          />
        </svg>
      );
    case 'skipped':
      return (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" className="shrink-0">
          <circle
            cx="12" cy="12" r="10"
            stroke="var(--fg-muted, #888)"
            strokeWidth="1.5"
            strokeDasharray="4 3"
            fill="none"
          />
          <path
            d="M9 8l6 4-6 4z"
            fill="var(--fg-muted, #888)"
          />
        </svg>
      );
  }
}

// ── StepConnector ──

function StepConnector({ completed }: { completed: boolean }) {
  return (
    <div
      className="ml-[11px] w-[2px] h-6 transition-colors duration-300"
      style={{
        backgroundColor: completed
          ? 'var(--badge-cmd-fg, #6ee77a)'
          : 'var(--border, #333)',
      }}
    />
  );
}

// ── FileLink ──

function FileLink({ path }: { path: string }) {
  const fileName = path.split('/').pop() ?? path;

  const handleClick = useCallback(() => {
    (window as any)._navigateToFile?.(path);
  }, [path]);

  return (
    <button
      onClick={handleClick}
      className="inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[11px] font-mono transition-colors duration-100 hover:brightness-125"
      style={{
        backgroundColor: 'var(--code-bg, #1a1a1a)',
        color: 'var(--link, #6ba3f7)',
      }}
      title={path}
    >
      <svg width="12" height="12" viewBox="0 0 16 16" fill="none" className="shrink-0">
        <path
          d="M4 2h5l4 4v8a1 1 0 01-1 1H4a1 1 0 01-1-1V3a1 1 0 011-1z"
          stroke="currentColor"
          strokeWidth="1.2"
          fill="none"
        />
        <path d="M9 2v4h4" stroke="currentColor" strokeWidth="1.2" fill="none" />
      </svg>
      {fileName}
    </button>
  );
}

// ── StepComment ──

function StepComment({
  stepId,
  comment,
  onSave,
}: {
  stepId: string;
  comment?: string;
  onSave: (stepId: string, comment: string) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(comment ?? '');

  const handleSave = useCallback(() => {
    const trimmed = value.trim();
    if (trimmed) {
      onSave(stepId, trimmed);
    }
    setEditing(false);
  }, [stepId, value, onSave]);

  const handleCancel = useCallback(() => {
    setValue(comment ?? '');
    setEditing(false);
  }, [comment]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        handleSave();
      } else if (e.key === 'Escape') {
        handleCancel();
      }
    },
    [handleSave, handleCancel]
  );

  if (!editing && !comment) {
    return (
      <button
        onClick={() => setEditing(true)}
        className="mt-1 text-[11px] transition-colors duration-100"
        style={{ color: 'var(--fg-muted, #888)' }}
      >
        + Add comment
      </button>
    );
  }

  if (editing) {
    return (
      <div className="mt-1">
        <input
          type="text"
          value={value}
          onChange={e => setValue(e.target.value)}
          onKeyDown={handleKeyDown}
          onBlur={handleSave}
          autoFocus
          placeholder="Add a comment..."
          className="w-full rounded px-2 py-1 text-[11px] outline-none"
          style={{
            backgroundColor: 'var(--input-bg, #1e1e1e)',
            border: '1px solid var(--input-border, #444)',
            color: 'var(--fg, #ccc)',
          }}
        />
      </div>
    );
  }

  return (
    <button
      onClick={() => setEditing(true)}
      className="mt-1 block rounded px-2 py-0.5 text-[11px] italic transition-colors duration-100 hover:brightness-125 text-left"
      style={{
        backgroundColor: 'var(--code-bg, #1a1a1a)',
        color: 'var(--fg-secondary, #aaa)',
      }}
    >
      {comment}
    </button>
  );
}

// ── CollapsibleSection ──

function CollapsibleSection({
  title,
  children,
  defaultOpen = false,
}: {
  title: string;
  children: React.ReactNode;
  defaultOpen?: boolean;
}) {
  const [open, setOpen] = useState(defaultOpen);

  return (
    <div className="mt-2 border-t" style={{ borderColor: 'var(--border, #333)' }}>
      <button
        onClick={() => setOpen(o => !o)}
        className="flex w-full items-center gap-1.5 py-2 text-left text-[11px] font-semibold uppercase tracking-wider"
        style={{ color: 'var(--fg-muted, #888)' }}
      >
        <svg
          width="10" height="10" viewBox="0 0 16 16" fill="none"
          className="shrink-0 transition-transform duration-200"
          style={{ transform: open ? 'rotate(90deg)' : 'rotate(0deg)' }}
        >
          <path d="M6 4l4 4-4 4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        {title}
      </button>
      {open && <div className="pb-2">{children}</div>}
    </div>
  );
}

// ── PlanCard (main) ──

interface PlanCardProps {
  plan: Plan;
}

export function PlanCard({ plan }: PlanCardProps) {
  const [comments, setComments] = useState<Record<string, string>>({});

  const completedCount = plan.steps.filter(s => s.status === 'completed').length;
  const totalCount = plan.steps.length;

  const handleCommentSave = useCallback((stepId: string, comment: string) => {
    setComments(prev => ({ ...prev, [stepId]: comment }));
  }, []);

  const handleApprove = useCallback(() => {
    (window as any)._approvePlan?.();
  }, []);

  const handleRevise = useCallback(() => {
    const allComments = plan.steps
      .map(s => {
        const c = comments[s.id] ?? s.comment;
        return c ? `Step "${s.title}": ${c}` : null;
      })
      .filter(Boolean)
      .join('\n');
    (window as any)._revisePlan?.(allComments);
  }, [plan.steps, comments]);

  const hasComments = plan.steps.some(s => comments[s.id] || s.comment);

  // Derive approach from step descriptions
  const approachItems = plan.steps
    .filter(s => s.description)
    .map(s => s.description!);

  return (
    <div
      className="my-3 overflow-hidden rounded-lg border"
      style={{
        borderColor: 'var(--accent, #6366f1)',
        backgroundColor: 'var(--card-bg, #252525)',
      }}
    >
      {/* Header */}
      <div
        className="flex items-center gap-2 px-3 py-2.5"
        style={{ borderBottom: '1px solid var(--border, #333)' }}
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" className="shrink-0">
          <rect x="3" y="3" width="18" height="18" rx="3" stroke="var(--accent, #6366f1)" strokeWidth="1.5" fill="none" />
          <path d="M8 9h8M8 13h6M8 17h4" stroke="var(--accent, #6366f1)" strokeWidth="1.5" strokeLinecap="round" />
        </svg>
        <span
          className="flex-1 text-[13px] font-semibold"
          style={{ color: 'var(--fg, #ccc)' }}
        >
          {plan.title}
        </span>
        <span
          className="rounded-full px-2 py-0.5 text-[10px] font-medium"
          style={{
            backgroundColor: completedCount === totalCount
              ? 'var(--badge-cmd-bg, #1a2e1a)'
              : 'var(--code-bg, #1a1a1a)',
            color: completedCount === totalCount
              ? 'var(--badge-cmd-fg, #6ee77a)'
              : 'var(--fg-muted, #888)',
          }}
        >
          {completedCount}/{totalCount} steps
        </span>
      </div>

      {/* Steps list */}
      <div className="px-3 py-2">
        {plan.steps.map((step, i) => (
          <div key={step.id}>
            <StepRow
              step={step}
              index={i}
              comment={comments[step.id]}
              onCommentSave={handleCommentSave}
              approved={plan.approved}
            />
            {i < plan.steps.length - 1 && (
              <StepConnector completed={step.status === 'completed'} />
            )}
          </div>
        ))}
      </div>

      {/* Collapsible sections */}
      <div className="px-3">
        {approachItems.length > 0 && (
          <CollapsibleSection title="Approach">
            <ul className="space-y-1 pl-4 text-[11px]" style={{ color: 'var(--fg-secondary, #aaa)' }}>
              {approachItems.map((desc, i) => (
                <li key={i} className="list-disc">{desc}</li>
              ))}
            </ul>
          </CollapsibleSection>
        )}

        <CollapsibleSection title="Testing">
          <p className="text-[11px] italic" style={{ color: 'var(--fg-muted, #888)' }}>
            Testing details will be provided after plan approval.
          </p>
        </CollapsibleSection>
      </div>

      {/* Action buttons — only when not approved */}
      {!plan.approved && (
        <div
          className="flex items-center gap-2 px-3 py-2.5"
          style={{ borderTop: '1px solid var(--border, #333)' }}
        >
          <button
            onClick={handleApprove}
            className="flex-1 rounded-md px-3 py-1.5 text-[12px] font-medium transition-all duration-150 hover:brightness-110"
            style={{
              backgroundColor: 'var(--badge-cmd-bg, #1a2e1a)',
              color: 'var(--badge-cmd-fg, #6ee77a)',
              border: '1px solid var(--badge-cmd-fg, #6ee77a)',
            }}
          >
            Approve & Execute
          </button>
          <button
            onClick={handleRevise}
            disabled={!hasComments}
            className="flex-1 rounded-md px-3 py-1.5 text-[12px] font-medium transition-all duration-150 hover:brightness-110 disabled:opacity-40 disabled:cursor-not-allowed"
            style={{
              backgroundColor: 'var(--badge-write-bg, #3b2e1a)',
              color: 'var(--badge-write-fg, #e8a84c)',
              border: '1px solid var(--badge-write-fg, #e8a84c)',
            }}
          >
            Revise with Comments
          </button>
        </div>
      )}
    </div>
  );
}

// ── StepRow (extracted for clarity) ──

function StepRow({
  step,
  index,
  comment,
  onCommentSave,
  approved,
}: {
  step: PlanStep;
  index: number;
  comment?: string;
  onCommentSave: (stepId: string, comment: string) => void;
  approved: boolean;
}) {
  return (
    <div className="flex gap-2 py-1">
      <StepStatusIcon status={step.status} index={index} />
      <div className="flex-1 min-w-0">
        <div
          className="text-[12px] font-medium"
          style={{ color: 'var(--fg, #ccc)' }}
        >
          {step.title}
        </div>
        {step.description && (
          <div
            className="mt-0.5 text-[11px]"
            style={{ color: 'var(--fg-secondary, #aaa)' }}
          >
            {step.description}
          </div>
        )}
        {step.filePaths && step.filePaths.length > 0 && (
          <div className="mt-1 flex flex-wrap gap-1">
            {step.filePaths.map(fp => (
              <FileLink key={fp} path={fp} />
            ))}
          </div>
        )}
        {!approved && (
          <StepComment
            stepId={step.id}
            comment={comment ?? step.comment}
            onSave={onCommentSave}
          />
        )}
      </div>
    </div>
  );
}
