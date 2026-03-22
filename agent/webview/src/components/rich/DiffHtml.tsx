import { useEffect, useRef, useState, useCallback } from 'react';
import { RichBlock } from './RichBlock';

// ── Singleton lazy-load for diff2html ──

type Diff2HtmlModule = typeof import('diff2html');

let diff2htmlModulePromise: Promise<Diff2HtmlModule> | null = null;
let cssLoaded = false;

function loadDiff2Html(): Promise<Diff2HtmlModule> {
  if (!diff2htmlModulePromise) {
    diff2htmlModulePromise = Promise.all([
      import('diff2html'),
      cssLoaded
        ? Promise.resolve()
        : import('diff2html/bundles/css/diff2html.min.css').then(() => {
            cssLoaded = true;
          }),
    ]).then(([module]) => module);
  }
  return diff2htmlModulePromise;
}

// ── Props ──

interface DiffHtmlProps {
  diffSource: string;
  onAcceptHunk?: (hunkIndex: number) => void;
  onRejectHunk?: (hunkIndex: number) => void;
}

export function DiffHtml({ diffSource, onAcceptHunk, onRejectHunk }: DiffHtmlProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  const renderIdRef = useRef(0);

  const renderDiff = useCallback(async () => {
    const currentRender = ++renderIdRef.current;
    setIsLoading(true);
    setError(null);

    try {
      const diff2html = await loadDiff2Html();

      if (currentRender !== renderIdRef.current) return;

      const html = diff2html.html(diffSource, {
        drawFileList: false,
        matching: 'lines',
        outputFormat: 'side-by-side',
        diffStyle: 'word',
      });

      if (currentRender !== renderIdRef.current) return;
      if (!containerRef.current) return;

      containerRef.current.innerHTML = html;

      // Add accept/reject buttons to each hunk if callbacks provided
      if (onAcceptHunk || onRejectHunk) {
        const hunks = containerRef.current.querySelectorAll('.d2h-diff-tbody');
        hunks.forEach((hunk, index) => {
          const btnContainer = document.createElement('div');
          btnContainer.className = 'diff-hunk-actions';
          btnContainer.style.cssText =
            'display:flex;gap:4px;padding:4px 8px;justify-content:flex-end;border-top:1px solid var(--border);';

          if (onAcceptHunk) {
            const acceptBtn = document.createElement('button');
            acceptBtn.textContent = 'Accept';
            acceptBtn.className = 'diff-hunk-btn diff-hunk-accept';
            acceptBtn.style.cssText =
              'font-size:11px;padding:2px 8px;border-radius:4px;border:none;cursor:pointer;background:var(--success,#22c55e);color:#fff;';
            acceptBtn.addEventListener('click', () => onAcceptHunk(index));
            btnContainer.appendChild(acceptBtn);
          }

          if (onRejectHunk) {
            const rejectBtn = document.createElement('button');
            rejectBtn.textContent = 'Reject';
            rejectBtn.className = 'diff-hunk-btn diff-hunk-reject';
            rejectBtn.style.cssText =
              'font-size:11px;padding:2px 8px;border-radius:4px;border:none;cursor:pointer;background:var(--error,#ef4444);color:#fff;';
            rejectBtn.addEventListener('click', () => onRejectHunk(index));
            btnContainer.appendChild(rejectBtn);
          }

          hunk.parentElement?.appendChild(btnContainer);
        });
      }

      setIsLoading(false);
    } catch (err) {
      if (currentRender !== renderIdRef.current) return;
      setError(err instanceof Error ? err : new Error(String(err)));
      setIsLoading(false);
    }
  }, [diffSource, onAcceptHunk, onRejectHunk]);

  useEffect(() => {
    void renderDiff();
  }, [renderDiff]);

  // Extract file path from diff header
  const filePath = diffSource.match(/^(?:---|\+\+\+)\s+(?:a\/|b\/)?(.+)$/m)?.[1] ?? '';

  return (
    <RichBlock
      type="diff"
      source={diffSource}
      isLoading={isLoading}
      error={error}
      onRetry={() => void renderDiff()}
    >
      {filePath && (
        <div className="border-b border-[var(--border)] px-3 py-1 text-[11px] font-mono text-[var(--fg-muted)]">
          {filePath}
        </div>
      )}
      <div
        ref={containerRef}
        className="diff-container overflow-auto text-[12px] [&_.d2h-wrapper]:bg-transparent [&_.d2h-file-wrapper]:border-none [&_.d2h-file-wrapper]:bg-transparent [&_.d2h-file-header]:hidden [&_.d2h-code-line-ctn]:font-[var(--font-mono,'JetBrains_Mono',monospace)] [&_.d2h-del]:bg-[var(--error,#ef4444)]/10 [&_.d2h-ins]:bg-[var(--success,#22c55e)]/10 [&_.d2h-code-side-line]:bg-transparent [&_.d2h-info]:bg-[var(--hover-overlay)]"
      />
    </RichBlock>
  );
}
