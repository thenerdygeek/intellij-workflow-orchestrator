import { StrictMode, useState, useCallback, useEffect, useMemo, useRef } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';
import './styles/animations.css';
import { Button } from './components/ui/button';
import { Check, RotateCcw, Loader2 } from 'lucide-react';
import { PlanDocumentViewer } from './components/plan/PlanDocumentViewer';
import type { LineComment } from './components/plan/PlanDocumentViewer';

// ── Data model — mirrors PlanJson from Kotlin ────────────────────────────────

interface AgentPlanData {
  goal: string;
  approach?: string;
  testing?: string;
  approved?: boolean;
  markdown?: string;
  title?: string;
}

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

let triggerReviseInternal: (() => void) | null = null;

// Synchronize pending state from chat card or Kotlin
(window as any).setPlanPending = (state: 'approve' | 'revise' | null) => {
  setPendingExternal?.(state);
};

// Called from Kotlin (AgentPlanEditor.triggerRevise()) when user clicks Revise on the chat card
(window as any).triggerReviseFromHost = () => {
  triggerReviseInternal?.();
};

// ── Main component ────────────────────────────────────────────────────────────

function PlanEditor() {
  const [planData, setPlanData] = useState<AgentPlanData | null>(null);
  const [comments, setComments] = useState<LineComment[]>([]);
  const [pending, setPending] = useState<'approve' | 'revise' | null>(null);
  const bodyRef = useRef<HTMLDivElement>(null);

  // Wire external state setters
  useEffect(() => {
    setPlanDataExternal = (data: AgentPlanData) => {
      setPlanData(data);
      // Reset pending state when new plan arrives (revision cycle complete)
      setPending(null);
      // Clear comments on new plan
      setComments([]);
    };

    // Wire pending state sync
    setPendingExternal = (state) => setPending(state);

    if (pendingPlanData) {
      setPlanDataExternal(pendingPlanData);
      pendingPlanData = null;
    }
    return () => {
      setPlanDataExternal = null;
      setPendingExternal = null;
      triggerReviseInternal = null;
    };
  }, []);

  // Generate markdown from plan data (markdown field is always present in production)
  const markdown = useMemo(() => {
    if (!planData) return '';
    return planData.markdown ?? '';
  }, [planData]);

  const handleAddComment = useCallback((lineNumber: number, text: string, lineContent: string) => {
    setComments(prev => {
      const filtered = prev.filter(c => c.lineNumber !== lineNumber);
      const updated = [...filtered, { lineNumber, text, lineContent }];
      // Notify Kotlin → chat panel about comment count change
      (window as any)._onCommentCountChanged?.(updated.length);
      return updated;
    });
  }, []);

  const handleRemoveComment = useCallback((lineNumber: number) => {
    setComments(prev => {
      const updated = prev.filter(c => c.lineNumber !== lineNumber);
      (window as any)._onCommentCountChanged?.(updated.length);
      return updated;
    });
  }, []);

  const handleProceed = useCallback(() => {
    setPending('approve');
    (window as any)._approvePlan?.();
  }, []);

  const handleRevise = useCallback(() => {
    if (!planData) return;
    setPending('revise');
    const payload = JSON.stringify({
      comments: comments.map(c => ({
        line: c.lineNumber,
        content: c.lineContent,
        comment: c.text,
      })),
      markdown,
    });
    (window as any)._revisePlan?.(payload);
  }, [comments, planData, markdown]);

  // Wire triggerRevise from host (chat card's Revise button)
  useEffect(() => {
    triggerReviseInternal = () => { handleRevise(); };
    return () => { triggerReviseInternal = null; };
  }, [handleRevise]);

  const hasComments = comments.length > 0;
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
            {planData.title || 'Implementation Plan'}
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
              {comments.length} comment{comments.length !== 1 ? 's' : ''}
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
                  <><RotateCcw className="h-3 w-3 mr-1" /> Revise ({comments.length})</>
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

      {/* ── Body: PlanDocumentViewer ── */}
      <div ref={bodyRef} className="pb-16">
        <PlanDocumentViewer
          markdown={markdown}
          comments={comments}
          showLineNumbers={!isApproved}
          onComment={!isApproved ? handleAddComment : undefined}
          onRemoveComment={!isApproved ? handleRemoveComment : undefined}
        />
      </div>
    </div>
  );
}

// ── Bootstrap ─────────────────────────────────────────────────────────────────

const container = document.getElementById('root');
if (container) {
  rootInstance = createRoot(container);
  rootInstance.render(<StrictMode><PlanEditor /></StrictMode>);
}
