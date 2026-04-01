import { memo, useMemo, useState, useCallback } from 'react';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeRaw from 'rehype-raw';
import { CodeBlock } from '@/components/markdown/CodeBlock';
import './plan-document.css';

export interface LineComment {
  lineNumber: number;
  text: string;
  lineContent: string;
}

interface PlanDocumentViewerProps {
  markdown: string;
  comments?: LineComment[];
  showLineNumbers?: boolean;
  onComment?: (lineNumber: number, text: string, lineContent: string) => void;
  onRemoveComment?: (lineNumber: number) => void;
  stepStatuses?: Record<string, string>; // stepId -> status for emoji overlay
}

export const PlanDocumentViewer = memo(function PlanDocumentViewer({
  markdown,
  comments = [],
  showLineNumbers = true,
  onComment,
  onRemoveComment,
}: PlanDocumentViewerProps) {
  // Split markdown into lines for line numbering
  const lines = useMemo(() => markdown.split('\n'), [markdown]);
  const [activeCommentLine, setActiveCommentLine] = useState<number | null>(null);
  const [commentDraft, setCommentDraft] = useState('');
  const commentsByLine = useMemo(() => {
    const map = new Map<number, LineComment>();
    comments.forEach(c => map.set(c.lineNumber, c));
    return map;
  }, [comments]);

  const handleSubmitComment = useCallback((lineNumber: number) => {
    if (commentDraft.trim() && onComment) {
      onComment(lineNumber, commentDraft.trim(), lines[lineNumber - 1] ?? '');
      setCommentDraft('');
      setActiveCommentLine(null);
    }
  }, [commentDraft, onComment, lines]);

  // Render the full markdown as a document with line-numbered wrapper
  return (
    <div className="plan-document">
      {/* Rendered markdown with document typography */}
      <div className="plan-document-body">
        <Markdown
          remarkPlugins={[remarkGfm]}
          rehypePlugins={[rehypeRaw]}
          components={createDocumentComponents()}
        >
          {markdown}
        </Markdown>
      </div>

      {/* Line-numbered overlay for comments */}
      {showLineNumbers && (
        <div className="plan-line-overlay">
          {lines.map((_, idx) => {
            const lineNum = idx + 1;
            const comment = commentsByLine.get(lineNum);
            const isActive = activeCommentLine === lineNum;

            return (
              <div key={lineNum} className="plan-line-row" data-line-number={lineNum}>
                {/* Gutter with line number + comment button */}
                <div className="plan-line-gutter">
                  <span className="plan-line-number">{lineNum}</span>
                  {onComment && !comment && (
                    <button
                      className="plan-line-comment-btn"
                      onClick={() => { setActiveCommentLine(isActive ? null : lineNum); setCommentDraft(''); }}
                      title="Add comment"
                    >+</button>
                  )}
                </div>

                {/* Inline comment editor */}
                {isActive && (
                  <div className="plan-comment-editor">
                    <textarea
                      value={commentDraft}
                      onChange={e => setCommentDraft(e.target.value)}
                      placeholder="Add your comment..."
                      autoFocus
                      onKeyDown={e => {
                        if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) handleSubmitComment(lineNum);
                        if (e.key === 'Escape') setActiveCommentLine(null);
                      }}
                    />
                    <div className="plan-comment-actions">
                      <button onClick={() => handleSubmitComment(lineNum)}>Comment</button>
                      <button onClick={() => setActiveCommentLine(null)}>Cancel</button>
                    </div>
                  </div>
                )}

                {/* Existing comment bubble */}
                {comment && !isActive && (
                  <div className="plan-comment-bubble">
                    <span className="plan-comment-text">{comment.text}</span>
                    {onRemoveComment && (
                      <button className="plan-comment-remove" onClick={() => onRemoveComment(lineNum)}>x</button>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
});

// Document-optimized markdown components (different from chat MarkdownRenderer)
function createDocumentComponents(): Record<string, React.ComponentType<any>> {
  return {
    // Code blocks with syntax highlighting
    code({ className, children, ...props }: any) {
      const isBlock = className?.startsWith('language-');
      if (isBlock) {
        const language = className?.replace('language-', '') ?? '';
        const code = String(children).replace(/\n$/, '');
        return <CodeBlock code={code} language={language} isStreaming={false} />;
      }
      return <code className="plan-inline-code" {...props}>{children}</code>;
    },
    // Task list items with checkboxes
    li({ children, className, ...props }: any) {
      const isTask = className === 'task-list-item';
      return <li className={isTask ? 'plan-task-item' : undefined} {...props}>{children}</li>;
    },
    input({ type, checked, ...props }: any) {
      if (type === 'checkbox') {
        return <span className="task-checkbox">{checked ? '\u2611' : '\u2610'}</span>;
      }
      return <input type={type} checked={checked} {...props} />;
    },
    // Links open in IDE
    a({ href, children, ...props }: any) {
      return (
        <a href={href} onClick={(e: React.MouseEvent) => {
          e.preventDefault();
          if (href) (window as any)._openFile?.(href);
        }} {...props}>{children}</a>
      );
    },
  };
}
