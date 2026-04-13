// ── Sub-Agent types ──

export interface SubAgentState {
  agentId: string;
  label: string;
  status: 'RUNNING' | 'COMPLETED' | 'ERROR' | 'KILLED';
  iteration: number;
  tokensUsed: number;
  messages: UiMessage[];
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
    /**
     * Correlation id for the async render round-trip.
     * Generated Kotlin-side by `RenderArtifactTool`, echoed through
     * the sandbox iframe, and used to resolve the suspended tool call
     * via `kotlinBridge.reportArtifactResult(...)`.
     *
     * Always populated when the artifact originates from a `render_artifact`
     * tool call. Absent for inline ```artifact``` markdown fences rendered
     * by `MarkdownRenderer`, which have no backing suspended tool call.
     */
    renderId?: string;
}

export interface VisualizationConfig {
  enabled: boolean;
  autoRender: boolean;
  defaultExpanded: boolean;
  maxHeight: number;
}

// ── UiMessage types (primary chat message type) ──
// Mirrors the Kotlin UiMessage data class in agent/session/UiMessage.kt.
// This is the sole message type used by the Zustand store and all UI components.

export type UiMessageType = 'ASK' | 'SAY';

export type UiAsk =
  | 'RESUME_TASK' | 'RESUME_COMPLETED_TASK' | 'TOOL' | 'COMMAND'
  | 'FOLLOWUP' | 'COMPLETION_RESULT' | 'PLAN_APPROVE' | 'PLAN_MODE_RESPOND'
  | 'ARTIFACT_RENDER' | 'QUESTION_WIZARD'
  | 'APPROVAL_GATE' | 'SUBAGENT_PERMISSION';

export type UiSay =
  | 'API_REQ_STARTED' | 'API_REQ_FINISHED' | 'TEXT' | 'USER_MESSAGE' | 'REASONING'
  | 'TOOL' | 'CHECKPOINT_CREATED' | 'ERROR' | 'PLAN_UPDATE'
  | 'ARTIFACT_RESULT' | 'SUBAGENT_STARTED' | 'SUBAGENT_PROGRESS'
  | 'SUBAGENT_COMPLETED' | 'STEERING_RECEIVED' | 'CONTEXT_COMPRESSED'
  | 'MEMORY_SAVED' | 'ROLLBACK_PERFORMED';

export interface UiMessageModelInfo {
  modelId?: string;
  provider?: string;
}

export interface UiMessagePlanStep {
  title: string;
  status: string;
}

export interface UiMessagePlanData {
  steps: UiMessagePlanStep[];
  status: string;
  comments?: Record<number, string>;
}

export interface UiMessageApprovalData {
  toolName: string;
  toolInput: string;
  diffPreview?: string;
  status: string; // PENDING | APPROVED | REJECTED
}

export interface UiMessageSubagentData {
  agentId: string;
  agentType: string;
  description: string;
  status: string; // RUNNING | COMPLETED | FAILED | KILLED
  iterations: number;
  summary?: string;
}

export interface UiMessageQuestionItem {
  text: string;
  options: string[];
}

export interface UiMessageQuestionData {
  questions: UiMessageQuestionItem[];
  currentIndex: number;
  answers?: Record<number, string>;
  status: string; // IN_PROGRESS | COMPLETED | SKIPPED
}

export interface UiMessageToolCallData {
  toolCallId: string;
  toolName: string;
  args?: string;
  status?: string; // PENDING | RUNNING | COMPLETED | ERROR
  result?: string;
  output?: string;
  durationMs?: number;
  diff?: string;
  isError?: boolean;
}

export interface UiMessage {
  ts: number;
  type: UiMessageType;
  ask?: UiAsk;
  say?: UiSay;
  text?: string;
  reasoning?: string;
  images?: string[];
  files?: string[];
  partial?: boolean;
  conversationHistoryIndex?: number;
  conversationHistoryDeletedRange?: number[];
  lastCheckpointHash?: string;
  modelInfo?: UiMessageModelInfo;
  artifactId?: string;
  planData?: UiMessagePlanData;
  approvalData?: UiMessageApprovalData;
  questionData?: UiMessageQuestionData;
  subagentData?: UiMessageSubagentData;
  toolCallData?: UiMessageToolCallData;
}

/** Mirrors Kotlin HistoryItem from sessions.json */
export interface HistoryItem {
  id: string;
  ts: number;
  task: string;
  tokensIn: number;
  tokensOut: number;
  cacheWrites?: number | null;
  cacheReads?: number | null;
  totalCost: number;
  modelId?: string | null;
  isFavorited: boolean;
}
