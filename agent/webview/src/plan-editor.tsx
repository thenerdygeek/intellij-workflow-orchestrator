import { StrictMode, useState, useCallback, useEffect } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';
import './styles/animations.css';
import { Button } from './components/ui/button';
import { Check, RotateCcw, MessageSquare, File } from 'lucide-react';

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

// ── Global entry points — called by Kotlin / showcase ────────────────────────

let rootInstance: ReturnType<typeof createRoot> | null = null;
let setPlanDataExternal: ((data: AgentPlanData) => void) | null = null;
let pendingPlanData: AgentPlanData | null = null;

(window as any).renderPlan = (json: string) => {
  const data = JSON.parse(json) as AgentPlanData;
  if (setPlanDataExternal) {
    setPlanDataExternal(data);
  } else {
    pendingPlanData = data;
  }
};

(window as any).updatePlanStep = (_stepId: string, _status: string) => {
  // Live step status updates while the agent executes.
  // The full plan is re-injected via renderPlan on each step change from Kotlin.
};

// ── Helpers ───────────────────────────────────────────────────────────────────

const ACTION_STYLES: Record<string, { bg: string; fg: string; label: string }> = {
  read:   { bg: 'var(--badge-read-bg)',   fg: 'var(--badge-read-fg)',   label: 'read'   },
  edit:   { bg: 'var(--badge-edit-bg)',   fg: 'var(--badge-edit-fg)',   label: 'edit'   },
  create: { bg: 'var(--badge-write-bg)',  fg: 'var(--badge-write-fg)',  label: 'create' },
  verify: { bg: 'var(--badge-search-bg)', fg: 'var(--badge-search-fg)', label: 'verify' },
  code:   { bg: 'var(--badge-search-bg)', fg: 'var(--badge-search-fg)', label: 'code'   },
};

function statusIcon(status?: string): { icon: string; color: string } {
  switch (status) {
    case 'completed': return { icon: '✓', color: 'var(--success)' };
    case 'running':   return { icon: '◉', color: 'var(--accent)'  };
    case 'failed':    return { icon: '✗', color: 'var(--error)'   };
    case 'skipped':   return { icon: '—', color: 'var(--fg-muted)' };
    default:          return { icon: '○', color: 'var(--fg-muted)' };
  }
}

// ── Main component ────────────────────────────────────────────────────────────

function PlanEditor() {
  const [planData, setPlanData] = useState<AgentPlanData | null>(null);
  const [comments, setComments] = useState<Record<string, string>>({});
  const [activeComment, setActiveComment] = useState<string | null>(null);

  useEffect(() => {
    setPlanDataExternal = (data: AgentPlanData) => {
      setPlanData(data);
      // Restore any pre-existing step comments
      const existing: Record<string, string> = {};
      data.steps.forEach(s => { if (s.userComment) existing[s.id] = s.userComment; });
      setComments(existing);
    };
    if (pendingPlanData) {
      setPlanDataExternal(pendingPlanData);
      pendingPlanData = null;
    }
    return () => { setPlanDataExternal = null; };
  }, []);

  const saveComment = useCallback((stepId: string, text: string) => {
    setComments(prev => ({ ...prev, [stepId]: text }));
    setActiveComment(null);
  }, []);

  const removeComment = useCallback((stepId: string) => {
    setComments(prev => { const n = { ...prev }; delete n[stepId]; return n; });
  }, []);

  const handleProceed = useCallback(() => {
    (window as any)._approvePlan?.();
  }, []);

  const handleRevise = useCallback(() => {
    if (!planData) return;
    // Send step-ID-keyed map — matches Kotlin's revisePlan(Map<String, String>)
    const payload = JSON.stringify(comments);
    (window as any)._revisePlan?.(payload);
  }, [comments, planData]);

  if (!planData) {
    return (
      <div className="flex h-screen items-center justify-center"
           style={{ backgroundColor: 'var(--bg)', color: 'var(--fg-muted)' }}>
        Loading plan…
      </div>
    );
  }

  const hasComments = Object.keys(comments).length > 0;

  return (
    <div className="min-h-screen" style={{ backgroundColor: 'var(--bg)', color: 'var(--fg)' }}>

      {/* ── Sticky header ── */}
      <div className="sticky top-0 z-10 flex items-center justify-between border-b px-6 py-3"
           style={{ backgroundColor: 'var(--bg)', borderColor: 'var(--border)' }}>
        <h1 className="text-base font-semibold truncate pr-4">
          {planData.goal || 'Implementation Plan'}
        </h1>
        <div className="flex gap-2 shrink-0">
          {hasComments && (
            <Button variant="outline" size="sm" onClick={handleRevise}>
              <RotateCcw className="h-3 w-3 mr-1" /> Revise
            </Button>
          )}
          <Button size="sm" className="glow-btn" onClick={handleProceed}>
            <Check className="h-3 w-3" /> Proceed
          </Button>
        </div>
      </div>

      {/* ── Body ── */}
      <div className="px-6 pt-5 pb-16 max-w-3xl mx-auto space-y-6">

        {/* Goal */}
        <Section label="Goal">
          <p className="text-sm leading-relaxed" style={{ color: 'var(--fg)' }}>
            {planData.goal}
          </p>
        </Section>

        {/* Approach */}
        {planData.approach && (
          <Section label="Approach">
            <p className="text-sm leading-relaxed" style={{ color: 'var(--fg)' }}>
              {planData.approach}
            </p>
          </Section>
        )}

        {/* Steps */}
        <Section label={`Steps (${planData.steps.length})`}>
          <div className="space-y-3">
            {planData.steps.map((step, idx) => {
              const { icon, color } = statusIcon(step.status);
              const actionStyle = ACTION_STYLES[step.action ?? 'code'] ?? ACTION_STYLES['code']!;
              const comment = comments[step.id];
              const isActive = activeComment === step.id;

              return (
                <div
                  key={step.id}
                  className="rounded-lg border p-4"
                  style={{ borderColor: 'var(--border)', backgroundColor: 'var(--tool-bg)' }}
                >
                  {/* Step header */}
                  <div className="flex items-start gap-3">
                    {/* Status icon */}
                    <span className="mt-0.5 text-base font-mono shrink-0 w-4 text-center"
                          style={{ color }}>
                      {icon}
                    </span>

                    <div className="flex-1 min-w-0">
                      {/* Title row */}
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className="text-[11px] font-mono shrink-0"
                              style={{ color: 'var(--fg-muted)' }}>
                          {idx + 1}.
                        </span>
                        <span className="text-sm font-medium">{step.title}</span>
                        {step.action && (
                          <span
                            className="text-[10px] px-1.5 py-0.5 rounded font-mono"
                            style={{ backgroundColor: actionStyle.bg, color: actionStyle.fg }}
                          >
                            {actionStyle.label}
                          </span>
                        )}
                      </div>

                      {/* Description */}
                      {step.description && (
                        <p className="mt-1.5 text-[13px] leading-relaxed"
                           style={{ color: 'var(--fg-secondary)' }}>
                          {step.description}
                        </p>
                      )}

                      {/* Files */}
                      {step.files && step.files.length > 0 && (
                        <div className="mt-2 flex flex-wrap gap-1.5">
                          {step.files.map(f => (
                            <button
                              key={f}
                              onClick={() => (window as any)._openFile?.(f)}
                              className="inline-flex items-center gap-1 text-[11px] px-2 py-0.5 rounded font-mono hover:opacity-80 transition-opacity"
                              style={{
                                backgroundColor: 'var(--code-bg)',
                                color: 'var(--accent-read)',
                                border: '1px solid var(--border)',
                              }}
                            >
                              <File className="h-2.5 w-2.5" />
                              {f.split('/').pop()}
                            </button>
                          ))}
                        </div>
                      )}

                      {/* Comment bubble */}
                      {comment && !isActive && (
                        <div
                          className="mt-2 text-[12px] px-3 py-1.5 rounded border-l-2 flex items-start gap-2"
                          style={{
                            color: 'var(--accent-edit)',
                            borderColor: 'var(--accent-edit)',
                            backgroundColor: 'rgba(220,220,170,0.06)',
                          }}
                        >
                          <MessageSquare className="h-3 w-3 mt-0.5 shrink-0" />
                          <span className="flex-1">{comment}</span>
                          <button
                            onClick={() => removeComment(step.id)}
                            className="opacity-50 hover:opacity-100 transition-opacity text-[10px]"
                          >
                            ×
                          </button>
                        </div>
                      )}

                      {/* Inline comment editor */}
                      {isActive && (
                        <CommentEditor
                          value={comment ?? ''}
                          onSave={text => saveComment(step.id, text)}
                          onCancel={() => setActiveComment(null)}
                        />
                      )}
                    </div>

                    {/* Comment toggle button */}
                    {!isActive && (
                      <button
                        onClick={() => setActiveComment(step.id)}
                        className="shrink-0 mt-0.5 h-6 w-6 rounded flex items-center justify-center opacity-30 hover:opacity-70 transition-opacity"
                        title="Add revision note"
                        style={{ color: 'var(--fg-muted)' }}
                      >
                        <MessageSquare className="h-3.5 w-3.5" />
                      </button>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </Section>

        {/* Testing / Verification */}
        {planData.testing && (
          <Section label="Verification">
            <p className="text-sm leading-relaxed" style={{ color: 'var(--fg)' }}>
              {planData.testing}
            </p>
          </Section>
        )}

      </div>
    </div>
  );
}

// ── Small helpers ─────────────────────────────────────────────────────────────

function Section({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <p className="text-[11px] font-semibold uppercase tracking-wider mb-2"
         style={{ color: 'var(--fg-muted)' }}>
        {label}
      </p>
      {children}
    </div>
  );
}

function CommentEditor({
  value,
  onSave,
  onCancel,
}: {
  value: string;
  onSave: (text: string) => void;
  onCancel: () => void;
}) {
  const [text, setText] = useState(value);
  return (
    <div className="mt-2 flex gap-1">
      <input
        autoFocus
        value={text}
        onChange={e => setText(e.target.value)}
        onKeyDown={e => {
          if (e.key === 'Enter') onSave(text);
          if (e.key === 'Escape') onCancel();
        }}
        placeholder="Add revision note…"
        className="flex-1 text-xs px-2 py-1 rounded border bg-transparent outline-none"
        style={{ borderColor: 'var(--border)', color: 'var(--fg)' }}
      />
      <Button variant="ghost" size="sm" className="h-6 text-[10px]" onClick={() => onSave(text)}>
        Save
      </Button>
      <Button variant="ghost" size="sm" className="h-6 text-[10px]" onClick={onCancel}>
        Cancel
      </Button>
    </div>
  );
}

// ── Bootstrap ─────────────────────────────────────────────────────────────────

const container = document.getElementById('root');
if (container) {
  rootInstance = createRoot(container);
  rootInstance.render(<StrictMode><PlanEditor /></StrictMode>);
}
