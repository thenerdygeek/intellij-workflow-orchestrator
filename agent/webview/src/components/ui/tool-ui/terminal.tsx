/**
 * Terminal — terminal-style output display for command execution results.
 * Compact header with command, duration, exit code, copy button.
 * Collapsible monospace output body.
 */

import { useState, useRef, useEffect, useMemo } from 'react';
import { AnsiUp } from 'ansi_up';
import { cn } from '@/lib/utils';
import { highlightPlainText } from '@/lib/terminal-highlight';
import { Button } from '@/components/ui/button';
import { CopyButton } from '@/components/ui/copy-button';
import { Terminal as TerminalIcon, ChevronDown, ChevronUp, Square } from 'lucide-react';

// Singleton AnsiUp — shared across all Terminal instances
const ansiUp = new AnsiUp();
ansiUp.use_classes = false;

// eslint-disable-next-line no-control-regex
const ANSI_RE = /\x1B\[[0-9;]*[a-zA-Z]/g;
function stripAnsi(text: string): string {
  return text.replace(ANSI_RE, '');
}

interface TerminalProps {
  command: string;
  stdout?: string;
  stderr?: string;
  exitCode?: number;
  durationMs?: number;
  maxCollapsedLines?: number;
  className?: string;
  isRunning?: boolean;
  onKill?: () => void;
}


// P1-15: tail-bound for live ANSI highlighting while RUNNING+expanded.
// Limits the per-chunk O(n²) ansi_to_html call to at most this many lines.
const RUNNING_HIGHLIGHT_TAIL = 400;

// BUG-STOP-1 F3: while a command streams, throttle the (still O(n)) ANSI/heuristic
// highlight to at most one recompute per this many ms. Per-chunk highlighting at the
// 16ms StreamBatcher cadence monopolizes the JCEF event loop and starves the elapsed
// timer's setInterval (the "frozen at 29.9s" freeze). 120ms ≈ 8 highlights/sec — live
// enough to read, cheap enough to leave the loop free for the 100ms timer tick.
const RUNNING_HIGHLIGHT_THROTTLE_MS = 120;

/**
 * Periodic trailing throttle: emits the leading value immediately, then at most one
 * update per `delayMs` carrying the LATEST value seen in the window. `delayMs <= 0`
 * disables throttling and flushes immediately (used once the card finalizes so the full
 * output highlights at once). A pending trailing timer intentionally survives subsequent
 * value changes, so a continuous flood still advances ~once per window instead of being
 * perpetually rescheduled (which would freeze the live output).
 */
function useThrottledValue<T>(value: T, delayMs: number): T {
  const [throttled, setThrottled] = useState(value);
  const latestRef = useRef(value);
  const lastFireRef = useRef(0);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  latestRef.current = value;

  useEffect(() => {
    if (delayMs <= 0) {
      if (timerRef.current) { clearTimeout(timerRef.current); timerRef.current = null; }
      lastFireRef.current = Date.now();
      setThrottled(value);
      return;
    }
    const sinceLast = Date.now() - lastFireRef.current;
    if (sinceLast >= delayMs) {
      lastFireRef.current = Date.now();
      setThrottled(value);
    } else if (timerRef.current == null) {
      timerRef.current = setTimeout(() => {
        lastFireRef.current = Date.now();
        timerRef.current = null;
        setThrottled(latestRef.current);
      }, delayMs - sinceLast);
    }
    // No cleanup-on-value-change: a scheduled trailing fire must outlive later values.
  }, [value, delayMs]);

  // Unmount-only cleanup for the pending trailing timer.
  useEffect(() => () => { if (timerRef.current) clearTimeout(timerRef.current); }, []);

  return throttled;
}

export function Terminal({
  command,
  stdout,
  stderr,
  exitCode,
  durationMs,
  maxCollapsedLines = 10,
  className,
  isRunning,
  onKill,
}: TerminalProps) {
  const [expanded, setExpanded] = useState(false);
  const outputRef = useRef<HTMLPreElement>(null);

  // P1-15: memoize expensive splits on stdout/stderr identity so re-renders
  // caused by parent state changes (non-output) don't re-run these.
  const output = useMemo(
    () => [stdout, stderr].filter(Boolean).join('\n'),
    [stdout, stderr],
  );
  const lines = useMemo(() => output.split('\n'), [output]);
  const needsCollapse = lines.length > maxCollapsedLines;
  // When collapsed, show the LAST N lines (most recent output is most relevant)
  const displayOutput = !expanded && needsCollapse
    ? lines.slice(-maxCollapsedLines).join('\n')
    : output;

  // P1-15: while RUNNING + expanded, highlight only the last RUNNING_HIGHLIGHT_TAIL
  // lines to bound the O(n²) ansi_to_html cost. A header shows skipped line count.
  // Once finalized (isRunning=false) the full output is highlighted.
  const { highlightInput, hiddenLineCount } = useMemo(() => {
    if (isRunning && expanded && lines.length > RUNNING_HIGHLIGHT_TAIL) {
      const tail = lines.slice(-RUNNING_HIGHLIGHT_TAIL);
      return {
        highlightInput: tail.join('\n'),
        hiddenLineCount: lines.length - RUNNING_HIGHLIGHT_TAIL,
      };
    }
    return { highlightInput: displayOutput, hiddenLineCount: 0 };
  }, [isRunning, expanded, lines, displayOutput]);

  const hasAnsi = useMemo(() => output !== stripAnsi(output), [output]);
  // BUG-STOP-1 F3: throttle the highlight input while RUNNING so the per-chunk recompute
  // can't starve the elapsed timer. Once finalized (isRunning falsy) the throttle is off
  // and the full output highlights immediately.
  const throttledHighlightInput = useThrottledValue(
    highlightInput,
    isRunning ? RUNNING_HIGHLIGHT_THROTTLE_MS : 0,
  );
  // ANSI output: let ansi_up handle coloring. Plain text: apply heuristic token highlights.
  const displayHtml = useMemo(
    () => {
      const strippedDisplay = stripAnsi(throttledHighlightInput);
      return strippedDisplay === throttledHighlightInput
        ? highlightPlainText(throttledHighlightInput)
        : ansiUp.ansi_to_html(throttledHighlightInput);
    },
    [throttledHighlightInput],
  );

  // Auto-scroll to bottom when output changes or when expanding
  useEffect(() => {
    if (outputRef.current) {
      outputRef.current.scrollTop = outputRef.current.scrollHeight;
    }
  }, [displayOutput, expanded]);

  const isError = exitCode != null && exitCode !== 0;

  return (
    <div
      className={cn('rounded-lg border overflow-hidden', className)}
      style={{ borderColor: isError ? 'var(--error)' : 'var(--border)', backgroundColor: 'var(--code-bg)' }}
    >
      {/* Header */}
      <div
        className="flex items-center gap-2 px-3 py-2 text-[11px]"
        style={{ borderBottom: '1px solid var(--border)' }}
      >
        <TerminalIcon className="h-3 w-3 shrink-0" style={{ color: 'var(--fg-muted)' }} />
        <code className="flex-1 truncate font-mono font-medium" style={{ color: 'var(--fg)' }}>
          {command}
        </code>
        {durationMs != null && (
          <span className="shrink-0 font-mono tabular-nums" style={{ color: 'var(--fg-muted)' }}>
            {durationMs < 1000 ? `${durationMs}ms` : `${(durationMs / 1000).toFixed(1)}s`}
          </span>
        )}
        {exitCode != null && (
          <span
            className="shrink-0 rounded px-1.5 py-0.5 font-mono text-[10px]"
            style={{
              backgroundColor: isError ? 'var(--diff-rem-bg)' : 'var(--badge-read-bg)',
              color: isError ? 'var(--error)' : 'var(--success)',
            }}
          >
            exit {exitCode}
          </span>
        )}
        <CopyButton
          text={hasAnsi ? stripAnsi(output) : output}
          size="sm"
          label="Copy output"
        />
        {isRunning && onKill && (
          <Button
            variant="ghost"
            size="sm"
            className="h-5 w-5 p-0 shrink-0"
            onClick={onKill}
            title="Stop process"
          >
            <Square className="h-3 w-3" style={{ color: 'var(--error)' }} />
          </Button>
        )}
      </div>

      {/* Output body — max-height keeps it bounded; the inner <pre> handles its own scroll. */}
      {output.length > 0 && (
        <div className="relative">
          {/* P1-15: while running+expanded, show only the tail; header says how many were omitted */}
          {hiddenLineCount > 0 && (
            <div
              className="px-3 py-1 text-[10px] italic select-none"
              style={{ color: 'var(--fg-muted)', borderBottom: '1px solid var(--border)' }}
            >
              … {hiddenLineCount.toLocaleString()} earlier lines (live view)
            </div>
          )}
          <pre
            ref={outputRef}
            className="px-3 py-2 text-[11px] leading-relaxed font-mono overflow-x-auto overflow-y-auto whitespace-pre-wrap"
            style={{ color: 'var(--fg)', maxHeight: expanded ? '300px' : '200px' }}
            dangerouslySetInnerHTML={{ __html: displayHtml }}
          />

          {/* Collapse/Expand toggle */}
          {needsCollapse && (
            <div className="flex justify-center py-1" style={{ borderTop: '1px solid var(--border)' }}>
              <Button
                variant="ghost"
                size="sm"
                className="h-6 text-[10px] gap-1"
                onClick={() => setExpanded(v => !v)}
              >
                {expanded ? (
                  <><ChevronUp className="h-3 w-3" /> Show less</>
                ) : (
                  <><ChevronDown className="h-3 w-3" /> Show all {lines.length} lines</>
                )}
              </Button>
            </div>
          )}
        </div>
      )}

      {/* Empty state */}
      {output.length === 0 && (
        <div className="px-3 py-3 text-[11px] italic" style={{ color: 'var(--fg-muted)' }}>
          No output
        </div>
      )}
    </div>
  );
}
