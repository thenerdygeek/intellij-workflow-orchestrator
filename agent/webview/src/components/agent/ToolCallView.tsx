import { useState, useEffect, useRef } from 'react';
import type { ToolCall, ToolCallStatus } from '@/bridge/types';
import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';

// ── Tool Categories ──

type ToolCategory = 'READ' | 'WRITE' | 'EDIT' | 'CMD' | 'SEARCH' | 'TOOL';

const CATEGORY_MAP: Record<string, ToolCategory> = {
  // Read operations
  read_file: 'READ',
  Read: 'READ',
  WebFetch: 'READ',
  glob_files: 'READ',
  Glob: 'READ',
  file_structure: 'READ',
  find_definition: 'READ',
  find_references: 'READ',
  type_hierarchy: 'READ',
  call_hierarchy: 'READ',
  git_status: 'READ',
  git_blame: 'READ',
  git_diff: 'READ',
  git_log: 'READ',
  git_branches: 'READ',
  git_show_file: 'READ',
  git_show_commit: 'READ',
  git_stash_list: 'READ',
  git_merge_base: 'READ',
  git_file_history: 'READ',
  run_inspections: 'READ',
  list_quickfixes: 'READ',

  // Edit operations
  edit_file: 'EDIT',
  Edit: 'EDIT',
  MultiEdit: 'EDIT',
  format_code: 'EDIT',
  optimize_imports: 'EDIT',
  refactor_rename: 'EDIT',

  // Search operations
  search_code: 'SEARCH',
  Grep: 'SEARCH',
  WebSearch: 'SEARCH',
  find_implementations: 'SEARCH',

  // Command operations
  run_command: 'CMD',
  Bash: 'CMD',
  run_tests: 'CMD',
  compile_module: 'CMD',

  // Write operations
  write_file: 'WRITE',
  Write: 'WRITE',
};

function getCategory(toolName: string): ToolCategory {
  // Strip mcp__ prefix (e.g., mcp__plugin_foo__bar_baz -> bar_baz)
  const stripped = toolName.replace(/^mcp__[^_]+_[^_]+__/, '');
  return CATEGORY_MAP[stripped] ?? 'TOOL';
}

const CATEGORY_STYLES: Record<ToolCategory, { className: string; label: string }> = {
  READ:   { className: 'bg-[var(--badge-read-bg,#1e3a5f)] text-[var(--badge-read-fg,#7cb3f0)]',     label: 'READ' },
  WRITE:  { className: 'bg-[var(--badge-write-bg,#3b2e1a)] text-[var(--badge-write-fg,#e8a84c)]',   label: 'WRITE' },
  EDIT:   { className: 'bg-[var(--badge-edit-bg,#2d1f3d)] text-[var(--badge-edit-fg,#c084fc)]',     label: 'EDIT' },
  CMD:    { className: 'bg-[var(--badge-cmd-bg,#1a2e1a)] text-[var(--badge-cmd-fg,#6ee77a)]',       label: 'CMD' },
  SEARCH: { className: 'bg-[var(--badge-search-bg,#1a2e3b)] text-[var(--badge-search-fg,#67d4e8)]', label: 'SEARCH' },
  TOOL:   { className: 'bg-[var(--chip-bg,#2a2a2a)] text-[var(--accent,#6366f1)]',                  label: 'TOOL' },
};

// ── Live Timer Hook ──

function useLiveTimer(status: ToolCallStatus): string {
  const startRef = useRef<number>(Date.now());
  const [elapsed, setElapsed] = useState(0);

  useEffect(() => {
    if (status === 'RUNNING') {
      startRef.current = Date.now();
      setElapsed(0);
      const interval = setInterval(() => {
        setElapsed(Date.now() - startRef.current);
      }, 100);
      return () => clearInterval(interval);
    }
  }, [status]);

  if (status !== 'RUNNING') return '';
  return formatDuration(elapsed);
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  const seconds = (ms / 1000).toFixed(1);
  return `${seconds}s`;
}

// ── Target Extraction ──

function extractTarget(args: string): string {
  try {
    const parsed: unknown = JSON.parse(args);
    if (typeof parsed === 'object' && parsed !== null) {
      const obj = parsed as Record<string, unknown>;
      for (const key of ['file_path', 'path', 'pattern', 'command', 'query', 'glob']) {
        const val = obj[key];
        if (typeof val === 'string' && val.length > 0) {
          if (val.length > 60) {
            return '...' + val.slice(-57);
          }
          return val;
        }
      }
    }
  } catch {
    // Not valid JSON
  }
  return '';
}

// ── Pretty-print JSON ──

function prettyPrint(text: string): string {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

// ── ToolCallView Component ──

interface ToolCallViewProps {
  toolCall: ToolCall;
  isLatest: boolean;
}

export function ToolCallView({ toolCall, isLatest }: ToolCallViewProps) {
  const { name, args, status, result, durationMs } = toolCall;
  const category = getCategory(name);
  const style = CATEGORY_STYLES[category];
  const target = extractTarget(args);
  const liveTimer = useLiveTimer(status);

  // Auto-expand: latest running card is expanded, collapse when superseded
  const [isOpen, setIsOpen] = useState(false);
  const prevIsLatest = useRef(isLatest);

  useEffect(() => {
    if (isLatest && status === 'RUNNING') {
      setIsOpen(true);
    }
    if (prevIsLatest.current && !isLatest && status === 'RUNNING') {
      setIsOpen(false);
    }
    prevIsLatest.current = isLatest;
  }, [isLatest, status]);

  const isRunning = status === 'RUNNING';
  const isError = status === 'ERROR';
  const isCompleted = status === 'COMPLETED';

  return (
    <div
      className={cn(
        'group relative',
        isRunning && 'ring-1 ring-[var(--accent,#6366f1)] rounded-lg',
        isError && 'ring-1 ring-[var(--error,#f06060)] rounded-lg',
      )}
    >
      {/* Override prompt-kit Tool's internal collapsible to use our open state */}
      <div className="[&_.border-border]:border-[var(--border,#333)]">
        <div className="border-[var(--border,#333)] mt-2 overflow-hidden rounded-lg border">
          <button
            onClick={() => setIsOpen((prev) => !prev)}
            aria-expanded={isOpen}
            aria-label={`${isOpen ? 'Collapse' : 'Expand'} ${name} tool call`}
            className="flex w-full items-center gap-2 px-3 py-2 text-left transition-colors duration-100 hover:brightness-110 bg-[var(--card-bg,#252525)]"
          >
            {/* Status indicator */}
            {isRunning && (
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none" className="shrink-0 animate-spin">
                <circle cx="8" cy="8" r="6" stroke="var(--fg-muted,#555)" strokeWidth="1.5" />
                <path d="M8 2a6 6 0 0 1 6 6" stroke="var(--accent,#6366f1)" strokeWidth="1.5" strokeLinecap="round" />
              </svg>
            )}
            {status === 'PENDING' && (
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none" className="shrink-0">
                <circle cx="8" cy="8" r="6" stroke="var(--fg-muted,#888)" strokeWidth="1.5" />
              </svg>
            )}
            {isCompleted && (
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none" className="shrink-0">
                <circle cx="8" cy="8" r="6" fill="var(--badge-cmd-bg,#1a2e1a)" />
                <path d="M5 8l2 2 4-4" stroke="var(--badge-cmd-fg,#6ee77a)" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            )}
            {isError && (
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none" className="shrink-0">
                <circle cx="8" cy="8" r="6" fill="var(--error-bg,#3b1a1a)" />
                <path d="M6 6l4 4M10 6l-4 4" stroke="var(--error-fg,#f06060)" strokeWidth="1.5" strokeLinecap="round" />
              </svg>
            )}

            {/* Category badge */}
            <Badge
              variant="secondary"
              className={cn(
                'rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wider border-0',
                style.className,
              )}
            >
              {style.label}
            </Badge>

            {/* Tool name */}
            <span className="text-[12px] font-medium text-[var(--fg,#ccc)]">
              {name}
            </span>

            {/* Target */}
            {target && (
              <span
                className="truncate text-[11px] font-mono text-[var(--fg-muted,#888)]"
                style={{ maxWidth: '200px' }}
              >
                {target}
              </span>
            )}

            {/* Spacer */}
            <span className="flex-1" />

            {/* Live timer while running */}
            {isRunning && liveTimer && (
              <span className="text-[11px] font-mono tabular-nums text-[var(--accent,#6366f1)]">
                {liveTimer}
              </span>
            )}

            {/* Duration badge on completion */}
            {(isCompleted || isError) && durationMs != null && (
              <span className="rounded px-1.5 py-0.5 text-[10px] font-mono bg-[var(--badge-duration-bg,#2a2a2a)] text-[var(--fg-muted,#888)]">
                {formatDuration(durationMs)}
              </span>
            )}

            {/* Expand chevron */}
            <svg
              width="12"
              height="12"
              viewBox="0 0 16 16"
              fill="none"
              className={cn(
                'shrink-0 transition-transform duration-200 text-[var(--fg-muted,#888)]',
                isOpen && 'rotate-180',
              )}
            >
              <path d="M4 6l4 4 4-4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>

          {/* Indeterminate progress bar */}
          {isRunning && (
            <div className="h-[2px] w-full overflow-hidden bg-[var(--border,#333)]">
              <div
                className="h-full w-1/3 bg-[var(--accent,#6366f1)]"
                style={{ animation: 'indeterminate 1.5s ease-in-out infinite' }}
              />
            </div>
          )}

          {/* Expandable details */}
          {isOpen && (
            <div className="border-t border-[var(--border,#333)] px-3 py-2">
              {/* Input args */}
              {args && args.length > 0 && (
                <div className="mb-2">
                  <div className="mb-1 text-[10px] font-semibold uppercase tracking-wider text-[var(--fg-muted,#888)]">
                    Input
                  </div>
                  <pre
                    className="overflow-x-auto rounded p-2 text-[11px] leading-relaxed font-mono bg-[var(--code-bg,#1a1a1a)] text-[var(--fg,#ccc)]"
                    style={{ maxHeight: '200px', overflowY: 'auto' }}
                  >
                    {prettyPrint(args)}
                  </pre>
                </div>
              )}

              {/* Output / Result */}
              {result != null && result.length > 0 && (
                <div>
                  <div
                    className={cn(
                      'mb-1 text-[10px] font-semibold uppercase tracking-wider',
                      isError ? 'text-[var(--error-fg,#f06060)]' : 'text-[var(--fg-muted,#888)]',
                    )}
                  >
                    {isError ? 'Error' : 'Output'}
                  </div>
                  <pre
                    className={cn(
                      'overflow-x-auto rounded p-2 text-[11px] leading-relaxed font-mono',
                      isError
                        ? 'bg-[var(--error-bg,#3b1a1a)] text-[var(--error-fg,#f06060)]'
                        : 'bg-[var(--code-bg,#1a1a1a)] text-[var(--fg,#ccc)]',
                    )}
                    style={{ maxHeight: '300px', overflowY: 'auto' }}
                  >
                    {prettyPrint(result)}
                  </pre>
                </div>
              )}

              {/* Running placeholder */}
              {isRunning && !result && (
                <div className="text-[11px] italic text-[var(--fg-muted,#888)]">
                  Executing...
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
