import { memo } from 'react';
import type { ToolCall } from '@/bridge/types';
import { ToolCallView } from './ToolCallView';
import {
  ChainOfThought,
  ChainOfThoughtStep,
  ChainOfThoughtTrigger,
  ChainOfThoughtContent,
} from '@/components/ui/prompt-kit/chain-of-thought';
import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';
import { Loader2, Check, X, Clock, Circle } from 'lucide-react';

// ── Category helpers (reuse from ToolCallView) ──

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

// ── Status icon for chain trigger ──

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
    default:
      return <Circle className="size-2 fill-current text-[var(--fg-muted)]" />;
  }
}

// ── ToolCallChain ──

interface ToolCallChainProps {
  toolCalls: ToolCall[];
}

export const ToolCallChain = memo(function ToolCallChain({ toolCalls }: ToolCallChainProps) {
  if (toolCalls.length === 0) return null;

  // Single tool call — render directly without chain wrapper
  if (toolCalls.length === 1) {
    return (
      <ToolCallView
        toolCall={toolCalls[0]!}
        isLatest={true}
      />
    );
  }

  return (
    <ChainOfThought className="my-2">
      {toolCalls.map((tc, idx) => {
        const category = getCategory(tc.name);
        const catStyle = CATEGORY_STYLES[category];
        const target = extractTarget(tc.args);
        const isLatest = idx === toolCalls.length - 1;
        const isRunning = tc.status === 'RUNNING';
        // Auto-expand latest running, or error calls
        const shouldDefaultOpen = (isLatest && isRunning) || tc.status === 'ERROR';

        return (
          <ChainOfThoughtStep
            key={tc.name + idx}
            defaultOpen={shouldDefaultOpen}
          >
            <ChainOfThoughtTrigger
              isActive={isRunning}
              icon={<StatusIcon status={tc.status} />}
            >
              <span className="flex items-center gap-2 w-full">
                <Badge
                  variant="secondary"
                  className={cn(
                    'rounded px-1 py-0 text-[9px] font-semibold uppercase tracking-wider border-0 shrink-0',
                    catStyle.className,
                  )}
                >
                  {catStyle.label}
                </Badge>
                <span className="font-mono font-medium text-[var(--fg)]">{tc.name}</span>
                {target && (
                  <span className="truncate font-mono text-[var(--fg-muted)]" style={{ maxWidth: '150px' }}>
                    {target}
                  </span>
                )}
                {tc.durationMs != null && (
                  <span className="ml-auto shrink-0 text-[10px] font-mono tabular-nums text-[var(--fg-muted)]">
                    {tc.durationMs < 1000 ? `${tc.durationMs}ms` : `${(tc.durationMs / 1000).toFixed(1)}s`}
                  </span>
                )}
              </span>
            </ChainOfThoughtTrigger>
            <ChainOfThoughtContent>
              <ToolCallView
                toolCall={tc}
                isLatest={isLatest}
              />
            </ChainOfThoughtContent>
          </ChainOfThoughtStep>
        );
      })}
    </ChainOfThought>
  );
});
