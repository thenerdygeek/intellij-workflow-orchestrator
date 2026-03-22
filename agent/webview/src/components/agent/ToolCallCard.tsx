import { useState, useEffect, useRef, useCallback } from 'react';
import type { ToolCall, ToolCallStatus } from '@/bridge/types';

// ── Tool Categories ──

type ToolCategory = 'READ' | 'WRITE' | 'EDIT' | 'CMD' | 'SEARCH';

const CATEGORY_MAP: Record<string, ToolCategory> = {
  read_file: 'READ',
  Read: 'READ',
  WebFetch: 'READ',
  write_file: 'WRITE',
  Write: 'WRITE',
  edit_file: 'EDIT',
  Edit: 'EDIT',
  MultiEdit: 'EDIT',
  run_command: 'CMD',
  Bash: 'CMD',
  search_code: 'SEARCH',
  Grep: 'SEARCH',
  Glob: 'SEARCH',
  glob_files: 'SEARCH',
  WebSearch: 'SEARCH',
  find_definition: 'SEARCH',
  find_references: 'SEARCH',
  find_implementations: 'SEARCH',
};

function getCategory(toolName: string): ToolCategory {
  // Strip mcp__ prefix (e.g., mcp__plugin_foo__bar_baz → bar_baz)
  const stripped = toolName.replace(/^mcp__[^_]+_[^_]+__/, '');
  return CATEGORY_MAP[stripped] ?? 'CMD';
}

const CATEGORY_STYLES: Record<ToolCategory, { bg: string; fg: string; label: string }> = {
  READ:   { bg: 'var(--badge-read-bg, #1e3a5f)',   fg: 'var(--badge-read-fg, #7cb3f0)',   label: 'READ' },
  WRITE:  { bg: 'var(--badge-write-bg, #3b2e1a)',  fg: 'var(--badge-write-fg, #e8a84c)',  label: 'WRITE' },
  EDIT:   { bg: 'var(--badge-edit-bg, #2d1f3d)',   fg: 'var(--badge-edit-fg, #c084fc)',   label: 'EDIT' },
  CMD:    { bg: 'var(--badge-cmd-bg, #1a2e1a)',    fg: 'var(--badge-cmd-fg, #6ee77a)',    label: 'CMD' },
  SEARCH: { bg: 'var(--badge-search-bg, #1a2e3b)', fg: 'var(--badge-search-fg, #67d4e8)', label: 'SEARCH' },
};

// ── Status Icon ──

function StatusIcon({ status }: { status: ToolCallStatus }) {
  switch (status) {
    case 'PENDING':
      return (
        <svg width="14" height="14" viewBox="0 0 16 16" fill="none" className="shrink-0">
          <circle cx="8" cy="8" r="6" stroke="var(--fg-muted, #888)" strokeWidth="1.5" />
        </svg>
      );
    case 'RUNNING':
      return (
        <svg width="14" height="14" viewBox="0 0 16 16" fill="none" className="shrink-0 animate-spin">
          <circle cx="8" cy="8" r="6" stroke="var(--fg-muted, #555)" strokeWidth="1.5" />
          <path
            d="M8 2a6 6 0 0 1 6 6"
            stroke="var(--accent, #6366f1)"
            strokeWidth="1.5"
            strokeLinecap="round"
          />
        </svg>
      );
    case 'COMPLETED':
      return (
        <svg width="14" height="14" viewBox="0 0 16 16" fill="none" className="shrink-0">
          <circle cx="8" cy="8" r="6" fill="var(--badge-cmd-bg, #1a2e1a)" />
          <path
            d="M5 8l2 2 4-4"
            stroke="var(--badge-cmd-fg, #6ee77a)"
            strokeWidth="1.5"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      );
    case 'ERROR':
      return (
        <svg
          width="14"
          height="14"
          viewBox="0 0 16 16"
          fill="none"
          className="shrink-0"
          style={{ animation: 'shake 0.4s ease-in-out' }}
        >
          <circle cx="8" cy="8" r="6" fill="var(--error-bg, #3b1a1a)" />
          <path
            d="M6 6l4 4M10 6l-4 4"
            stroke="var(--error-fg, #f06060)"
            strokeWidth="1.5"
            strokeLinecap="round"
          />
        </svg>
      );
  }
}

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
      // Try common arg fields
      for (const key of ['file_path', 'path', 'pattern', 'command', 'query', 'glob']) {
        const val = obj[key];
        if (typeof val === 'string' && val.length > 0) {
          // Truncate long paths/commands
          if (val.length > 60) {
            return '...' + val.slice(-57);
          }
          return val;
        }
      }
    }
  } catch {
    // Not valid JSON, return empty
  }
  return '';
}

// ── Pretty-Print Args ──

function prettyPrintArgs(args: string): string {
  try {
    return JSON.stringify(JSON.parse(args), null, 2);
  } catch {
    return args;
  }
}

// ── ToolCallCard Component ──

interface ToolCallCardProps {
  toolCall: ToolCall;
  isLatest: boolean;
}

export function ToolCallCard({ toolCall, isLatest }: ToolCallCardProps) {
  const { name, args, status, result, durationMs } = toolCall;
  const category = getCategory(name);
  const style = CATEGORY_STYLES[category];
  const target = extractTarget(args);
  const liveTimer = useLiveTimer(status);

  // Auto-expand: latest running card is expanded, collapse when no longer latest
  const [expanded, setExpanded] = useState(false);
  const prevIsLatest = useRef(isLatest);

  useEffect(() => {
    // Auto-expand when becoming the latest running card
    if (isLatest && status === 'RUNNING') {
      setExpanded(true);
    }
    // Auto-collapse when a newer card takes over
    if (prevIsLatest.current && !isLatest && status === 'RUNNING') {
      setExpanded(false);
    }
    prevIsLatest.current = isLatest;
  }, [isLatest, status]);

  const toggleExpanded = useCallback(() => {
    setExpanded(prev => !prev);
  }, []);

  const isRunning = status === 'RUNNING';
  const isError = status === 'ERROR';
  const isCompleted = status === 'COMPLETED';

  return (
    <div
      className="mb-2 overflow-hidden rounded-lg border transition-all duration-200"
      style={{
        borderColor: isError
          ? 'var(--error-border, #5c2020)'
          : isRunning
            ? 'var(--accent, #6366f1)'
            : 'var(--border, #333)',
        backgroundColor: 'var(--card-bg, #252525)',
      }}
    >
      {/* Header */}
      <button
        onClick={toggleExpanded}
        className="flex w-full items-center gap-2 px-3 py-2 text-left transition-colors duration-100 hover:brightness-110"
        style={{ background: 'transparent' }}
      >
        <StatusIcon status={status} />

        {/* Category badge */}
        <span
          className="rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wider"
          style={{
            backgroundColor: style.bg,
            color: style.fg,
          }}
        >
          {style.label}
        </span>

        {/* Tool name */}
        <span
          className="text-[12px] font-medium"
          style={{ color: 'var(--fg, #ccc)' }}
        >
          {name}
        </span>

        {/* Target */}
        {target && (
          <span
            className="truncate text-[11px] font-mono"
            style={{ color: 'var(--fg-muted, #888)', maxWidth: '200px' }}
          >
            {target}
          </span>
        )}

        {/* Spacer */}
        <span className="flex-1" />

        {/* Live timer while running */}
        {isRunning && liveTimer && (
          <span
            className="text-[11px] font-mono tabular-nums"
            style={{ color: 'var(--accent, #6366f1)' }}
          >
            {liveTimer}
          </span>
        )}

        {/* Duration badge on completion */}
        {(isCompleted || isError) && durationMs != null && (
          <span
            className="rounded px-1.5 py-0.5 text-[10px] font-mono"
            style={{
              backgroundColor: 'var(--badge-duration-bg, #2a2a2a)',
              color: 'var(--fg-muted, #888)',
            }}
          >
            {formatDuration(durationMs)}
          </span>
        )}

        {/* Expand chevron */}
        <svg
          width="12"
          height="12"
          viewBox="0 0 16 16"
          fill="none"
          className="shrink-0 transition-transform duration-200"
          style={{
            transform: expanded ? 'rotate(180deg)' : 'rotate(0deg)',
            color: 'var(--fg-muted, #888)',
          }}
        >
          <path
            d="M4 6l4 4 4-4"
            stroke="currentColor"
            strokeWidth="1.5"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </button>

      {/* Indeterminate progress bar */}
      {isRunning && (
        <div
          className="h-[2px] w-full overflow-hidden"
          style={{ backgroundColor: 'var(--border, #333)' }}
        >
          <div
            className="h-full w-1/3"
            style={{
              backgroundColor: 'var(--accent, #6366f1)',
              animation: 'indeterminate 1.5s ease-in-out infinite',
            }}
          />
        </div>
      )}

      {/* Expandable details */}
      {expanded && (
        <div
          className="border-t px-3 py-2"
          style={{ borderColor: 'var(--border, #333)' }}
        >
          {/* Input args */}
          {args && args.length > 0 && (
            <div className="mb-2">
              <div
                className="mb-1 text-[10px] font-semibold uppercase tracking-wider"
                style={{ color: 'var(--fg-muted, #888)' }}
              >
                Input
              </div>
              <pre
                className="overflow-x-auto rounded p-2 text-[11px] leading-relaxed font-mono"
                style={{
                  backgroundColor: 'var(--code-bg, #1a1a1a)',
                  color: 'var(--fg, #ccc)',
                  maxHeight: '200px',
                  overflowY: 'auto',
                }}
              >
                {prettyPrintArgs(args)}
              </pre>
            </div>
          )}

          {/* Output / Result */}
          {result != null && result.length > 0 && (
            <div>
              <div
                className="mb-1 text-[10px] font-semibold uppercase tracking-wider"
                style={{ color: isError ? 'var(--error-fg, #f06060)' : 'var(--fg-muted, #888)' }}
              >
                {isError ? 'Error' : 'Output'}
              </div>
              <pre
                className="overflow-x-auto rounded p-2 text-[11px] leading-relaxed font-mono"
                style={{
                  backgroundColor: isError
                    ? 'var(--error-bg, #3b1a1a)'
                    : 'var(--code-bg, #1a1a1a)',
                  color: isError ? 'var(--error-fg, #f06060)' : 'var(--fg, #ccc)',
                  maxHeight: '300px',
                  overflowY: 'auto',
                }}
              >
                {result}
              </pre>
            </div>
          )}

          {/* Running placeholder */}
          {isRunning && !result && (
            <div
              className="text-[11px] italic"
              style={{ color: 'var(--fg-muted, #888)' }}
            >
              Executing...
            </div>
          )}
        </div>
      )}
    </div>
  );
}
