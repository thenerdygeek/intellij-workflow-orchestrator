import { memo } from 'react';
import type { ToolCall } from '@/bridge/types';
import { Terminal } from '@/components/ui/tool-ui/terminal';
import {
  ChainOfThought,
  ChainOfThoughtStep,
  ChainOfThoughtTrigger,
  ChainOfThoughtContent,
} from '@/components/ui/prompt-kit/chain-of-thought';
import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';
import { Loader2, Check, X, Clock } from 'lucide-react';
import { useChatStore } from '@/stores/chatStore';

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

function formatDuration(ms: number): string {
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;
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

// ── Simple input/output for non-terminal tools ──

function ToolCallDetails({ toolCall }: { toolCall: ToolCall }) {
  let input: Record<string, unknown> | null = null;
  try { input = JSON.parse(toolCall.args) as Record<string, unknown>; } catch { /* ignore */ }

  return (
    <div className="space-y-2 py-1">
      {input && Object.keys(input).length > 0 && (
        <div>
          <div className="text-[10px] font-semibold uppercase tracking-wider mb-1" style={{ color: 'var(--fg-muted)' }}>Input</div>
          <pre
            className="rounded p-2 text-[11px] font-mono leading-relaxed overflow-x-auto"
            style={{ backgroundColor: 'var(--code-bg)', color: 'var(--fg)', maxHeight: '150px', overflowY: 'auto' }}
          >
            {JSON.stringify(input, null, 2)}
          </pre>
        </div>
      )}
      {toolCall.result && (
        <div>
          <div
            className="text-[10px] font-semibold uppercase tracking-wider mb-1"
            style={{ color: toolCall.status === 'ERROR' ? 'var(--error)' : 'var(--fg-muted)' }}
          >
            {toolCall.status === 'ERROR' ? 'Error' : 'Output'}
          </div>
          <pre
            className="rounded p-2 text-[11px] font-mono leading-relaxed overflow-x-auto"
            style={{
              backgroundColor: toolCall.status === 'ERROR' ? 'var(--diff-rem-bg)' : 'var(--code-bg)',
              color: toolCall.status === 'ERROR' ? 'var(--error)' : 'var(--fg)',
              maxHeight: '200px',
              overflowY: 'auto',
            }}
          >
            {toolCall.result}
          </pre>
        </div>
      )}
      {toolCall.status === 'RUNNING' && !toolCall.result && (
        <div className="text-[11px] italic" style={{ color: 'var(--fg-muted)' }}>Executing...</div>
      )}
    </div>
  );
}

// ── Terminal content for CMD tools ──

function TerminalContent({ toolCall }: { toolCall: ToolCall }) {
  const streamOutput = useChatStore(s => s.toolOutputStreams[toolCall.id] || '');
  const isRunning = toolCall.status === 'RUNNING';
  const isError = toolCall.status === 'ERROR';

  let command = toolCall.name;
  try {
    const parsed = JSON.parse(toolCall.args) as Record<string, unknown>;
    if (typeof parsed.command === 'string') command = parsed.command;
  } catch { /* ignore */ }

  return (
    <Terminal
      command={command}
      stdout={isRunning ? streamOutput : (toolCall.result && !isError ? toolCall.result : streamOutput)}
      stderr={isError ? toolCall.result : undefined}
      exitCode={isError ? 1 : toolCall.status === 'COMPLETED' ? 0 : undefined}
      durationMs={toolCall.durationMs}
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

  return (
    <ChainOfThoughtStep
      defaultOpen={false}
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
          <span className="font-mono font-medium text-[var(--fg)] shrink-0">{tc.name}</span>
          {target && (
            <span className="truncate font-mono text-[var(--fg-muted)]" style={{ maxWidth: '150px' }}>
              {target}
            </span>
          )}
          <span className="flex-1" />
          {isRunning && (
            <span className="shrink-0 text-[10px] font-mono tabular-nums text-[var(--accent)]">running</span>
          )}
          {tc.durationMs != null && !isRunning && (
            <span className="shrink-0 text-[10px] font-mono tabular-nums text-[var(--fg-muted)]">
              {formatDuration(tc.durationMs)}
            </span>
          )}
        </span>
      </ChainOfThoughtTrigger>
      <ChainOfThoughtContent>
        {isCmdTool ? (
          <TerminalContent toolCall={tc} />
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
