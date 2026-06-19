import { memo, useCallback, useRef, useEffect, useMemo, useState, lazy, Suspense } from 'react';
import type { ToolCall } from '@/bridge/types';
import { Terminal } from '@/components/ui/tool-ui/terminal';
import {
  ChainOfThought,
  ChainOfThoughtStep,
  ChainOfThoughtTrigger,
  ChainOfThoughtContent,
} from '@/components/ui/prompt-kit/chain-of-thought';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { CopyButton } from '@/components/ui/copy-button';
import { cn } from '@/lib/utils';
import { formatElapsedMs, formatElapsedSeconds } from '@/lib/time';
import { Loader2, Check, X, Clock, Square } from 'lucide-react';
import { useChatStore } from '@/stores/chatStore';
import { useShiki } from '@/hooks/useShiki';

// Lazy-load DiffHtml — only needed when expanding edit/write tool calls
const DiffHtml = lazy(() => import('@/components/rich/DiffHtml').then(m => ({ default: m.DiffHtml })));

// Tools that must NOT show the universal header Stop button:
//  - run_command / background_process: have their own in-terminal Stop (terminal.tsx)
//  - ask_user_input: awaiting the user; has its own answer affordance
// (the `agent` tool is added in Phase 2 — it uses the per-worker SubAgentView Kill)
const STOP_SUPPRESSED_TOOLS = new Set(['run_command', 'background_process', 'ask_user_input'])

// ── Category helpers ──

type ToolCategory = 'READ' | 'WRITE' | 'EDIT' | 'CMD' | 'SEARCH' | 'TOOL';

const CATEGORY_MAP: Record<string, ToolCategory> = {
  read_file: 'READ', Read: 'READ', WebFetch: 'READ', glob_files: 'READ', Glob: 'READ',
  file_structure: 'READ', find_definition: 'READ', find_references: 'READ',
  type_hierarchy: 'READ', call_hierarchy: 'READ',
  git_status: 'READ', git_blame: 'READ', git_diff: 'READ', git_log: 'READ',
  git_branches: 'READ', git_show_file: 'READ', git_show_commit: 'READ',
  git_stash_list: 'READ', git_merge_base: 'READ', git_file_history: 'READ',
  run_inspections: 'READ', list_quickfixes: 'READ',
  edit_file: 'EDIT', Edit: 'EDIT', MultiEdit: 'EDIT',
  format_code: 'EDIT', optimize_imports: 'EDIT', refactor_rename: 'EDIT',
  search_code: 'SEARCH', Grep: 'SEARCH', WebSearch: 'SEARCH', find_implementations: 'SEARCH',
  run_command: 'CMD', Bash: 'CMD', run_tests: 'CMD', compile_module: 'CMD',
  write_file: 'WRITE', Write: 'WRITE',
};

function getCategory(toolName: string): ToolCategory {
  const stripped = toolName.replace(/^mcp__[^_]+_[^_]+__/, '');
  return CATEGORY_MAP[stripped] ?? 'TOOL';
}

const CATEGORY_STYLES: Record<ToolCategory, { className: string; label: string }> = {
  READ:   { className: 'bg-[var(--badge-read-bg,#1e3a5f)] text-[var(--badge-read-fg,#7cb3f0)]', label: 'READ' },
  WRITE:  { className: 'bg-[var(--badge-write-bg,#3b2e1a)] text-[var(--badge-write-fg,#e8a84c)]', label: 'WRITE' },
  EDIT:   { className: 'bg-[var(--badge-edit-bg,#2d1f3d)] text-[var(--badge-edit-fg,#c084fc)]', label: 'EDIT' },
  CMD:    { className: 'bg-[var(--badge-cmd-bg,#1a2e1a)] text-[var(--badge-cmd-fg,#6ee77a)]', label: 'CMD' },
  SEARCH: { className: 'bg-[var(--badge-search-bg,#1a2e3b)] text-[var(--badge-search-fg,#67d4e8)]', label: 'SEARCH' },
  TOOL:   { className: 'bg-[var(--chip-bg,#2a2a2a)] text-[var(--accent)]', label: 'TOOL' },
};

function extractTarget(args: string): string {
  try {
    const parsed = JSON.parse(args) as Record<string, unknown>;
    for (const key of ['file_path', 'path', 'pattern', 'command', 'query', 'glob']) {
      const val = parsed[key];
      if (typeof val === 'string' && val.length > 0) {
        return val.length > 40 ? '...' + val.slice(-37) : val;
      }
    }
  } catch { /* ignore */ }
  return '';
}

const formatDuration = formatElapsedMs;

/**
 * Surface the configured per-call timeout (in seconds) as a "/ Nm Ss" suffix on
 * the running indicator so users can see how much headroom the tool has before
 * its watchdog fires. The value is computed Kotlin-side
 * (`RunCommandTool.resolveTimeoutSeconds`) and threaded through the bridge via
 * `ToolCall.toolTimeoutSeconds`, so the displayed cap matches the cap the
 * in-tool monitor actually enforces. Returns null when the tool doesn't expose
 * a displayable timeout (currently every tool except `run_command`).
 */
function extractRunCommandTimeoutLabel(toolCall: ToolCall): string | null {
  const seconds = toolCall.toolTimeoutSeconds;
  if (typeof seconds !== 'number' || !Number.isFinite(seconds) || seconds <= 0) {
    return null;
  }
  return formatElapsedSeconds(seconds);
}

// ── Live elapsed timer (mounted only while RUNNING) ──

function LiveElapsedTimer({ timeoutLabel }: { timeoutLabel: string | null }) {
  const startRef = useRef(Date.now());
  const [elapsedMs, setElapsedMs] = useState(0);

  useEffect(() => {
    startRef.current = Date.now();
    setElapsedMs(0);
    const id = setInterval(() => setElapsedMs(Date.now() - startRef.current), 100);
    return () => clearInterval(id);
  }, []);

  return (
    <span className="shrink-0 text-[10px] font-mono tabular-nums text-[var(--accent)]">
      {formatElapsedMs(elapsedMs)}{timeoutLabel ? ` / ${timeoutLabel}` : ''}
    </span>
  );
}

// ── Status icon ──

function StatusIcon({ status }: { status: ToolCall['status'] }) {
  switch (status) {
    case 'RUNNING':
      return <Loader2 className="size-3 animate-spin text-[var(--accent)]" />;
    case 'COMPLETED':
      return <Check className="size-3 text-[var(--success)]" />;
    case 'ERROR':
      return <X className="size-3 text-[var(--error)]" />;
    case 'PENDING':
      return <Clock className="size-3 text-[var(--fg-muted)]" />;
  }
}

// ── Language detection from tool args ──

const EXT_LANG: Record<string, string> = {
  kt: 'kotlin', kts: 'kotlin', java: 'java', py: 'python',
  js: 'javascript', ts: 'typescript', tsx: 'typescript', jsx: 'javascript',
  json: 'json', yaml: 'yaml', yml: 'yaml', xml: 'xml',
  html: 'html', css: 'css', sql: 'sql', sh: 'bash', bash: 'bash',
  go: 'go', rs: 'rust', md: 'markdown', gradle: 'kotlin',
  toml: 'toml', tf: 'hcl', proto: 'protobuf', graphql: 'graphql',
  swift: 'swift', rb: 'ruby', php: 'php', c: 'c', cpp: 'cpp', h: 'c',
};

/** Detect language hint for Shiki based on tool name and args. */
function detectLanguage(toolCall: ToolCall): string {
  const name = toolCall.name.replace(/^mcp__[^_]+_[^_]+__/, '');

  // File-reading tools → derive from file extension
  if (name === 'read_file' || name === 'Read' || name === 'git_show_file') {
    try {
      const args = JSON.parse(toolCall.args) as Record<string, unknown>;
      const filePath = (args.file_path ?? args.path ?? '') as string;
      const ext = filePath.split('.').pop()?.toLowerCase() ?? '';
      return EXT_LANG[ext] ?? '';
    } catch { /* ignore */ }
  }

  // Git diff/blame output
  if (name === 'git_diff' || name === 'git_blame') return 'diff';

  return '';
}

// Max output size for syntax highlighting (skip Shiki for very large outputs)
const HIGHLIGHT_MAX_CHARS = 50_000;

// ── Simple input/output for non-terminal tools ──

function ToolCallDetails({ toolCall }: { toolCall: ToolCall }) {
  let input: Record<string, unknown> | null = null;
  try { input = JSON.parse(toolCall.args) as Record<string, unknown>; } catch { /* ignore */ }

  const displayOutput = toolCall.output || toolCall.result;
  const scrollRef = useRef<HTMLDivElement>(null);

  const language = useMemo(() => detectLanguage(toolCall), [toolCall]);
  const shouldHighlight = language !== '' && (displayOutput?.length ?? 0) <= HIGHLIGHT_MAX_CHARS;
  const { html: shikiHtml, isLoading: shikiLoading } = useShiki(
    shouldHighlight ? (displayOutput ?? '') : '',
    shouldHighlight ? language : '',
  );
  const useHighlighted = shouldHighlight && !!shikiHtml && !shikiLoading;
  const isError = toolCall.status === 'ERROR';

  // Sticky-bottom auto-scroll: keep the latest output visible while the user
  // is at (or near) the bottom; if they've scrolled up to read earlier output,
  // leave their scroll position alone. Tracking is via a ref that's updated on
  // every scroll event — including the programmatic scroll below — so the
  // "user manually scrolled away" signal is captured the moment it happens.
  const stuckToBottomRef = useRef(true);

  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    const STICK_THRESHOLD_PX = 32;
    const onScroll = () => {
      const distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
      stuckToBottomRef.current = distanceFromBottom <= STICK_THRESHOLD_PX;
    };
    el.addEventListener('scroll', onScroll, { passive: true });
    return () => el.removeEventListener('scroll', onScroll);
  }, []);

  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    if (stuckToBottomRef.current) {
      el.scrollTop = el.scrollHeight;
    }
  }, [displayOutput, shikiHtml]);

  return (
    <div className="space-y-2 py-1">
      {input && Object.keys(input).length > 0 && (
        <div className="group/input relative">
          <div className="flex items-center justify-between mb-1">
            <div className="text-[10px] font-semibold uppercase tracking-wider" style={{ color: 'var(--fg-muted)' }}>Input</div>
            <CopyButton
              text={JSON.stringify(input, null, 2)}
              size="sm"
              label="Copy input"
              className="opacity-0 group-hover/input:opacity-100"
            />
          </div>
          <pre
            className="rounded p-2 text-[11px] font-mono leading-relaxed overflow-x-auto"
            style={{ backgroundColor: 'var(--code-bg)', color: 'var(--fg)', maxHeight: '150px', overflowY: 'auto' }}
          >
            {JSON.stringify(input, null, 2)}
          </pre>
        </div>
      )}
      {displayOutput && (
        <div className="group/output relative">
          <div className="flex items-center justify-between mb-1">
            <div
              className="text-[10px] font-semibold uppercase tracking-wider"
              style={{ color: isError ? 'var(--error)' : 'var(--fg-muted)' }}
            >
              {isError ? 'Error' : 'Output'}
            </div>
            <CopyButton
              text={displayOutput}
              size="sm"
              label={isError ? 'Copy error' : 'Copy output'}
              className="opacity-0 group-hover/output:opacity-100"
            />
          </div>
          <div
            ref={scrollRef}
            className={cn(
              'rounded overflow-x-auto overflow-y-auto',
              useHighlighted
                ? '[&_pre]:!m-0 [&_pre]:!p-2 [&_pre]:!text-[11px] [&_pre]:!leading-relaxed [&_code]:!text-[11px]'
                : 'p-2 text-[11px] font-mono leading-relaxed whitespace-pre-wrap [overflow-wrap:anywhere]',
            )}
            style={{
              backgroundColor: !useHighlighted ? (isError ? 'var(--diff-rem-bg)' : 'var(--code-bg)') : undefined,
              color: !useHighlighted ? (isError ? 'var(--error)' : 'var(--fg)') : undefined,
              maxHeight: '300px',
            }}
          >
            {useHighlighted ? (
              <div dangerouslySetInnerHTML={{ __html: shikiHtml }} />
            ) : (
              <pre className="m-0 p-0 text-inherit font-inherit leading-inherit whitespace-pre-wrap [overflow-wrap:anywhere]">
                {displayOutput}
              </pre>
            )}
          </div>
        </div>
      )}
      {toolCall.status === 'RUNNING' && !displayOutput && (
        <div className="text-[11px] italic" style={{ color: 'var(--fg-muted)' }}>Executing...</div>
      )}
    </div>
  );
}

// ── Terminal content for CMD tools ──

function TerminalContent({ toolCall }: { toolCall: ToolCall }) {
  // P1-15: per-key selector so only THIS terminal re-renders when its own chunk
  // arrives — previously the whole-map selector caused every visible terminal to
  // re-render on every append regardless of which tool was streaming.
  const streamOutput = useChatStore(s => s.toolOutputStreams[toolCall.id] ?? '');
  const isRunning = toolCall.status === 'RUNNING';

  const handleKill = useCallback(() => {
    useChatStore.getState().killToolCall(toolCall.id);
  }, [toolCall.id]);
  const isError = toolCall.status === 'ERROR';

  let command = toolCall.name;
  try {
    const parsed = JSON.parse(toolCall.args) as Record<string, unknown>;
    if (typeof parsed.command === 'string') {
      command = parsed.command;
    } else if (toolCall.name === 'run_tests' && typeof parsed.class_name === 'string') {
      command = `run_tests ${parsed.class_name}${typeof parsed.method === 'string' ? `#${parsed.method}` : ''}`;
    } else if (toolCall.name === 'compile_module' && typeof parsed.module === 'string') {
      command = `compile ${parsed.module}`;
    }
  } catch { /* ignore */ }

  // For completed: prefer full output, fall back to stream, then summary
  const completedOutput = toolCall.output || streamOutput || toolCall.result || '';

  return (
    <Terminal
      command={command}
      stdout={isRunning ? streamOutput : completedOutput}
      exitCode={isError ? 1 : toolCall.status === 'COMPLETED' ? 0 : undefined}
      durationMs={toolCall.durationMs}
      isRunning={isRunning}
      onKill={isRunning ? handleKill : undefined}
    />
  );
}

// ── ToolCallItem (memoized) ──

const ToolCallItem = memo(function ToolCallItem({ tc }: { tc: ToolCall }) {
  const category = getCategory(tc.name);
  const catStyle = CATEGORY_STYLES[category];
  const target = extractTarget(tc.args);
  const isRunning = tc.status === 'RUNNING';
  const isCmdTool = category === 'CMD';
  const hasDiff = !!(tc.diff) && (category === 'EDIT' || category === 'WRITE');
  const isRolledBack = tc.rolledBack === true;

  // Headline summary for the row's hover-only copy button.
  // Format: `tool_name(target)` — concise, shareable, mirrors how a person
  // would describe the call. The full JSON args are still available via the
  // "Copy input" button inside the expanded panel.
  const headlineSummary = target ? `${tc.name}(${target})` : tc.name;

  return (
    <div className={cn('group/tool-row relative', isRolledBack && 'opacity-40')}>
      {isRolledBack && (
        <span
          className="absolute -top-1 -right-1 z-10 rounded px-1.5 py-0.5 text-[9px] font-medium uppercase tracking-wider"
          style={{
            background: 'var(--warning, #e5a100)',
            color: 'var(--bg, #1e1e1e)',
          }}
        >
          reverted
        </span>
      )}
      <ChainOfThoughtStep
        defaultOpen={false}
        forceOpen={isRunning && isCmdTool}
      >
        <ChainOfThoughtTrigger
          isActive={isRunning}
          icon={<StatusIcon status={tc.status} />}
        >
          <span className="flex items-center gap-1.5 w-full min-w-0">
            <Badge
              variant="secondary"
              className={cn(
                'rounded px-1 py-0 text-[9px] font-semibold uppercase tracking-wider border-0 shrink-0',
                catStyle.className,
              )}
            >
              {catStyle.label}
            </Badge>
            <span className={cn('font-mono font-medium text-[var(--fg)] shrink-0', isRolledBack && 'line-through')}>{tc.name}</span>
            {target && (
              <span className={cn('truncate font-mono text-[var(--fg-muted)]', isRolledBack && 'line-through')} style={{ maxWidth: '150px' }}>
                {isCmdTool ? `(${target})` : target}
              </span>
            )}
            <span className="flex-1" />
            {/* Universal Stop button — shown for any RUNNING tool that doesn't
                have its own stop affordance (run_command/background_process have
                the in-terminal Stop; ask_user_input has its own answer). */}
            {isRunning && !STOP_SUPPRESSED_TOOLS.has(tc.name) && (
              <Button
                variant="ghost"
                size="sm"
                className="h-5 w-5 p-0 shrink-0"
                onClick={(e) => { e.stopPropagation(); useChatStore.getState().killToolCall(tc.id) }}
                title="Stop"
                aria-label="Stop"
              >
                <Square className="h-3 w-3" style={{ color: 'var(--error)' }} />
              </Button>
            )}
            {/* Elapsed time / duration. Hidden on row hover so it doesn't sit
                under the absolutely-positioned hover copy button on the right
                (they previously overlapped). */}
            <span data-testid="tool-elapsed" className="shrink-0 group-hover/tool-row:invisible">
              {isRunning
                ? <LiveElapsedTimer timeoutLabel={extractRunCommandTimeoutLabel(tc)} />
                : tc.durationMs != null && (
                  <span
                    className="text-[10px] font-mono tabular-nums text-[var(--fg-muted)]"
                    title={extractRunCommandTimeoutLabel(tc) ? `Limit: ${extractRunCommandTimeoutLabel(tc)}` : undefined}
                  >
                    {formatDuration(tc.durationMs)}
                  </span>
                )
              }
            </span>
          </span>
        </ChainOfThoughtTrigger>
        <ChainOfThoughtContent>
          {isCmdTool ? (
            <TerminalContent toolCall={tc} />
          ) : hasDiff ? (
            <Suspense fallback={<div className="p-2 text-[11px] italic" style={{ color: 'var(--fg-muted)' }}>Loading diff view...</div>}>
              <div className="max-h-[400px] overflow-y-auto rounded" style={{ backgroundColor: 'var(--code-bg)' }}>
                <DiffHtml diffSource={tc.diff!} />
              </div>
            </Suspense>
          ) : (
            <ToolCallDetails toolCall={tc} />
          )}
        </ChainOfThoughtContent>
        {tc.status === 'ERROR' && tc.result && (
          <div className="mt-1 text-[11px] px-2 py-1 rounded"
            style={{
              color: 'var(--error, #ef4444)',
              background: 'var(--diff-rem-bg, rgba(239,68,68,0.1))',
            }}>
            {tc.result}
          </div>
        )}
      </ChainOfThoughtStep>
      {/* Hover-only headline copy button. Sibling of the trigger (not nested
          in its <button>) to keep HTML valid. Stops propagation so clicks on
          this button don't toggle the collapsible. The chevron from
          ChainOfThoughtTrigger sits at the row's far right; this button is
          positioned to its left so they don't overlap. */}
      <div
        onClick={(e) => e.stopPropagation()}
        className="absolute right-7 top-1 z-10 opacity-0 transition-opacity group-hover/tool-row:opacity-100"
      >
        <CopyButton text={headlineSummary} size="sm" label="Copy tool call summary" />
      </div>
    </div>
  );
});

// ── ToolCallChain ──

interface ToolCallChainProps {
  toolCalls: ToolCall[];
}

export const ToolCallChain = memo(function ToolCallChain({ toolCalls }: ToolCallChainProps) {
  if (toolCalls.length === 0) return null;

  return (
    <ChainOfThought className="my-2">
      {toolCalls.map(tc => <ToolCallItem key={tc.id} tc={tc} />)}
    </ChainOfThought>
  );
});
