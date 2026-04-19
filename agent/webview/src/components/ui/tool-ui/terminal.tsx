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

  const output = [stdout, stderr].filter(Boolean).join('\n');
  const lines = output.split('\n');
  const needsCollapse = lines.length > maxCollapsedLines;
  // When collapsed, show the LAST N lines (most recent output is most relevant)
  const displayOutput = !expanded && needsCollapse
    ? lines.slice(-maxCollapsedLines).join('\n')
    : output;

  const hasAnsi = output !== stripAnsi(output);
  // ANSI output: let ansi_up handle coloring. Plain text: apply heuristic token highlights.
  const displayHtml = useMemo(
    () => {
      const strippedDisplay = stripAnsi(displayOutput);
      return strippedDisplay === displayOutput
        ? highlightPlainText(displayOutput)
        : ansiUp.ansi_to_html(displayOutput);
    },
    [displayOutput],
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

      {/* Output body — max-height keeps it bounded but lets StickToBottom detect growth */}
      {output.length > 0 && (
        <div className="relative">
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
