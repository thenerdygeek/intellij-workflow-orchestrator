import { StrictMode, useState, useCallback, useEffect } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';
import './styles/animations.css';
import { Button } from './components/ui/button';
import { MessageSquare, Check, RotateCcw } from 'lucide-react';

interface PlanData {
  title: string;
  markdown: string;
  approved: boolean;
  comments: Record<string, string>;
}

let rootInstance: ReturnType<typeof createRoot> | null = null;

// Global setter — called from React state updater
let setPlanDataExternal: ((data: PlanData) => void) | null = null;

(window as any).renderPlan = (json: string) => {
  const data = JSON.parse(json) as PlanData;
  if (setPlanDataExternal) {
    setPlanDataExternal(data);
  }
};

(window as any).updatePlanStep = (_stepId: string, _status: string) => {
  // Handled by re-render from Kotlin
};

function PlanEditor() {
  const [planData, setPlanData] = useState<PlanData | null>(null);
  const [comments, setComments] = useState<Record<string, string>>({});
  const [activeComment, setActiveComment] = useState<string | null>(null);

  // Expose state setter for the global renderPlan function
  useEffect(() => {
    setPlanDataExternal = (data: PlanData) => {
      setPlanData(data);
      setComments(data.comments ?? {});
    };
    return () => { setPlanDataExternal = null; };
  }, []);

  const addComment = useCallback((lineId: string, text: string) => {
    setComments(prev => ({ ...prev, [lineId]: text }));
    setActiveComment(null);
  }, []);

  const handleProceed = useCallback(() => {
    (window as any)._approvePlan?.();
  }, []);

  const handleRevise = useCallback(() => {
    const json = JSON.stringify(comments);
    (window as any)._revisePlan?.(json);
  }, [comments]);

  // Loading state
  if (!planData) {
    return (
      <div className="flex h-screen items-center justify-center" style={{ backgroundColor: 'var(--bg)', color: 'var(--fg-muted)' }}>
        Loading plan...
      </div>
    );
  }

  const lines = planData.markdown.split('\n');
  const hasComments = Object.keys(comments).length > 0;

  return (
    <div className="min-h-screen" style={{ backgroundColor: 'var(--bg)', color: 'var(--fg)' }}>
      {/* Sticky header */}
      <div className="sticky top-0 z-10 flex items-center justify-between border-b px-6 py-3"
           style={{ backgroundColor: 'var(--bg)', borderColor: 'var(--border)' }}>
        <h1 className="text-base font-semibold">{planData.title || 'Implementation Plan'}</h1>
        <div className="flex gap-2">
          {hasComments && (
            <Button variant="outline" size="sm" onClick={handleRevise}>
              <RotateCcw className="h-3 w-3 mr-1" /> Revise
            </Button>
          )}
          <Button size="sm" onClick={handleProceed}>
            <Check className="h-3 w-3 mr-1" /> Proceed
          </Button>
        </div>
      </div>

      {/* Plan content with comment gutters */}
      <div className="px-6 py-4 max-w-4xl mx-auto">
        {lines.map((line, idx) => {
          const lineId = `line-${idx}`;
          const comment = comments[lineId];

          return (
            <div key={idx} className="group relative flex">
              {/* Comment gutter */}
              <div className="w-8 shrink-0 flex justify-center pt-1">
                {comment ? (
                  <button
                    onClick={() => setActiveComment(lineId)}
                    className="h-4 w-4 rounded-full flex items-center justify-center"
                    style={{ backgroundColor: 'var(--accent-edit)', color: 'var(--bg)' }}
                  >
                    <MessageSquare className="h-2.5 w-2.5" />
                  </button>
                ) : (
                  <button
                    onClick={() => setActiveComment(lineId)}
                    className="h-4 w-4 rounded-full flex items-center justify-center opacity-0 group-hover:opacity-40 transition-opacity"
                    style={{ color: 'var(--fg-muted)' }}
                  >
                    +
                  </button>
                )}
              </div>

              {/* Line content */}
              <div className="flex-1 py-0.5">
                <MarkdownLine content={line} />
                {/* Inline comment editor */}
                {activeComment === lineId && (
                  <CommentEditor
                    value={comment ?? ''}
                    onSave={(text) => addComment(lineId, text)}
                    onCancel={() => setActiveComment(null)}
                  />
                )}
                {/* Show existing comment */}
                {comment && activeComment !== lineId && (
                  <div
                    className="mt-1 ml-2 text-xs px-2 py-1 rounded border-l-2"
                    style={{ color: 'var(--accent-edit)', borderColor: 'var(--accent-edit)', backgroundColor: 'rgba(245,158,11,0.05)' }}
                  >
                    {comment}
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function MarkdownLine({ content }: { content: string }) {
  if (content.startsWith('### ')) return <h3 className="text-base font-semibold mt-4 mb-1">{content.slice(4)}</h3>;
  if (content.startsWith('## ')) return <h2 className="text-lg font-bold mt-6 mb-2">{content.slice(3)}</h2>;
  if (content.startsWith('# ')) return <h1 className="text-xl font-bold mt-6 mb-2">{content.slice(2)}</h1>;
  if (content.startsWith('- [ ] ')) return <div className="flex gap-2 ml-4"><span style={{ color: 'var(--fg-muted)' }}>&#9744;</span><span>{content.slice(6)}</span></div>;
  if (content.startsWith('- [x] ')) return <div className="flex gap-2 ml-4"><span style={{ color: 'var(--success)' }}>&#9745;</span><span className="line-through opacity-60">{content.slice(6)}</span></div>;
  if (content.startsWith('- ')) return <div className="ml-4">&bull; {content.slice(2)}</div>;
  if (content.trim() === '') return <div className="h-3" />;
  if (content.startsWith('```')) return <div className="font-mono text-xs" style={{ color: 'var(--fg-muted)' }}>{content}</div>;
  return <div className="text-sm leading-relaxed">{content}</div>;
}

function CommentEditor({ value, onSave, onCancel }: { value: string; onSave: (text: string) => void; onCancel: () => void }) {
  const [text, setText] = useState(value);
  return (
    <div className="mt-1 ml-2 flex gap-1">
      <input
        autoFocus
        value={text}
        onChange={e => setText(e.target.value)}
        onKeyDown={e => { if (e.key === 'Enter') onSave(text); if (e.key === 'Escape') onCancel(); }}
        placeholder="Add comment..."
        className="flex-1 text-xs px-2 py-1 rounded border bg-transparent outline-none"
        style={{ borderColor: 'var(--border)', color: 'var(--fg)' }}
      />
      <Button variant="ghost" size="sm" className="h-6 text-[10px]" onClick={() => onSave(text)}>Save</Button>
      <Button variant="ghost" size="sm" className="h-6 text-[10px]" onClick={onCancel}>Cancel</Button>
    </div>
  );
}

// Initialize root
const container = document.getElementById('root');
if (container) {
  rootInstance = createRoot(container);
  rootInstance.render(<StrictMode><PlanEditor /></StrictMode>);
}
