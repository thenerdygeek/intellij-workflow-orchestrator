import { StrictMode, useState, useCallback, useEffect, useMemo, useRef } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';
import './styles/animations.css';
import { Button } from './components/ui/button';
import { Check, RotateCcw, MessageSquare, Loader2, Plus, X } from 'lucide-react';
import { MarkdownRenderer } from './components/markdown/MarkdownRenderer';

// ── Data model — mirrors AgentPlan / PlanStep from Kotlin ─────────────────────

interface PlanStep {
  id: string;
  title: string;
  description?: string;
  files?: string[];
  action?: string;   // read | edit | create | verify | code
  status?: string;   // pending | running | completed | failed | skipped
  userComment?: string;
}

interface AgentPlanData {
  goal: string;
  approach?: string;
  steps: PlanStep[];
  testing?: string;
  approved?: boolean;
}

// Section IDs for comments
type SectionId = 'goal' | 'approach' | 'testing' | `step-${number}`;

// ── Global entry points — called by Kotlin / showcase ────────────────────────

let rootInstance: ReturnType<typeof createRoot> | null = null;
let setPlanDataExternal: ((data: AgentPlanData) => void) | null = null;
let setPendingExternal: ((state: 'approve' | 'revise' | null) => void) | null = null;
let pendingPlanData: AgentPlanData | null = null;

// Called from AgentPlanEditor.kt to set IDE theme CSS variables before rendering.
(window as any).applyTheme = (vars: Record<string, string>) => {
  const root = document.documentElement;
  Object.entries(vars).forEach(([k, v]) => root.style.setProperty('--' + k, v));
};

(window as any).renderPlan = (json: string) => {
  const data = JSON.parse(json) as AgentPlanData;
  if (setPlanDataExternal) {
    setPlanDataExternal(data);
  } else {
    pendingPlanData = data;
  }
};

(window as any).updatePlanStep = (stepId: string, status: string) => {
  if (setPlanDataExternal) {
    // Use the external setter to trigger a re-render with updated step status
    setPlanDataExternal(null as any); // trigger re-read via internal update
  }
  // Direct state update via dedicated mechanism
  updateStepStatusInternal?.(stepId, status);
};

let updateStepStatusInternal: ((stepId: string, status: string) => void) | null = null;

// Synchronize pending state from chat card or Kotlin
(window as any).setPlanPending = (state: 'approve' | 'revise' | null) => {
  setPendingExternal?.(state);
};

// ── Helpers ───────────────────────────────────────────────────────────────────

function statusEmoji(status?: string): string {
  switch (status) {
    case 'completed':
    case 'done':
      return '\u2705';  // green check
    case 'running':
    case 'in_progress':
      return '\u23f3';  // hourglass
    case 'failed':
      return '\u274c';  // red x
    case 'skipped':
      return '\u23ed\ufe0f';  // skip
    default:
      return '\u2b55';  // hollow circle
  }
}

function statusLabel(status?: string): string {
  switch (status) {
    case 'completed':
    case 'done':
      return 'Completed';
    case 'running':
    case 'in_progress':
      return 'Running';
    case 'failed':
      return 'Failed';
    case 'skipped':
      return 'Skipped';
    default:
      return 'Pending';
  }
}

/** Convert plan data into a markdown string for rendering. */
function planToMarkdown(plan: AgentPlanData): string {
  const lines: string[] = [];

  lines.push(`## Goal`);
  lines.push(plan.goal);
  lines.push('');

  if (plan.approach) {
    lines.push(`## Approach`);
    lines.push(plan.approach);
    lines.push('');
  }

  lines.push(`## Steps`);
  lines.push('');
  plan.steps.forEach((step, idx) => {
    const emoji = statusEmoji(step.status);
    lines.push(`### ${idx + 1}. ${step.title}`);
    if (step.description) {
      lines.push(step.description);
    }
    if (step.files && step.files.length > 0) {
      lines.push('');
      lines.push(`**Files:** ${step.files.map(f => `\`${f}\``).join(', ')}`);
    }
    lines.push(`**Status:** ${emoji} ${statusLabel(step.status)}`);
    lines.push('');
  });

  if (plan.testing) {
    lines.push(`## Testing & Verification`);
    lines.push(plan.testing);
    lines.push('');
  }

  return lines.join('\n');
}

// ── Section overlay for comments ─────────────────────────────────────────────

/** Map section IDs to the plan data sections. */
function getSectionIds(plan: AgentPlanData): SectionId[] {
  const ids: SectionId[] = ['goal'];
  if (plan.approach) ids.push('approach');
  plan.steps.forEach((_, idx) => ids.push(`step-${idx}`));
  if (plan.testing) ids.push('testing');
  return ids;
}

function sectionLabel(id: SectionId): string {
  if (id === 'goal') return 'Goal';
  if (id === 'approach') return 'Approach';
  if (id === 'testing') return 'Testing & Verification';
  if (id.startsWith('step-')) return `Step ${parseInt(id.split('-')[1]!) + 1}`;
  return id;
}

// ── Main component ────────────────────────────────────────────────────────────

function PlanEditor() {
  const [planData, setPlanData] = useState<AgentPlanData | null>(null);
  const [comments, setComments] = useState<Record<string, string>>({});
  const [activeComment, setActiveComment] = useState<SectionId | null>(null);
  const [pending, setPending] = useState<'approve' | 'revise' | null>(null);
  const bodyRef = useRef<HTMLDivElement>(null);

  // Wire external state setters
  useEffect(() => {
    setPlanDataExternal = (data: AgentPlanData) => {
      setPlanData(data);
      // Reset pending state when new plan arrives (revision cycle complete)
      setPending(null);
      // Restore any pre-existing step comments
      const existing: Record<string, string> = {};
      data.steps.forEach((s, idx) => {
        if (s.userComment) existing[`step-${idx}`] = s.userComment;
      });
      setComments(existing);
    };

    // Wire the step status updater
    updateStepStatusInternal = (stepId: string, status: string) => {
      setPlanData(prev => {
        if (!prev) return prev;
        return {
          ...prev,
          steps: prev.steps.map(s => s.id === stepId ? { ...s, status } : s)
        };
      });
    };

    // Wire pending state sync
    setPendingExternal = (state) => setPending(state);

    if (pendingPlanData) {
      setPlanDataExternal(pendingPlanData);
      pendingPlanData = null;
    }
    return () => {
      setPlanDataExternal = null;
      updateStepStatusInternal = null;
      setPendingExternal = null;
    };
  }, []);

  const saveComment = useCallback((sectionId: SectionId, text: string) => {
    if (text.trim()) {
      setComments(prev => ({ ...prev, [sectionId]: text.trim() }));
    } else {
      setComments(prev => { const n = { ...prev }; delete n[sectionId]; return n; });
    }
    setActiveComment(null);
  }, []);

  const removeComment = useCallback((sectionId: SectionId) => {
    setComments(prev => { const n = { ...prev }; delete n[sectionId]; return n; });
  }, []);

  const handleProceed = useCallback(() => {
    setPending('approve');
    (window as any)._approvePlan?.();
  }, []);

  const handleRevise = useCallback(() => {
    if (!planData) return;
    setPending('revise');
    // Send section-keyed comments to Kotlin
    const payload = JSON.stringify(comments);
    (window as any)._revisePlan?.(payload);
  }, [comments, planData]);

  // Generate markdown from plan
  const markdown = useMemo(() => {
    if (!planData) return '';
    return planToMarkdown(planData);
  }, [planData]);

  // Compute section IDs for comment buttons
  const sectionIds = useMemo(() => {
    if (!planData) return [];
    return getSectionIds(planData);
  }, [planData]);

  const hasComments = Object.keys(comments).length > 0;
  const isApproved = planData?.approved === true;

  if (!planData) {
    return (
      <div className="flex h-screen items-center justify-center"
           style={{ backgroundColor: 'var(--bg)', color: 'var(--fg-muted)' }}>
        Loading plan...
      </div>
    );
  }

  return (
    <div className="min-h-screen" style={{ backgroundColor: 'var(--bg)', color: 'var(--fg)' }}>

      {/* ── Sticky header ── */}
      <div className="sticky top-0 z-10 flex items-center justify-between border-b px-6 py-3"
           style={{ backgroundColor: 'var(--bg)', borderColor: 'var(--border)' }}>
        <div className="flex items-center gap-3 min-w-0 pr-4">
          <h1 className="text-base font-semibold truncate">
            Implementation Plan
          </h1>
          {isApproved && (
            <span className="text-[10px] px-2 py-0.5 rounded-full font-medium"
                  style={{ backgroundColor: 'rgba(34,197,94,0.15)', color: 'var(--success, #22c55e)' }}>
              Approved
            </span>
          )}
          {!isApproved && hasComments && (
            <span className="text-[10px] px-2 py-0.5 rounded-full font-medium"
                  style={{ backgroundColor: 'rgba(234,179,8,0.15)', color: 'var(--warning, #eab308)' }}>
              {Object.keys(comments).length} comment{Object.keys(comments).length !== 1 ? 's' : ''}
            </span>
          )}
        </div>
        {!isApproved && (
          <div className="flex gap-2 shrink-0">
            {hasComments && (
              <Button variant="outline" size="sm" onClick={handleRevise} disabled={pending !== null}>
                {pending === 'revise' ? (
                  <><Loader2 className="h-3 w-3 mr-1 animate-spin" /> Processing...</>
                ) : (
                  <><RotateCcw className="h-3 w-3 mr-1" /> Revise ({Object.keys(comments).length})</>
                )}
              </Button>
            )}
            <Button size="sm" className="glow-btn" onClick={handleProceed} disabled={pending !== null}>
              {pending === 'approve' ? (
                <><Loader2 className="h-3 w-3 animate-spin" /> Approving...</>
              ) : (
                <><Check className="h-3 w-3" /> Proceed</>
              )}
            </Button>
          </div>
        )}
      </div>

      {/* ── Body: markdown + comment annotations ── */}
      <div ref={bodyRef} className="px-6 pt-5 pb-16 max-w-3xl mx-auto">

        {/* Rendered plan as markdown */}
        <div className="plan-markdown-body">
          <MarkdownRenderer content={markdown} />
        </div>

        {/* ── Comment section ── */}
        {!isApproved && (
          <div className="mt-8 border-t pt-6" style={{ borderColor: 'var(--border)' }}>
            <p className="text-[11px] font-semibold uppercase tracking-wider mb-3"
               style={{ color: 'var(--fg-muted)' }}>
              Revision Comments
            </p>

            {/* Existing comments */}
            {Object.entries(comments).map(([id, text]) => (
              <CommentBubble
                key={id}
                sectionLabel={sectionLabel(id as SectionId)}
                text={text}
                onEdit={() => setActiveComment(id as SectionId)}
                onRemove={() => removeComment(id as SectionId)}
              />
            ))}

            {/* Active comment editor */}
            {activeComment && (
              <CommentEditor
                sectionLabel={sectionLabel(activeComment)}
                value={comments[activeComment] ?? ''}
                onSave={text => saveComment(activeComment, text)}
                onCancel={() => setActiveComment(null)}
              />
            )}

            {/* Add comment buttons — one per section */}
            {!activeComment && (
              <div className="flex flex-wrap gap-1.5 mt-3">
                {sectionIds.map(id => (
                  <button
                    key={id}
                    onClick={() => setActiveComment(id)}
                    className="inline-flex items-center gap-1 text-[11px] px-2.5 py-1 rounded-md border transition-all hover:border-[var(--accent)]"
                    style={{
                      borderColor: comments[id] ? 'var(--accent)' : 'var(--border)',
                      color: comments[id] ? 'var(--accent)' : 'var(--fg-muted)',
                      backgroundColor: comments[id] ? 'rgba(99,102,241,0.08)' : 'transparent',
                    }}
                  >
                    {comments[id] ? (
                      <><MessageSquare className="h-3 w-3" /> {sectionLabel(id)}</>
                    ) : (
                      <><Plus className="h-3 w-3" /> {sectionLabel(id)}</>
                    )}
                  </button>
                ))}
              </div>
            )}

            {!hasComments && !activeComment && (
              <p className="text-[12px] mt-2" style={{ color: 'var(--fg-muted)' }}>
                Click a section above to add revision feedback. Comments will be sent to the LLM when you click Revise.
              </p>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

// ── Small helpers ─────────────────────────────────────────────────────────────

function CommentEditor({
  sectionLabel: label,
  value,
  onSave,
  onCancel,
}: {
  sectionLabel: string;
  value: string;
  onSave: (text: string) => void;
  onCancel: () => void;
}) {
  const [text, setText] = useState(value);
  return (
    <div className="mt-2 rounded-lg border p-3" style={{ borderColor: 'var(--accent)', backgroundColor: 'rgba(99,102,241,0.05)' }}>
      <p className="text-[11px] font-medium mb-1.5" style={{ color: 'var(--accent)' }}>
        Comment on: {label}
      </p>
      <textarea
        autoFocus
        value={text}
        onChange={e => setText(e.target.value)}
        onKeyDown={e => {
          if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) onSave(text);
          if (e.key === 'Escape') onCancel();
        }}
        placeholder="Describe what should change..."
        rows={3}
        className="w-full text-xs px-2 py-1.5 rounded border bg-transparent outline-none resize-y"
        style={{ borderColor: 'var(--border)', color: 'var(--fg)' }}
      />
      <div className="flex gap-1 mt-1.5 justify-end">
        <Button variant="ghost" size="sm" className="h-6 text-[10px]" onClick={onCancel}>
          Cancel
        </Button>
        <Button size="sm" className="h-6 text-[10px]" onClick={() => onSave(text)}>
          Save
        </Button>
      </div>
    </div>
  );
}

function CommentBubble({
  sectionLabel: label,
  text,
  onEdit,
  onRemove,
}: {
  sectionLabel: string;
  text: string;
  onEdit: () => void;
  onRemove: () => void;
}) {
  return (
    <div
      className="mb-2 text-[12px] px-3 py-2 rounded-lg border-l-2 flex items-start gap-2 group cursor-pointer hover:opacity-90 transition-opacity"
      style={{
        color: 'var(--fg)',
        borderColor: 'var(--accent)',
        backgroundColor: 'rgba(99,102,241,0.06)',
      }}
      onClick={onEdit}
    >
      <MessageSquare className="h-3 w-3 mt-0.5 shrink-0" style={{ color: 'var(--accent)' }} />
      <div className="flex-1 min-w-0">
        <span className="text-[10px] font-medium" style={{ color: 'var(--accent)' }}>{label}</span>
        <p className="mt-0.5">{text}</p>
      </div>
      <button
        onClick={(e) => { e.stopPropagation(); onRemove(); }}
        className="opacity-0 group-hover:opacity-70 hover:!opacity-100 transition-opacity shrink-0"
        title="Remove comment"
      >
        <X className="h-3 w-3" />
      </button>
    </div>
  );
}

// ── Bootstrap ─────────────────────────────────────────────────────────────────

const container = document.getElementById('root');
if (container) {
  rootInstance = createRoot(container);
  rootInstance.render(<StrictMode><PlanEditor /></StrictMode>);
}
