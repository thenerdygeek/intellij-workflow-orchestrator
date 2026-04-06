// ── Message types ──

export type MessageRole = 'user' | 'agent' | 'system';

export interface Message {
  id: string;
  role: MessageRole;
  content: string;
  timestamp: number;
  /** Completed tool call chain — present on 'system' messages with type 'toolchain' */
  toolChain?: ToolCall[];
  /** Sub-agent state — present on 'system' messages with type 'subagent' */
  subAgent?: SubAgentState;
  /** File path associated with this message (e.g., from edit_file tool calls) */
  filePath?: string;
  /** Artifact state — present on messages with rendered artifacts */
  artifact?: ArtifactState;
  /** True if this message's changes have been rolled back */
  rolledBack?: boolean;
}

// ── Sub-Agent types ──

export interface SubAgentState {
  agentId: string;
  label: string;
  status: 'RUNNING' | 'COMPLETED' | 'ERROR' | 'KILLED';
  iteration: number;
  tokensUsed: number;
  messages: Message[];
  activeToolChain: ToolCall[];
  summary?: string;
  startedAt: number;
}

// ── Tool call types ──

export type ToolCallStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'ERROR';

export interface ToolCall {
  id: string;
  name: string;
  args: string;
  status: ToolCallStatus;
  result?: string;
  output?: string;
  durationMs?: number;
  /** Unified diff for file edit/create tools — rendered as DiffHtml when expanded */
  diff?: string;
  /** True if this tool call's changes have been rolled back */
  rolledBack?: boolean;
}

// ── Plan types ──

export type PlanStepStatus = 'pending' | 'running' | 'completed' | 'done' | 'failed' | 'skipped';

export interface PlanStep {
  id: string;
  title: string;
  description?: string;
  status: PlanStepStatus;
  comment?: string;
  filePaths?: string[];
}

export interface Plan {
  title: string;
  steps: PlanStep[];
  approved: boolean;
  markdown?: string;
  summary?: string;
}

// ── Question types ──

export type QuestionType = 'single-select' | 'multi-select' | 'text';

export interface QuestionOption {
  id?: string;
  label: string;
  description?: string;
  selected?: boolean;
}

export interface Question {
  id: string;
  text: string;
  type: QuestionType;
  options: QuestionOption[];
  answer?: string | string[];
  skipped?: boolean;
}

// ── Session types ──

export type SessionStatus = 'RUNNING' | 'COMPLETED' | 'CANCELLED' | 'ERROR';

export interface SessionInfo {
  status: SessionStatus;
  tokensUsed: number;
  durationMs: number;
  iterations: number;
  filesModified: string[];
}

// ── Mention types ──

export type MentionType = 'file' | 'folder' | 'symbol' | 'tool' | 'skill' | 'ticket';

export interface Mention {
  type: MentionType;
  label: string;
  path?: string;
  icon?: string;
}

export interface MentionSearchResult {
  type: MentionType;
  label: string;
  path?: string;
  description?: string;
  icon?: string;
}

// ── Theme types ──

export interface ThemeVars {
  // Core layout
  bg: string;
  fg: string;
  'fg-secondary': string;
  'fg-muted': string;
  border: string;

  // Semantic backgrounds
  'user-bg': string;
  'tool-bg': string;
  'code-bg': string;
  'thinking-bg': string;

  // Tool category badges (background + foreground pairs)
  'badge-read-bg': string;
  'badge-read-fg': string;
  'badge-write-bg': string;
  'badge-write-fg': string;
  'badge-edit-bg': string;
  'badge-edit-fg': string;
  'badge-cmd-bg': string;
  'badge-cmd-fg': string;
  'badge-search-bg': string;
  'badge-search-fg': string;

  // Tool category accent colors
  'accent-read': string;
  'accent-write': string;
  'accent-edit': string;
  'accent-cmd': string;
  'accent-search': string;

  // Diff colors
  'diff-add-bg': string;
  'diff-add-fg': string;
  'diff-rem-bg': string;
  'diff-rem-fg': string;

  // Status colors
  success: string;
  error: string;
  warning: string;
  link: string;

  // UI chrome
  'hover-overlay': string;
  'hover-overlay-strong': string;
  'divider-subtle': string;
  'row-alt': string;
  'input-bg': string;
  'input-border': string;
  'toolbar-bg': string;
  'chip-bg': string;
  'chip-border': string;

  // Additional from agent-plan.html
  accent?: string;
  running?: string;
  pending?: string;

  // Allow additional string keys
  [key: string]: string | undefined;
}

// ── Status types ──

export type StatusType = 'INFO' | 'SUCCESS' | 'WARNING' | 'ERROR';

// ── Toast types ──

export type ToastType = 'info' | 'success' | 'warning' | 'error';

// ── Skill types ──

export interface Skill {
  name: string;
  description: string;
  active?: boolean;
}

// ── Diff types ──

export interface EditDiff {
  filePath: string;
  oldLines: string[];
  newLines: string[];
  accepted: boolean | null;
}

// ── Edit stats / checkpoint types ──

export interface EditStats {
  totalLinesAdded: number;
  totalLinesRemoved: number;
  filesModified: number;
}

export interface CheckpointInfo {
  id: string;
  description: string;
  timestamp: number;
  iteration: number;
  filesModified: string[];
  totalLinesAdded: number;
  totalLinesRemoved: number;
}

// ── Rollback types ──

export type RollbackSource = 'LLM_TOOL' | 'USER_BUTTON' | 'USER_UNDO';
export type RollbackMechanism = 'LOCAL_HISTORY' | 'GIT_FALLBACK';
export type RollbackScope = 'FULL_CHECKPOINT' | 'SINGLE_FILE';

export interface RollbackInfo {
  id: string;
  timestamp: number;
  checkpointId: string;
  description: string;
  source: RollbackSource;
  mechanism: RollbackMechanism;
  affectedFiles: string[];
  rolledBackEntryIds: string[];
  scope: RollbackScope;
}

// ── Visualization settings ──

export type VisualizationType = 'mermaid' | 'chart' | 'flow' | 'math' | 'diff' | 'interactiveHtml' | 'table' | 'output' | 'progress' | 'timeline' | 'image' | 'artifact';

export interface ArtifactState {
    title: string;
    source: string;
}

export interface VisualizationConfig {
  enabled: boolean;
  autoRender: boolean;
  defaultExpanded: boolean;
  maxHeight: number;
}
