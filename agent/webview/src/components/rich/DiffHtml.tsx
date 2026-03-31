import { useEffect, useRef, useState, useCallback } from 'react';
import { RichBlock } from './RichBlock';

// ── Singleton lazy-load for diff2html ──

type Diff2HtmlModule = typeof import('diff2html');

let diff2htmlModulePromise: Promise<Diff2HtmlModule> | null = null;
let diff2htmlResolved: Diff2HtmlModule | null = null;
let cssLoaded = false;

/** Race dynamic import against a timeout — JCEF's custom scheme can hang on chunk loads. */
function withTimeout<T>(promise: Promise<T>, ms: number, label: string): Promise<T> {
  return Promise.race([
    promise,
    new Promise<T>((_, reject) =>
      setTimeout(() => reject(new Error(`${label} timed out after ${ms}ms`)), ms)
    ),
  ]);
}

function loadDiff2Html(): Promise<Diff2HtmlModule> {
  if (!diff2htmlModulePromise) {
    // Load diff2html module with a 5s timeout to prevent infinite loading in JCEF.
    // CSS is loaded separately and non-blocking.
    diff2htmlModulePromise = withTimeout(import('diff2html'), 5000, 'diff2html import')
      .then((module) => {
        diff2htmlResolved = module;
        if (!cssLoaded) {
          import('diff2html/bundles/css/diff2html.min.css')
            .then(() => { cssLoaded = true; })
            .catch(() => { /* CSS load failed — non-fatal, diff still works */ });
        }
        return module;
      })
      .catch((err) => {
        // Reset so next attempt retries
        diff2htmlModulePromise = null;
        throw err;
      });
  }
  return diff2htmlModulePromise;
}

/**
 * Call this early (e.g. from the bridge layer when a diff is known to be coming)
 * to start the diff2html download before the component even mounts.
 */
export function preloadDiff2Html(): void {
  void loadDiff2Html();
}

// ── Helpers ──

/** Shared button styles */
const BTN_BASE =
  'font-size:11px;padding:2px 8px;border-radius:4px;border:none;cursor:pointer;color:var(--bg,#fff);';

/** Extract "new" side text lines from a diff2html hunk tbody (side-by-side format) */
function extractNewLinesFromHunk(hunk: Element): string {
  // In side-by-side mode, each row has two sides. The right side contains the
  // "new" content. We look for right-side code cells.
  const rows = hunk.querySelectorAll('tr');
  const lines: string[] = [];

  rows.forEach((row) => {
    const cells = row.querySelectorAll('td');
    if (cells.length < 4) return; // not a code row (e.g. info row)

    // Side-by-side layout: [old-num, old-code, new-num, new-code]
    const newCodeCell = cells[3];
    if (!newCodeCell) return;

    // Skip empty-placeholder cells (deleted lines have no new-side content)
    const lineContent = newCodeCell.querySelector('.d2h-code-line-ctn');
    if (!lineContent) return;

    // Only include lines that have a line number on the new side
    const newNumCell = cells[2];
    if (!newNumCell || !newNumCell.textContent?.trim()) return;

    lines.push(lineContent.textContent ?? '');
  });

  return lines.join('\n');
}

// ── Props ──

interface DiffHtmlProps {
  diffSource: string;
  onAcceptHunk?: (hunkIndex: number, editedContent?: string) => void;
  onRejectHunk?: (hunkIndex: number) => void;
}

export function DiffHtml({ diffSource, onAcceptHunk, onRejectHunk }: DiffHtmlProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  // Start loading immediately (not just in useEffect) so JCEF has max time to fetch the chunk.
  // If the module is already cached from a preloadDiff2Html() call, this is a no-op.
  loadDiff2Html();
  const [isLoading, setIsLoading] = useState(() => diff2htmlResolved === null);
  const [error, setError] = useState<Error | null>(null);
  const renderIdRef = useRef(0);

  // Extract file path from diff header
  const filePath = diffSource.match(/^(?:---|\+\+\+)\s+(?:a\/|b\/)?(.+)$/m)?.[1] ?? '';

  const renderDiff = useCallback(async () => {
    const currentRender = ++renderIdRef.current;
    // Only show loading state if the module isn't already in memory.
    if (!diff2htmlResolved) {
      setIsLoading(true);
    }
    setError(null);

    try {
      const diff2html = diff2htmlResolved ?? await loadDiff2Html();

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

      // Add accept/reject/edit buttons to each hunk if callbacks provided
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
              BTN_BASE + 'background:var(--success,#22c55e);';
            acceptBtn.addEventListener('click', () => onAcceptHunk(index));
            btnContainer.appendChild(acceptBtn);
          }

          // Edit button — always shown when onAcceptHunk is provided
          if (onAcceptHunk) {
            const editBtn = document.createElement('button');
            editBtn.textContent = 'Edit';
            editBtn.className = 'diff-hunk-btn diff-hunk-edit';
            editBtn.style.cssText =
              BTN_BASE + 'background:var(--info,#3b82f6);';
            editBtn.addEventListener('click', () => {
              enterEditMode(hunk, index, btnContainer);
            });
            btnContainer.appendChild(editBtn);
          }

          if (onRejectHunk) {
            const rejectBtn = document.createElement('button');
            rejectBtn.textContent = 'Reject';
            rejectBtn.className = 'diff-hunk-btn diff-hunk-reject';
            rejectBtn.style.cssText =
              BTN_BASE + 'background:var(--error,#ef4444);';
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

  /**
   * Replace the hunk's code display with a textarea for editing, plus Save/Cancel buttons.
   * All DOM manipulation is imperative because diff2html renders via innerHTML.
   */
  function enterEditMode(hunk: Element, hunkIndex: number, btnContainer: HTMLDivElement) {
    const newContent = extractNewLinesFromHunk(hunk);
    const maybeTable = hunk.closest('table');
    if (!maybeTable) return;
    const table: HTMLTableElement = maybeTable;

    // Save original display for cancel
    const originalTableDisplay = table.style.display;
    const originalBtnDisplay = btnContainer.style.display;

    // Hide the diff table and action buttons
    table.style.display = 'none';
    btnContainer.style.display = 'none';

    // Create edit container
    const editContainer = document.createElement('div');
    editContainer.className = 'diff-hunk-edit-container';
    editContainer.style.cssText =
      'padding:8px;border:1px solid var(--border);border-radius:4px;margin:4px 0;background:var(--bg,#1e1e1e);';

    // Label
    const label = document.createElement('div');
    label.textContent = 'Edit proposed changes:';
    label.style.cssText =
      'font-size:11px;color:var(--fg-muted);margin-bottom:4px;';
    editContainer.appendChild(label);

    // Textarea
    const textarea = document.createElement('textarea');
    textarea.value = newContent;
    textarea.style.cssText =
      'width:100%;min-height:120px;max-height:400px;resize:vertical;' +
      'font-family:var(--font-mono,"JetBrains Mono",monospace);font-size:12px;' +
      'background:var(--input-bg,#2d2d2d);color:var(--fg,#d4d4d4);' +
      'border:1px solid var(--border,#444);border-radius:4px;padding:8px;' +
      'line-height:1.5;tab-size:4;outline:none;box-sizing:border-box;';
    textarea.spellcheck = false;

    // Auto-resize to fit content
    const lineCount = newContent.split('\n').length;
    const estimatedHeight = Math.max(120, Math.min(400, lineCount * 18 + 16));
    textarea.style.minHeight = estimatedHeight + 'px';

    // Handle Tab key for indentation
    textarea.addEventListener('keydown', (e) => {
      if (e.key === 'Tab') {
        e.preventDefault();
        const start = textarea.selectionStart;
        const end = textarea.selectionEnd;
        textarea.value =
          textarea.value.substring(0, start) + '    ' + textarea.value.substring(end);
        textarea.selectionStart = textarea.selectionEnd = start + 4;
      }
      // Escape = cancel
      if (e.key === 'Escape') {
        exitEditMode();
      }
    });

    editContainer.appendChild(textarea);

    // Save / Cancel buttons
    const editBtnRow = document.createElement('div');
    editBtnRow.style.cssText =
      'display:flex;gap:4px;justify-content:flex-end;margin-top:4px;';

    const saveBtn = document.createElement('button');
    saveBtn.textContent = 'Save & Accept';
    saveBtn.style.cssText = BTN_BASE + 'background:var(--success,#22c55e);';
    saveBtn.addEventListener('click', () => {
      const editedContent = textarea.value;
      exitEditMode();

      // Call the accept callback with edited content
      if (onAcceptHunk) {
        onAcceptHunk(hunkIndex, editedContent);
      }

      // Also call the bridge function directly if available
      if (window._acceptDiffHunk) {
        window._acceptDiffHunk(filePath, hunkIndex, editedContent);
      }
    });

    const cancelBtn = document.createElement('button');
    cancelBtn.textContent = 'Cancel';
    cancelBtn.style.cssText =
      BTN_BASE + 'background:var(--fg-muted,#6b7280);';
    cancelBtn.addEventListener('click', exitEditMode);

    editBtnRow.appendChild(saveBtn);
    editBtnRow.appendChild(cancelBtn);
    editContainer.appendChild(editBtnRow);

    // Insert edit container after the table
    table.parentElement?.insertBefore(editContainer, table.nextSibling);

    // Focus the textarea
    requestAnimationFrame(() => textarea.focus());

    function exitEditMode() {
      // Restore original display
      table.style.display = originalTableDisplay;
      btnContainer.style.display = originalBtnDisplay;
      editContainer.remove();
    }
  }

  useEffect(() => {
    void renderDiff();
  }, [renderDiff]);

  // Fallback: render raw diff with color-coded lines when diff2html fails to load
  if (error) {
    return (
      <div className="my-2 overflow-hidden rounded-lg border border-[var(--border)] bg-[var(--code-bg)]">
        {filePath && (
          <div className="border-b border-[var(--border)] px-3 py-1.5 text-[11px] font-mono text-[var(--fg-muted)]">
            {filePath}
          </div>
        )}
        <pre className="overflow-auto px-3 py-2 text-[12px] leading-relaxed font-mono" style={{ maxHeight: '400px' }}>
          {diffSource.split('\n').map((line, i) => {
            let color = 'var(--fg)';
            let bg = 'transparent';
            if (line.startsWith('+') && !line.startsWith('+++')) {
              color = 'var(--diff-add-fg, #b5cea8)';
              bg = 'var(--diff-add-bg, rgba(106,153,85,0.15))';
            } else if (line.startsWith('-') && !line.startsWith('---')) {
              color = 'var(--diff-rem-fg, #f4a5a5)';
              bg = 'var(--diff-rem-bg, rgba(244,71,71,0.15))';
            } else if (line.startsWith('@@')) {
              color = 'var(--fg-muted)';
            }
            return (
              <div key={i} style={{ color, backgroundColor: bg }}>{line}</div>
            );
          })}
        </pre>
      </div>
    );
  }

  return (
    <RichBlock
      type="diff"
      source={diffSource}
      isLoading={isLoading}
      error={null}
      onRetry={() => void renderDiff()}
    >
      {filePath && (
        <div className="border-b border-[var(--border)] px-3 py-1 text-[11px] font-mono text-[var(--fg-muted)]">
          {filePath}
        </div>
      )}
      {/* Force IDE theme on diff2html — its bundled CSS uses hardcoded light colors */}
      <style>{`
        .diff-container .d2h-wrapper,
        .diff-container .d2h-file-wrapper,
        .diff-container .d2h-file-diff { background: transparent !important; border: none !important; }
        .diff-container .d2h-file-header { display: none !important; }
        .diff-container .d2h-code-line-ctn { font-family: var(--font-mono, 'JetBrains Mono', monospace) !important; }
        .diff-container .d2h-code-side-line,
        .diff-container .d2h-code-line { background: transparent !important; color: var(--fg) !important; }
        .diff-container .d2h-del,
        .diff-container .d2h-del .d2h-code-side-line,
        .diff-container .d2h-del .d2h-code-line { background: var(--diff-rem-bg, rgba(244,71,71,0.12)) !important; color: var(--diff-rem-fg, #f4a5a5) !important; }
        .diff-container .d2h-ins,
        .diff-container .d2h-ins .d2h-code-side-line,
        .diff-container .d2h-ins .d2h-code-line { background: var(--diff-add-bg, rgba(106,153,85,0.12)) !important; color: var(--diff-add-fg, #b5cea8) !important; }
        .diff-container .d2h-info,
        .diff-container .d2h-info .d2h-code-side-line { background: var(--hover-overlay, rgba(255,255,255,0.04)) !important; color: var(--fg-muted) !important; }
        .diff-container .d2h-code-side-emptyplaceholder,
        .diff-container .d2h-emptyplaceholder { background: var(--code-bg, #1a1a2e) !important; border-color: var(--border) !important; }
        .diff-container .d2h-code-side-linenumber,
        .diff-container .d2h-code-linenumber { color: var(--fg-muted, #555) !important; background: transparent !important; border-color: var(--border) !important; }
        .diff-container .d2h-cntx .d2h-code-side-line,
        .diff-container .d2h-cntx .d2h-code-line { background: transparent !important; color: var(--fg) !important; }
        .diff-container td { border-color: var(--border, #333) !important; }
        .diff-container .d2h-diff-table { border-collapse: collapse; }
      `}</style>
      <div
        ref={containerRef}
        className="diff-container overflow-auto text-[12px]"
      />
    </RichBlock>
  );
}
