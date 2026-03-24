import { useState, useEffect, useRef } from 'react';
import type { ToolCall, ToolCallStatus } from '@/bridge/types';
import { Tool, type ToolPart } from '@/components/ui/prompt-kit/tool';
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
  TOOL:   { className: 'bg-[var(--chip-bg,#2a2a2a)] text-[var(--accent)]',                          label: 'TOOL' },
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

// ── Map ToolCall → ToolPart ──

function toToolPart(tc: ToolCall): ToolPart {
  const stateMap: Record<ToolCallStatus, ToolPart['state']> = {
    PENDING: 'input-available',
    RUNNING: 'input-streaming',
    COMPLETED: 'output-available',
    ERROR: 'output-error',
  };

  let input: Record<string, unknown> | undefined;
  try {
    input = JSON.parse(tc.args) as Record<string, unknown>;
  } catch {
    input = tc.args ? { raw: tc.args } : undefined;
  }

  let output: Record<string, unknown> | undefined;
  if (tc.result) {
    try {
      output = JSON.parse(tc.result) as Record<string, unknown>;
    } catch {
      output = { result: tc.result };
    }
  }

  return {
    type: tc.name,
    state: stateMap[tc.status],
    input,
    output: tc.status !== 'ERROR' ? output : undefined,
    errorText: tc.status === 'ERROR' ? tc.result : undefined,
  };
}

// ── ToolCallView Component ──

interface ToolCallViewProps {
  toolCall: ToolCall;
  isLatest: boolean;
}

export function ToolCallView({ toolCall, isLatest }: ToolCallViewProps) {
  const { name, status, durationMs } = toolCall;
  const category = getCategory(name);
  const catStyle = CATEGORY_STYLES[category];
  const target = extractTarget(toolCall.args);
  const liveTimer = useLiveTimer(status);
  const toolPart = toToolPart(toolCall);

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

  // Header extras: category badge + target + timer/duration
  const headerExtra = (
    <>
      <Badge
        variant="secondary"
        className={cn(
          'rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wider border-0',
          catStyle.className,
        )}
      >
        {catStyle.label}
      </Badge>
      {target && (
        <span
          className="truncate text-[11px] font-mono text-[var(--fg-muted)]"
          style={{ maxWidth: '200px' }}
        >
          {target}
        </span>
      )}
      <span className="flex-1" />
      {isRunning && liveTimer && (
        <span className="text-[11px] font-mono tabular-nums text-[var(--accent)]">
          {liveTimer}
        </span>
      )}
      {(isCompleted || isError) && durationMs != null && (
        <span className="rounded px-1.5 py-0.5 text-[10px] font-mono bg-[var(--chip-bg)] text-[var(--fg-muted)]">
          {formatDuration(durationMs)}
        </span>
      )}
    </>
  );

  // Progress bar for running state
  const footerExtra = isRunning ? (
    <div className="h-[2px] w-full overflow-hidden" style={{ backgroundColor: 'var(--border)' }}>
      <div
        className="h-full w-1/3 bg-[var(--accent)]"
        style={{ animation: 'indeterminate 1.5s ease-in-out infinite' }}
      />
    </div>
  ) : null;

  return (
    <div
      className={cn(
        'group relative',
        isRunning && 'ring-1 ring-[var(--accent)] rounded-lg',
        isError && 'ring-1 ring-[var(--error)] rounded-lg',
      )}
    >
      <Tool
        toolPart={toolPart}
        open={isOpen}
        onOpenChange={setIsOpen}
        headerExtra={headerExtra}
        footerExtra={footerExtra}
        className="mt-2"
      />
    </div>
  );
}
