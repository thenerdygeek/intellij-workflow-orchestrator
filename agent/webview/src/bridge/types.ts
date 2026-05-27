// ── Sub-Agent types ──

export interface SubAgentState {
  agentId: string;
  label: string;
  /** Display name of the model backing this sub-agent (e.g. "Claude Sonnet 4.5"). */
  model?: string;
  status: 'RUNNING' | 'COMPLETED' | 'ERROR' | 'KILLED';
  iteration: number;
  tokensUsed: number;
  messages: UiMessage[];
  activeToolChain: ToolCall[];
  summary?: string;
  startedAt: number;
  /** Live streaming thinking buffer for this sub-agent's current <thinking> block. */
  streamingThinkingText?: string | null;
}

// ── Tool call types ──

export type ToolCallStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'ERROR';

/**
 * Metadata for a tool-produced image. Mirrors `ToolResult.ImageRefData` in
 * `:core` — sha256/size identifies the bytes in the active session's
 * AttachmentStore so the next LLM turn can route through the vision path.
 * Bytes themselves never travel through this bridge; only metadata.
 */
export interface ImageRef {
  sha256: string;
  mime: string;
  size: number;
  originalFilename?: string;
  /**
   * 'image' renders as an inline thumbnail; 'file' (a non-image attachment routed
   * to read_file/read_document) renders as a file chip in the message bubble.
   * Absent ⇒ treated as 'image' for backward compatibility with older payloads.
   */
  kind?: 'image' | 'file';
}

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
  /**
   * Tool-produced image attachments. When present and non-empty, the
   * tool-result row renders a small "N images attached from tool" badge
   * below the text output. Multimodal-agent Phase 6.
   */
  imageRefs?: ImageRef[];
  /**
   * Resolved per-call timeout (seconds) for tools that surface a live
   * "/ Nm Ss" cap on their running indicator — currently only `run_command`.
   * Computed Kotlin-side by `RunCommandTool.resolveTimeoutSeconds` so the
   * displayed cap matches the actual cap that the in-tool monitor enforces.
   * Absent for tools without a meaningful displayable timeout; the UI then
   * suppresses the "/ ..." suffix entirely.
   */
  toolTimeoutSeconds?: number;
}

// ── Plan types ──

export interface Plan {
  title: string;
  approved: boolean;
  markdown?: string;
  summary?: string;
  /**
   * Stable identity assigned by `chatStore.setPlan`: a monotonic counter bumped
   * whenever the plan content changes, preserved when an identical plan is
   * re-pushed. Used as the single identity for the card's reset effect and the
   * summary typewriter so a same-title revision is correctly treated as new
   * (bug #11). Absent only for plans rendered outside the store (tests/legacy).
   */
  revision?: number;
}

export interface Handoff {
  /** The LLM-authored 5-section handoff summary (markdown). */
  summary: string;
}

// ── Task types ──

export type TaskStatus = 'pending' | 'in_progress' | 'completed' | 'deleted';

export interface Task {
  id: string;
  subject: string;
  description: string;
  activeForm?: string;
  status: TaskStatus;
  owner?: string;
  blocks: string[];
  blockedBy: string[];
  createdAt?: number;
  updatedAt?: number;
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

// ── Edit stats ──

export interface EditStats {
  totalLinesAdded: number;
  totalLinesRemoved: number;
  filesModified: number;
}

// ── Checkpoint v2 — aggregate diff types ──
// Mirrors Kotlin `agent/checkpoint/CheckpointModels.kt`.

export type FileChangeStatus = 'MODIFIED' | 'CREATED' | 'DELETED';

export interface FileChange {
  path: string;
  added: number;
  removed: number;
  status: FileChangeStatus;
}

export interface AggregateDiff {
  totalAdded: number;
  totalRemoved: number;
  files: FileChange[];
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
  | 'TOOL' | 'STATUS' | 'ERROR' | 'PLAN_UPDATE'
  | 'ARTIFACT_RESULT' | 'SUBAGENT_STARTED' | 'SUBAGENT_PROGRESS'
  | 'SUBAGENT_COMPLETED' | 'STEERING_RECEIVED' | 'CONTEXT_COMPRESSED'
  | 'MEMORY_SAVED' | 'PLAN_APPROVED'
  | 'COMPACTION_MARKER'
  // UI-only spill marker inserted by chatStore.capMessages when the
  // messages[] hard cap evicts an older prefix (see MESSAGES_HARD_CAP).
  // Never emitted by Kotlin; never persisted to api_conversation_history.json.
  | 'SYSTEM';

export interface UiMessageCompactionMarker {
  tokensBefore: number;
  tokensAfter: number;
  messagesBefore: number;
  messagesAfter: number;
  ranLlmSummary: boolean;
  ts: number;
}

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
  /**
   * Tool-produced image metadata. When present and non-empty, the persisted
   * tool-result row renders the "N images attached from tool" badge.
   * Multimodal-agent Phase 6.
   */
  imageRefs?: ImageRef[];
}

export type CompletionKind = 'done' | 'review' | 'heads_up';

export interface CompletionData {
  kind: CompletionKind;
  result: string;
  verifyHow?: string | null;
  discovery?: string | null;
  /**
   * Optional suggested next user message. Rendered as faded ghost-text in
   * the chat input by `<RichInput>` while the input is empty; pressing Right
   * Arrow promotes it to real input. Null/absent on legacy completions.
   */
  nextStep?: string | null;
}

export interface UiMessagePlanApprovalData {
  planMarkdown: string;
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
  modelInfo?: UiMessageModelInfo;
  artifactId?: string;
  planData?: UiMessagePlanData;
  approvalData?: UiMessageApprovalData;
  questionData?: UiMessageQuestionData;
  /**
   * After the P4.T2 refactor, this field holds the full SubAgentState (the
   * single source of truth for sub-agent lifecycle). Legacy sessions persisted
   * with UiMessageSubagentData shape are handled by hydrateFromUiMessages
   * which re-hydrates into SubAgentState on load.
   */
  subagentData?: SubAgentState;
  toolCallData?: UiMessageToolCallData;
  completionData?: CompletionData;
  planApprovalData?: UiMessagePlanApprovalData;
  /** Payload for `say='COMPACTION_MARKER'` divider message. */
  compactionMarker?: UiMessageCompactionMarker;
  /** Wall-clock ms from first ThinkingDelta to ThinkingEnd. Undefined for pre-fix history entries. */
  thinkingDurationMs?: number;
  /** Mentions attached to a USER_MESSAGE (ticket chips, file refs, etc.) */
  mentions?: Mention[];
  /**
   * Multimodal-agent Phase 6 — true when the assistant message is the second
   * leg of the image+tools two-step workaround. Renders a small `📷 image
   * analyzed` strip above the content with a tooltip explaining that the
   * image was analyzed in a separate request to enable tool use.
   */
  analyzedImageBadge?: boolean;
  /**
   * Multimodal-agent — image attachments the user added to this turn. The
   * USER_MESSAGE bubble renders these as thumbnails via
   * `<img src="http://workflow-agent/attachments/{sha256}">` (served by the
   * Kotlin AttachmentReadHandler from disk).
   */
  attachments?: ImageRef[];
}

/** Mirrors Kotlin DelegationMetadata — populated when this session was delegated from another IDE instance. */
export interface DelegationMetadata {
  delegatorIde: string;
  delegatorRepo: string;
  delegatorSessionId: string;
  startedAt: number;
  closedAt?: number | null;
  closeReason?: string | null;
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
  delegated?: DelegationMetadata | null;
}
