import { memo, useMemo, useState, useCallback, useRef, useEffect } from 'react';
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

/**
 * Groups consecutive source lines into blocks that must be rendered together
 * as a single markdown unit (e.g., fenced code blocks, tables, lists).
 * Each block knows its start line number and raw content.
 */
interface LineBlock {
  startLine: number; // 1-based
  endLine: number;   // 1-based, inclusive
  content: string;   // raw markdown lines joined with \n
  type: 'normal' | 'code' | 'table';
}

/**
 * Returns true if the line is a "block-start" that should begin a new block.
 * Headings, HRs, and blank lines break the flow.
 */
function isBlockBreaker(line: string): boolean {
  return /^#{1,6}\s/.test(line) || /^---+$/.test(line) || /^===+$/.test(line);
}

/**
 * Returns true if the line is a continuation of the previous content block
 * (i.e., NOT a heading, NOT a blank line, NOT a fence/table start).
 */
function isContinuation(line: string): boolean {
  if (line.trim() === '') return false;
  if (isBlockBreaker(line)) return false;
  if (/^(`{3,}|~{3,})/.test(line)) return false;
  if (/^\|/.test(line)) return false;
  return true;
}

function groupLinesIntoBlocks(lines: string[]): LineBlock[] {
  const blocks: LineBlock[] = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i]!;

    // Skip blank lines — they act as separators, don't create blocks
    if (line.trim() === '') {
      i++;
      continue;
    }

    // Fenced code block: ``` or ~~~
    if (/^(`{3,}|~{3,})/.test(line)) {
      const fence = line.match(/^(`{3,}|~{3,})/)?.[0] ?? '```';
      const fenceChar = fence.charAt(0);
      const fenceLen = fence.length;
      const start = i;
      i++;
      while (i < lines.length && !(lines[i]!.startsWith(fenceChar.repeat(fenceLen)))) {
        i++;
      }
      if (i < lines.length) i++; // include closing fence
      blocks.push({
        startLine: start + 1,
        endLine: i,
        content: lines.slice(start, i).join('\n'),
        type: 'code',
      });
      continue;
    }

    // Table: line starts with | and next line is separator (|---|)
    const nextLine = i + 1 < lines.length ? lines[i + 1]! : '';
    if (/^\|/.test(line) && /^\|[\s\-:|]+\|/.test(nextLine)) {
      const start = i;
      while (i < lines.length && /^\|/.test(lines[i]!)) {
        i++;
      }
      blocks.push({
        startLine: start + 1,
        endLine: i,
        content: lines.slice(start, i).join('\n'),
        type: 'table',
      });
      continue;
    }

    // Heading — always its own block
    if (isBlockBreaker(line)) {
      blocks.push({
        startLine: i + 1,
        endLine: i + 1,
        content: line,
        type: 'normal',
      });
      i++;
      continue;
    }

    // Content block: group consecutive non-blank, non-heading, non-fence lines
    // This keeps paragraphs, list items, and multi-line descriptions together.
    const start = i;
    i++;
    while (i < lines.length && isContinuation(lines[i]!)) {
      i++;
    }
    blocks.push({
      startLine: start + 1,
      endLine: i,
      content: lines.slice(start, i).join('\n'),
      type: 'normal',
    });
  }

  return blocks;
}

export const PlanDocumentViewer = memo(function PlanDocumentViewer({
  markdown,
  comments = [],
  showLineNumbers = true,
  onComment,
  onRemoveComment,
}: PlanDocumentViewerProps) {
  const lines = useMemo(() => markdown.split('\n'), [markdown]);
  const blocks = useMemo(() => groupLinesIntoBlocks(lines), [lines]);
  const [activeCommentLine, setActiveCommentLine] = useState<number | null>(null);
  const [commentDraft, setCommentDraft] = useState('');
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const commentsByLine = useMemo(() => {
    const map = new Map<number, LineComment>();
    comments.forEach(c => map.set(c.lineNumber, c));
    return map;
  }, [comments]);

  // Focus textarea when comment editor opens
  useEffect(() => {
    if (activeCommentLine !== null && textareaRef.current) {
      textareaRef.current.focus();
    }
  }, [activeCommentLine]);

  const handleSubmitComment = useCallback((lineNumber: number) => {
    if (commentDraft.trim() && onComment) {
      onComment(lineNumber, commentDraft.trim(), lines[lineNumber - 1] ?? '');
      setCommentDraft('');
      setActiveCommentLine(null);
    }
  }, [commentDraft, onComment, lines]);

  if (!showLineNumbers) {
    // Simple mode: just render the markdown without line numbers
    return (
      <div className="plan-document">
        <div className="plan-document-body">
          <Markdown
            remarkPlugins={[remarkGfm]}
            rehypePlugins={[rehypeRaw]}
            components={createDocumentComponents()}
          >
            {markdown}
          </Markdown>
        </div>
      </div>
    );
  }

  // Interactive mode: render blocks with inline line numbers and comment UI
  return (
    <div className="plan-document plan-document-interactive">
      <div className="plan-document-body">
        {blocks.map((block) => {
          // For multi-line blocks (code/table), show the start line number
          // and a single comment target for the whole block
          const lineNum = block.startLine;
          const comment = commentsByLine.get(lineNum);
          const isActive = activeCommentLine === lineNum;
          // Also check if any line in a multi-line block has a comment
          const blockComments: LineComment[] = [];
          for (let l = block.startLine; l <= block.endLine; l++) {
            const c = commentsByLine.get(l);
            if (c) blockComments.push(c);
          }

          return (
            <div key={lineNum} className="plan-block-row" data-line={lineNum}>
              {/* Gutter */}
              <div className="plan-block-gutter">
                <span className="plan-line-number">{lineNum}</span>
                {onComment && !comment && blockComments.length === 0 && (
                  <button
                    className="plan-line-comment-btn"
                    onClick={() => {
                      setActiveCommentLine(isActive ? null : lineNum);
                      setCommentDraft('');
                    }}
                    title="Add comment"
                  >+</button>
                )}
              </div>

              {/* Content */}
              <div className="plan-block-content">
                <Markdown
                  remarkPlugins={[remarkGfm]}
                  rehypePlugins={[rehypeRaw]}
                  components={createDocumentComponents()}
                >
                  {block.content}
                </Markdown>

                {/* Inline comment editor */}
                {isActive && (
                  <div className="plan-comment-editor">
                    <textarea
                      ref={textareaRef}
                      value={commentDraft}
                      onChange={e => setCommentDraft(e.target.value)}
                      placeholder="Add your comment..."
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

                {/* Existing comment bubbles */}
                {(comment ? [comment] : blockComments).map((c) => (
                  <div key={c.lineNumber} className="plan-comment-bubble">
                    <span className="plan-comment-text">{c.text}</span>
                    {onRemoveComment && (
                      <button className="plan-comment-remove" onClick={() => onRemoveComment(c.lineNumber)}>×</button>
                    )}
                  </div>
                ))}
              </div>
            </div>
          );
        })}
      </div>
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
