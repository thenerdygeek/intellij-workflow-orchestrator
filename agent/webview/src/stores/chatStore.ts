import { create } from 'zustand';
import type {
  ToolCall,
  ToolCallStatus,
  Plan,
  Handoff,
  Task,
  Question,
  SessionInfo,
  SessionStatus,
  Mention,
  MentionSearchResult,
  StatusType,
  EditDiff,
  Skill,
  ToastType,
  AggregateDiff,
  UiMessage,
  SubAgentState,
  HistoryItem,
  CompletionData,
  CompletionKind,
  ImageRef,
} from '../bridge/types';

// ── Internal ID generator ──
let _idCounter = 0;
function nextId(prefix: string = 'msg'): string {
  return `${prefix}-${Date.now()}-${++_idCounter}`;
}

/** Parse JSON without throwing — returns null on failure. */
function safeJsonParse(s: string | undefined): any {
  if (!s) return null;
  try { return JSON.parse(s); } catch { return null; }
}

/**
 * Upgrades a persisted subagentData blob from the legacy UiMessageSubagentData
 * shape (pre-Phase 4: description/iterations/agentType, no label/iteration) to
 * the full SubAgentState shape used since Phase 4.
 *
 * Detection: if `raw.label` is already a string the blob is modern — leave it
 * alone.  Anything without `label` is assumed legacy and gets safe defaults so
 * SubAgentView never calls `.match` on undefined.
 *
 * Legacy sessions are always terminal at restart (no live run can resume across
 * a JVM restart), so status is fixed to COMPLETED.
 */
function upgradeLegacySubagentData(raw: any): any {
  if (!raw || typeof raw !== 'object') return raw;
  // Modern shape has `label` (string). Leave it alone.
  if (typeof raw.label === 'string') return raw;

  const description = typeof raw.description === 'string' ? raw.description : '';
  const agentType = typeof raw.agentType === 'string' ? raw.agentType : '';
  const labelFromLegacy = description
    ? (agentType ? `${description} (${agentType})` : description)
    : 'Sub-agent';

  return {
    agentId: raw.agentId ?? 'unknown',
    label: labelFromLegacy,
    model: raw.model ?? null,
    status: 'COMPLETED',  // legacy sessions are terminal; no live run resumes across restart
    startedAt: typeof raw.startedAt === 'number' ? raw.startedAt : Date.now(),
    iteration: typeof raw.iterations === 'number' ? raw.iterations
              : typeof raw.iteration === 'number' ? raw.iteration
              : 0,
    tokensUsed: typeof raw.tokensUsed === 'number' ? raw.tokensUsed : 0,
    messages: Array.isArray(raw.messages) ? raw.messages : [],
    activeToolChain: Array.isArray(raw.activeToolChain) ? raw.activeToolChain : [],
    streamingText: raw.streamingText ?? null,
    streamingThinkingText: raw.streamingThinkingText ?? null,
    summary: typeof raw.summary === 'string' ? raw.summary : undefined,
    error: typeof raw.error === 'string' ? raw.error : undefined,
  };
}

/**
 * Bug 7 — content-equality helpers used by setTasks/applyTask{Create,Update}
 * so a Kotlin-side re-emission of an unchanged task list doesn't cause every
 * `s => s.tasks` subscriber to re-render. Compares user-visible fields only;
 * `createdAt`/`updatedAt` are intentionally excluded so Kotlin's repeated
 * "task touched" pings don't churn the React tree.
 */
function taskFieldsEqual(a: Task, b: Task): boolean {
  if (a === b) return true;
  if (a.id !== b.id) return false;
  if (a.subject !== b.subject) return false;
  if (a.description !== b.description) return false;
  if (a.activeForm !== b.activeForm) return false;
  if (a.status !== b.status) return false;
  if (a.owner !== b.owner) return false;
  if (a.blocks.length !== b.blocks.length) return false;
  for (let i = 0; i < a.blocks.length; i++) if (a.blocks[i] !== b.blocks[i]) return false;
  if (a.blockedBy.length !== b.blockedBy.length) return false;
  for (let i = 0; i < a.blockedBy.length; i++) if (a.blockedBy[i] !== b.blockedBy[i]) return false;
  return true;
}
function tasksShallowEqual(a: Task[], b: Task[]): boolean {
  if (a === b) return true;
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (!taskFieldsEqual(a[i]!, b[i]!)) return false;
  return true;
}

// ── Monotonic timestamp generator ──
// Guarantees unique ts values even when multiple UiMessages are created
// in the same millisecond (e.g., draining tool calls in appendToken).
//
// CONTRACT: returns wall-clock-anchored ms (Math.max(Date.now(), _lastTs + 1)).
// Components rely on wall-clock semantics — e.g., AgentMessage and
// PlanApprovedBubble gate the message-enter animation on `Date.now() - ts < 1000`.
// If you ever switch this to a pure logical counter, audit those gate sites.
let _lastTs = 0;
function uniqueTs(): number {
  const now = Date.now();
  _lastTs = now > _lastTs ? now : _lastTs + 1;
  return _lastTs;
}

// ── messages[] hard cap (2026-05-20 P0 perf audit) ──
// The UI keeps at most MESSAGES_HARD_CAP entries to bound heap growth on
// multi-hour sessions; the agent itself still sees the full conversation
// through ContextManager — this cap is UI-only. When the cap is exceeded,
// the evicted prefix is replaced by a single SYSTEM "spill marker" so the
// user knows older messages were archived (and can scroll up to see it).
//
// Contract: the SPILL marker counts AGAINST the cap, not in addition to it.
// On first cap and every successive cap the total array length is exactly
// MESSAGES_HARD_CAP (marker + MESSAGES_HARD_CAP - 1 retained entries). The
// previous helper produced cap + 1 on first cap and cap on re-cap; that
// off-by-one was invisible to the first test but a latent footgun for any
// caller that ever indexed against an exact size. Exported so tests can
// refer to the named constant rather than repeating the literal.
export const MESSAGES_HARD_CAP = 1000;
const SPILL_MARKER_TEXT =
  'Older messages were archived to keep the chat responsive. ' +
  'The agent still sees the full conversation in its context.';

function capMessages(messages: UiMessage[]): UiMessage[] {
  if (messages.length <= MESSAGES_HARD_CAP) return messages;
  // Reserve 1 slot for the SPILL marker; keep the most-recent
  // MESSAGES_HARD_CAP - 1 entries. This produces the same final array size
  // on first cap and every subsequent cap — otherwise the hasMarker /
  // no-marker paths drift by 1.
  const keepCount = MESSAGES_HARD_CAP - 1;
  const tail = messages.slice(messages.length - keepCount);
  // If the tail's head is the previous SPILL marker (i.e. the marker survived
  // into the kept window), drop it so we don't end up with two markers stacked.
  const firstMsg = tail[0];
  const cleanedTail =
    tail.length > 0 && firstMsg != null && firstMsg.say === 'SYSTEM' && firstMsg.text === SPILL_MARKER_TEXT
      ? tail.slice(1)
      : tail;
  const marker: UiMessage = {
    ts: uniqueTs(),
    type: 'SAY',
    say: 'SYSTEM',
    text: SPILL_MARKER_TEXT,
  };
  return [marker, ...cleanedTail];
}

// ── Background process snapshot ──
export interface BackgroundProcessSnapshot {
  bgId: string;
  kind: string;
  label: string;
  state: 'RUNNING' | 'EXITED' | 'KILLED' | 'TIMED_OUT';
  startedAt: number;
  exitCode: number | null;
  outputBytes: number;
  runtimeMs: number;
}

// ── Debug log ──
export interface DebugLogEntry {
  ts: number;
  level: 'info' | 'warn' | 'error';
  event: string;
  detail: string;
  meta?: Record<string, any>;
}

const DEBUG_LOG_MAX_ENTRIES = 200;

// ── Process input state ──
interface PendingProcessInput {
  processId: string;
  description: string;
  prompt: string;
  command: string;
}

// ── Approval state ──
export interface ApprovalCommandPreview {
  command: string;
  shell: string;
  cwd: string;
  env: Array<{ key: string; value: string }>;
  separateStderr?: boolean;
}
interface PendingApproval {
  toolName: string;
  riskLevel: string;
  title: string;
  description?: string;
  metadata?: Array<{ key: string; value: string }>;
  diffContent?: string;
  commandPreview?: ApprovalCommandPreview;
  /** Whether the UI should offer "Allow for session". False for run_command. */
  allowSessionApproval: boolean;
  /** Present when the approval bubbles up from a sub-agent. */
  originAgentId?: string | null;
  /** Human-readable label of the sub-agent that requested approval. */
  originLabel?: string | null;
}

// ── Toast state ──
interface Toast {
  id: string;
  message: string;
  type: ToastType;
  durationMs: number;
}

// ── Chat store state ──
interface ChatState {
  // State
  messages: UiMessage[];
  /**
   * In-flight streaming text. `null` when no stream is active.
   * Streaming text is held OUTSIDE the `messages` array so per-token updates
   * don't bump the array reference and force ChatView to re-walk every message.
   * On `endStream`, the buffer is committed to `messages` as a finalized
   * UiMessage and `streamingText` is reset to `null`.
   */
  streamingText: string | null;
  /**
   * Stable ts used as the React key for the streaming bubble. Set on the first
   * token, kept until `endStream` flushes the buffer into `messages` with this
   * same ts so DOM continuity is preserved (no remount flash).
   */
  streamingMsgTs: number | null;
  /**
   * Live thinking-block content accumulating between Kotlin's `appendToThinking`
   * deltas and the eventual `endThinking` finalize. While non-null, ChatFooter
   * renders a `<ThinkingView isStreaming={true}>` showing the reasoning live with
   * a shimmer. On endThinking the buffer is flushed into `messages` as a
   * REASONING UiMessage (reusing `streamingThinkingTs` for DOM continuity if
   * possible) and both fields are reset to null. Mirrors the
   * `streamingText`/`streamingMsgTs` pair for prose.
   */
  streamingThinkingText: string | null;
  streamingThinkingTs: number | null;
  /**
   * In-flight streaming `edit_file` previews — one entry per active LLM tool
   * call. Keyed by the AgentLoop-side callId (block index + tool name).
   *
   * Driven by four Kotlin → JS bridge calls: `_streamingEditOpen` (insert),
   * `_streamingEditUpdate` (refresh `.diff`), `_streamingEditFinalize` (flip
   * `.status` to 'finalized'), `_streamingEditCancel` (delete). On
   * `endStream` / `clearChat` / `startSession` the map is reset so stale
   * previews never bleed across sessions.
   *
   * The map is rendered by `<StreamingEditPreviewView />` mounted inside
   * `ChatFooter`. Empty map → nothing renders. The user sees the diff grow
   * inside the chat while the LLM is still emitting `<new_string>` — Commit 2
   * of the live-preview feature.
   */
  streamingEdits: Record<string, { path: string; diff: string; status: 'streaming' | 'finalized' }>;
  activeToolCalls: Map<string, ToolCall>;  // key = unique tool call ID
  plan: Plan | null;
  handoff: Handoff | null;
  planCommentCount: number;
  /**
   * Bug 8 — identities of plan summaries the user has already seen the typewriter
   * animation for. Persists across remounts so React reconciliation that drops and
   * re-mounts <PlanSummaryCard> doesn't re-run the type-out from scratch.
   * Identity = `${plan.title}::${plan.summary ?? plan.markdown ?? ''}`. Cleared on
   * new chat / session reset along with the rest of the store.
   */
  seenPlanSummaries: Set<string>;
  /**
   * Bug 9 — locked random fallback phrase for the working indicator. Owned by the
   * store rather than re-rolled on every WorkingIndicator remount, so per-iteration
   * busy toggles don't churn the phrase. Set once at session start and rotated on
   * new task / new chat. Haiku-generated phrases (smartWorkingPhrase) take
   * precedence when present.
   */
  workingFallbackPhrase: string | null;
  questions: Question[] | null;
  activeQuestionIndex: number;
  questionSummary: any | null;
  session: SessionInfo;
  inputState: {
    locked: boolean;
    mentions: Mention[];
    model: string;
    /** When set, the active model is the result of an automatic fallback (e.g. network error → cheaper model). The string is the human-readable reason shown in the chip's tooltip. null = primary model. */
    modelFallbackReason: string | null;
    mode: 'agent' | 'plan';
  };
  busy: boolean;
  /**
   * Manual compaction lifecycle state. While `active` is true, the chat input,
   * compact button, and other mutating affordances are disabled and a top
   * banner with a spinner is shown.
   */
  compactionState: { active: boolean; phase: string };
  steeringMode: boolean;
  showingToolsPanel: boolean;
  toolsPanelData: string | null;
  showingSkeleton: boolean;
  retryState: { kind: 'continue' | 'retry'; caption: string } | null;
  toasts: Toast[];
  skillBanner: string | null;
  skillsList: Skill[];
  tokenBudget: { used: number; max: number };
  mentionResults: MentionSearchResult[];
  /**
   * The query string that produced the items currently in `mentionResults`.
   * Echoed by Kotlin from `searchMentionsQuery` so the React filter can drop
   * stale responses (e.g. response for `@fil` arriving after the user has
   * backspaced to `@fi` or `@`). Default `''` matches an empty/open-tabs
   * response.
   */
  mentionResultsQuery: string;
  pendingApproval: PendingApproval | null;
  pendingProcessInput: PendingProcessInput | null;
  focusInputTrigger: number;
  /**
   * Suggested next-user-message ghost-text. Set from the most recent
   * `attempt_completion`'s `nextStep`; rendered by `<RichInput>` as faded
   * placeholder text when the input is empty. Cleared when the user sends a
   * message, on chat reset, or on a new session.
   */
  nextStepHint: string | null;
  debugLogVisible: boolean;
  debugLogEntries: DebugLogEntry[];
  toolOutputStreams: Record<string, string>;
  // Per-tool expand/collapse flag, keyed by toolCall.id. Lifted out of
  // ToolCallView's local state so it survives Virtuoso unmount/remount when
  // the row scrolls out of the chat viewport.
  toolCallOpen: Record<string, boolean>;
  aggregateDiff: AggregateDiff | null;
  smartWorkingPhrase: string | null;
  sessionTitle: string | null;
  /** Monotonic counter bumped every time Kotlin asks for an animated title
   *  replacement. SessionTitle component watches this counter to trigger the
   *  scramble animation without conflating with simple provisional-title sets. */
  sessionTitleAnimateKey: number;
  planPending: 'approve' | 'revise' | null;
  planCompletedPendingClear: boolean;
  queuedSteeringMessages: { id: string; text: string; timestamp: number }[];
  restoredInputText: string | null;

  // Session stats chips (model, tokens, cost)
  sessionStats: {
    modelId: string | null;
    tokensIn: number;
    tokensOut: number;
    estimatedCostUsd: number | null;
  } | null;

  // History view state
  viewMode: 'history' | 'chat';
  historyItems: HistoryItem[];
  historySearch: string;

  // Editor-tab fullscreen mode — when true, chrome (TopBar/InputBar/etc.) is hidden
  // and the single block fills the pane. Toggled by AgentVisualizationEditor.
  editorTabMode: boolean;

  // Resume bar state — non-null when viewing a resumable session
  resumeSessionId: string | null;

  // Task state (task system port — Phase 5)
  tasks: Task[];

  // Background processes (Phase 7, Task 7.3)
  backgroundProcesses: BackgroundProcessSnapshot[];

  // Actions
  startSession(task: string, mentions?: Mention[], attachments?: ImageRef[]): void;
  completeSession(info: SessionInfo): void;
  addUserMessage(text: string, mentions?: Mention[], attachments?: ImageRef[]): void;
  addPlanApprovedMessage(planMarkdown: string): void;
  addAgentText(text: string): void;
  appendToken(token: string): void;
  endStream(): void;
  addToolCall(toolCallId: string, name: string, args: string, status: ToolCallStatus, toolTimeoutSeconds?: number): void;
  setToolCallOpen(toolCallId: string, open: boolean): void;
  updateToolCall(name: string, status: ToolCallStatus, result: string, durationMs: number, output?: string, diff?: string, toolCallId?: string, imageRefs?: ImageRef[]): void;
  finalizeToolChain(): void;
  addDiff(diff: EditDiff): void;
  addDiffExplanation(title: string, diffSource: string): void;
  addCompletionCard(data: CompletionData): void;
  /** Clears the ghost-text hint without sending or restoring text. */
  clearNextStepHint(): void;
  addStatus(message: string, type: StatusType): void;
  addThinking(text: string): void;
  appendToThinking(text: string): void;
  endThinking(): void;

  // Streaming `edit_file` preview actions (Commit 2 of live-preview feature)
  streamingEditOpen(callId: string, path: string, initialDiff: string): void;
  streamingEditUpdate(callId: string, diff: string): void;
  streamingEditFinalize(callId: string): void;
  streamingEditCancel(callId: string): void;
  /** Drop every active streaming-edit preview. Called on stream end / new chat. */
  clearStreamingEdits(): void;
  clearChat(): void;
  setPlan(plan: Plan): void;
  clearPlan(): void;
  setHandoff(handoff: Handoff): void;
  clearHandoff(): void;
  approvePlan(): void;
  setPlanPending(state: 'approve' | 'revise' | null): void;
  /** Bug 8 — record that the typewriter for a given plan-summary identity has played. */
  markPlanSummarySeen(identity: string): void;
  /** Bug 9 — set the locked working-indicator fallback phrase (rotated on new task). */
  setWorkingFallbackPhrase(phrase: string): void;

  // Task actions (task system port — Phase 5)
  setTasks(tasks: Task[]): void;
  applyTaskCreate(task: Task): void;
  applyTaskUpdate(task: Task): void;
  setPlanCommentCount(count: number): void;
  showQuestions(questions: Question[]): void;
  clearQuestions(): void;
  finalizeQuestionsAsMessage(): void;
  showQuestion(index: number): void;
  showQuestionSummary(summary: any): void;
  answerQuestion(qid: string, answer: string[]): void;
  skipQuestion(qid: string): void;
  setInputLocked(locked: boolean): void;
  setInputMode(mode: 'agent' | 'plan'): void;
  setBusy(busy: boolean): void;
  setCompactionState(active: boolean, phase: string): void;
  insertCompactionMarker(payload: {
    tokensBefore: number;
    tokensAfter: number;
    messagesBefore: number;
    messagesAfter: number;
    ranLlmSummary: boolean;
    ts: number;
  }): void;
  setSteeringMode(enabled: boolean): void;
  setModelName(model: string): void;
  /**
   * Toggles the fallback indicator on the model chip. When [isFallback] is true,
   * the chip renders an amber border + Zap icon + tooltip showing [reason].
   * Pass [isFallback]=false (and reason=null) to clear — the chip returns to its
   * normal appearance. Used by ModelFallbackManager via the JCEF bridge.
   */
  setModelFallbackState(isFallback: boolean, reason: string | null): void;
  updateTokenBudget(used: number, max: number): void;
  updateSkillsList(skills: Skill[]): void;
  showSkillBanner(name: string): void;
  hideSkillBanner(): void;
  showToolsPanel(toolsJson: string): void;
  hideToolsPanel(): void;
  showRetryButton(kind: 'continue' | 'retry', caption: string): void;
  focusInput(): void;
  addChart(chartConfigJson: string): void;
  addAnsiOutput(text: string): void;
  addTabs(tabsJson: string): void;
  addTimeline(itemsJson: string): void;
  addProgressBar(percent: number, type: string): void;
  addJiraCard(cardJson: string): void;
  addSonarBadge(badgeJson: string): void;
  showSkeleton(): void;
  hideSkeleton(): void;
  showToast(message: string, type: string, durationMs: number): void;
  dismissToast(id: string): void;
  receiveMentionResults(query: string, results: MentionSearchResult[]): void;
  showApproval(toolName: string, riskLevel: string, description?: string, metadata?: Array<{ key: string; value: string }>, diffContent?: string, commandPreview?: ApprovalCommandPreview, allowSessionApproval?: boolean, originAgentId?: string | null, originLabel?: string | null): void;
  resolveApproval(decision: 'approve' | 'deny' | 'allowForSession'): void;
  showProcessInput(processId: string, description: string, prompt: string, command: string): void;
  resolveProcessInput(input: string): void;
  sendMessage(text: string, mentions: Mention[], attachments?: Array<{ sha256: string; mime: string; size: number; originalFilename: string }>): void;
  setDebugLogVisible(visible: boolean): void;
  addDebugLogEntry(entry: DebugLogEntry): void;
  clearDebugLog(): void;
  appendToolOutput(toolCallId: string, chunk: string): void;
  killToolCall(toolCallId: string): void;

  // Aggregate Diff Actions
  updateAggregateDiff(diff: AggregateDiff): void;
  setSmartWorkingPhrase(phrase: string): void;

  // Queued Steering Actions
  addQueuedSteeringMessage(id: string, text: string): void;
  removeQueuedSteeringMessage(id: string): void;
  promoteQueuedSteeringMessages(ids: string[]): void;
  restoreInputText(text: string): void;
  clearRestoredInputText(): void;

  // Session stats actions
  updateSessionStats(stats: { modelId: string | null; tokensIn: number; tokensOut: number; estimatedCostUsd: number | null }): void;
  clearSessionStats(): void;

  // Artifact Actions
  addArtifact(payload: string): void;

  // Sub-Agent Actions
  spawnSubAgent(payload: string): void;
  updateSubAgentIteration(payload: string): void;
  addSubAgentToolCall(payload: string): void;
  updateSubAgentToolCall(payload: string): void;
  updateSubAgentMessage(payload: string): void;
  appendSubAgentStreamDelta(payload: string): void;
  appendSubAgentThinking(agentId: string, delta: string): void;
  endSubAgentThinking(agentId: string): void;
  completeSubAgent(payload: string): void;
  killSubAgent(agentId: string): void;

  // Session rehydration
  hydrateFromUiMessages(uiMessages: UiMessage[]): void;

  // History view actions
  setViewMode(mode: 'history' | 'chat'): void;
  setHistoryItems(items: HistoryItem[]): void;
  setHistorySearch(query: string): void;

  // Editor-tab fullscreen mode
  setEditorTabMode(enabled: boolean): void;

  // Resume bar
  setResumeSessionId(sessionId: string | null): void;

  // Background processes (Phase 7, Task 7.3)
  setBackgroundProcesses(snapshot: BackgroundProcessSnapshot[]): void;
}

export const useChatStore = create<ChatState>((set, get) => ({
  // Initial state
  messages: [],
  streamingText: null,
  streamingMsgTs: null,
  streamingThinkingText: null,
  streamingThinkingTs: null,
  streamingEdits: {},
  activeToolCalls: new Map(),
  plan: null,
  handoff: null,
  planCommentCount: 0,
  seenPlanSummaries: new Set<string>(),
  workingFallbackPhrase: null,
  questions: null,
  activeQuestionIndex: 0,
  questionSummary: null,
  session: {
    status: 'RUNNING' as SessionStatus,
    tokensUsed: 0,
    durationMs: 0,
    iterations: 0,
    filesModified: [],
  },
  inputState: {
    locked: false,
    mentions: [],
    model: '',
    modelFallbackReason: null,
    mode: 'agent',
  },
  busy: false,
  compactionState: { active: false, phase: '' },
  steeringMode: false,
  showingToolsPanel: false,
  toolsPanelData: null,
  showingSkeleton: false,
  retryState: null,
  toasts: [],
  skillBanner: null,
  skillsList: [],
  tokenBudget: { used: 0, max: 0 },
  mentionResults: [],
  mentionResultsQuery: '',
  pendingApproval: null,
  pendingProcessInput: null,
  focusInputTrigger: 0,
  nextStepHint: null,
  debugLogVisible: false,
  debugLogEntries: [],
  toolOutputStreams: {},
  toolCallOpen: {},
  aggregateDiff: null,
  smartWorkingPhrase: null,
  sessionTitle: null,
  sessionTitleAnimateKey: 0,
  planPending: null,
  planCompletedPendingClear: false,
  queuedSteeringMessages: [],
  restoredInputText: null,
  sessionStats: null,
  viewMode: 'chat' as const,
  historyItems: [],
  historySearch: '',
  resumeSessionId: null,
  tasks: [],
  backgroundProcesses: [],
  editorTabMode: false,

  // Actions
  startSession(task: string, mentions?: Mention[], attachments?: ImageRef[]) {
    const firstMessage: UiMessage = {
      ts: uniqueTs(),
      type: 'SAY',
      say: 'USER_MESSAGE',
      text: task,
      ...(mentions && mentions.length > 0 ? { mentions } : {}),
      ...(attachments && attachments.length > 0 ? { attachments } : {}),
    };
    set({
      messages: [firstMessage],
      streamingText: null,
      streamingMsgTs: null,
      streamingThinkingText: null,
      streamingThinkingTs: null,
      streamingEdits: {},
      activeToolCalls: new Map(),
      // Reset tool output streams alongside activeToolCalls — they are keyed by
      // the same tool call IDs and would otherwise leak across sessions.
      toolOutputStreams: {},
      toolCallOpen: {},
      plan: null,
      handoff: null,
      planCompletedPendingClear: false,
      seenPlanSummaries: new Set<string>(),
      workingFallbackPhrase: null,
      questions: null,
      questionSummary: null,
      busy: true,
      steeringMode: true,
      retryState: null,
      smartWorkingPhrase: null,
      sessionTitle: null,
      aggregateDiff: null,
      queuedSteeringMessages: [],
      restoredInputText: null,
      tasks: [],
      sessionStats: null,
      viewMode: 'chat' as const,
      nextStepHint: null,
      session: {
        status: 'RUNNING',
        tokensUsed: 0,
        durationMs: 0,
        iterations: 0,
        filesModified: [],
      },
    });
  },

  completeSession(info: SessionInfo) {
    // Finalize any remaining active tool calls as individual UiMessage entries,
    // merging any buffered stream output into each tool call first so it
    // survives past the toolOutputStreams reset.
    const state = get();
    const streams = state.toolOutputStreams;
    const remaining = Array.from(state.activeToolCalls.values());
    const toolMessages: UiMessage[] = remaining.map(tc => {
      const stream = streams[tc.id];
      const output = tc.output || stream || undefined;
      return {
        ts: uniqueTs(),
        type: 'SAY' as const,
        say: 'TOOL' as const,
        toolCallData: {
          toolCallId: tc.id,
          toolName: tc.name,
          args: tc.args,
          status: tc.status,
          result: tc.result,
          output,
          durationMs: tc.durationMs,
          diff: tc.diff,
          // Multimodal-agent Phase 6 — preserve tool-produced image metadata
          // when the active tool chain is drained on session-complete.
          imageRefs: tc.imageRefs,
        },
      };
    });
    // Flush any in-flight streaming text first (chronology: text → tool calls → session end).
    const streamFlush: UiMessage[] = (state.streamingText != null && state.streamingMsgTs != null)
      ? [{ ts: state.streamingMsgTs, type: 'SAY' as const, say: 'TEXT' as const, text: state.streamingText, partial: false }]
      : [];
    const messages = (streamFlush.length > 0 || toolMessages.length > 0)
      ? [...state.messages, ...streamFlush, ...toolMessages]
      : state.messages;
    set({
      session: info,
      busy: false,
      steeringMode: false,
      streamingText: null,
      streamingMsgTs: null,
      streamingThinkingText: null,
      streamingThinkingTs: null,
      streamingEdits: {},
      activeToolCalls: new Map(),
      // Drop all tool output streams — the map was keyed by the now-cleared
      // tool call IDs and would otherwise leak for the rest of the app's life.
      toolOutputStreams: {},
      toolCallOpen: {},
      messages,
      queuedSteeringMessages: [],
      handoff: null,
      // Clear completed plan on session end (no more messages will arrive to trigger deferred clear)
      ...(state.planCompletedPendingClear ? { plan: null, planCompletedPendingClear: false } : {}),
    });
  },

  addUserMessage(text: string, mentions?: Mention[], attachments?: ImageRef[]) {
    const msg: UiMessage = {
      ts: uniqueTs(), type: 'SAY', say: 'USER_MESSAGE', text,
      ...(mentions && mentions.length > 0 ? { mentions } : {}),
      ...(attachments && attachments.length > 0 ? { attachments } : {}),
    };
    set(state => ({ messages: capMessages([...state.messages, msg]), nextStepHint: null }));
  },

  addPlanApprovedMessage(planMarkdown: string) {
    const msg: UiMessage = {
      ts: uniqueTs(),
      type: 'SAY',
      say: 'PLAN_APPROVED',
      text: 'Implementation plan approved',
      planApprovalData: { planMarkdown },
    };
    set(state => ({ messages: capMessages([...state.messages, msg]) }));
  },

  addAgentText(text: string) {
    set(state => {
      const flushed: UiMessage[] = state.streamingText != null && state.streamingMsgTs != null
        ? [{ ts: state.streamingMsgTs, type: 'SAY', say: 'TEXT', text: state.streamingText, partial: false }]
        : [];
      const msg: UiMessage = { ts: uniqueTs(), type: 'SAY', say: 'TEXT', text };
      return {
        messages: capMessages([...state.messages, ...flushed, msg]),
        streamingText: null,
        streamingMsgTs: null,
        streamingThinkingText: null,
        streamingThinkingTs: null,
      };
    });
  },

  appendToken(token: string) {
    if (token.length === 0) return;
    set(state => {
      if (state.streamingText == null) {
        let messages = state.messages;
        let activeToolCalls = state.activeToolCalls;
        let toolOutputStreams = state.toolOutputStreams;

        if (state.activeToolCalls.size > 0) {
          const streams = state.toolOutputStreams;
          const toolMsgs: UiMessage[] = Array.from(state.activeToolCalls.values()).map(tc => {
            const s = streams[tc.id];
            const output = tc.output || s || undefined;
            return {
              ts: uniqueTs(),
              type: 'SAY' as const,
              say: 'TOOL' as const,
              toolCallData: {
                toolCallId: tc.id,
                toolName: tc.name,
                args: tc.args,
                status: tc.status,
                result: tc.result,
                output,
                durationMs: tc.durationMs,
                diff: tc.diff,
                // Multimodal-agent Phase 6 — preserve tool-produced image
                // metadata across this drain path too.
                imageRefs: tc.imageRefs,
              },
            };
          });
          messages = [...messages, ...toolMsgs];
          activeToolCalls = new Map();
          toolOutputStreams = {};
        }

        return {
          messages,
          activeToolCalls,
          toolOutputStreams,
          streamingText: token,
          streamingMsgTs: uniqueTs(),
        };
      }
      return { streamingText: state.streamingText + token };
    });
  },

  endStream() {
    set(state => {
      const shouldClearPlan = state.planCompletedPendingClear;
      // Drop any in-flight streaming edit previews. By the time endStream fires,
      // the tool call has either finalized (approval card now owns the diff) or
      // been cancelled. Either way, the live preview should disappear so the user
      // doesn't see two cards rendering the same diff.
      const haveStreamingEdits = Object.keys(state.streamingEdits).length > 0;
      if (state.streamingText == null || state.streamingMsgTs == null) {
        return {
          handoff: null,
          ...(haveStreamingEdits ? { streamingEdits: {} } : {}),
          ...(shouldClearPlan ? { plan: null, planCompletedPendingClear: false } : {}),
        };
      }
      const finalized: UiMessage = {
        ts: state.streamingMsgTs,
        type: 'SAY',
        say: 'TEXT',
        text: state.streamingText,
        partial: false,
      };
      return {
        messages: capMessages([...state.messages, finalized]),
        streamingText: null,
        streamingMsgTs: null,
        streamingThinkingText: null,
        streamingThinkingTs: null,
        handoff: null,
        ...(haveStreamingEdits ? { streamingEdits: {} } : {}),
        ...(shouldClearPlan ? { plan: null, planCompletedPendingClear: false } : {}),
      };
    });
  },

  addToolCall(toolCallId: string, name: string, args: string, status: ToolCallStatus, toolTimeoutSeconds?: number) {
    set(state => {
      // Use the LLM-assigned tool call ID so streaming output (keyed by the same ID) resolves correctly.
      // Fall back to a generated ID for legacy callers that don't supply one.
      let id = toolCallId || nextId('tc');
      const newMap = new Map(state.activeToolCalls);

      // Same id + different tool name = a Kotlin-side id scope bug. Rekey
      // the incoming entry instead of silently overwriting, so the previous
      // tool's UI card survives and the root cause surfaces in the log.
      // Same id + same name is a legitimate status update, not a collision.
      const existing = newMap.get(id);
      if (existing && existing.name !== name) {
        const dupKey = `${id}__dup-${nextId('col')}`;
        console.warn(
          `[chatStore] addToolCall: id collision — "${id}" already bound to ` +
          `"${existing.name}" (status=${existing.status}). Incoming call "${name}" ` +
          `rekeyed to "${dupKey}". This indicates a Kotlin-side ID scope bug.`
        );
        id = dupKey;
      }

      newMap.set(id, {
        id,
        name,
        args,
        status,
        ...(toolTimeoutSeconds != null ? { toolTimeoutSeconds } : {}),
      });

      // Flush any in-flight streaming buffer into messages so the tool call
      // below it renders as the next visual item chronologically.
      if (state.streamingText != null && state.streamingMsgTs != null) {
        const finalized: UiMessage = {
          ts: state.streamingMsgTs,
          type: 'SAY',
          say: 'TEXT',
          text: state.streamingText,
          partial: false,
        };
        return {
          messages: capMessages([...state.messages, finalized]),
          streamingText: null,
          streamingMsgTs: null,
          activeToolCalls: newMap,
        };
      }

      return { activeToolCalls: newMap };
    });
  },

  setToolCallOpen(toolCallId: string, open: boolean) {
    set(state => {
      const current = state.toolCallOpen[toolCallId];
      if (current === open) return {};
      return { toolCallOpen: { ...state.toolCallOpen, [toolCallId]: open } };
    });
  },

  updateToolCall(name: string, status: ToolCallStatus, result: string, durationMs: number, output?: string, diff?: string, toolCallId?: string, imageRefs?: ImageRef[]) {
    set(state => {
      const newMap = new Map(state.activeToolCalls);
      // Prefer exact ID match — this is the only reliable way to target a
      // specific tool call when multiple calls to the same tool run in parallel.
      // Without an ID, results arriving out of order would overwrite the wrong
      // slot. Fall back to first-RUNNING-by-name only when no ID was provided
      // (legacy callers) or the ID is unknown to the store.
      let targetKey: string | null = null;
      if (toolCallId && newMap.has(toolCallId)) {
        targetKey = toolCallId;
      } else {
        for (const [key, tc] of newMap) {
          if (tc.name === name && tc.status === 'RUNNING') {
            targetKey = key;
            break;
          }
        }
        // Fallback: any match with this name (last one)
        if (!targetKey) {
          for (const [key, tc] of newMap) {
            if (tc.name === name) targetKey = key;
          }
        }
      }
      // Multimodal-agent Phase 6 — only attach `imageRefs` when non-empty so
      // the property doesn't appear on the common no-image case (keeps the
      // ToolCall payload compact + matches the Kotlin contract that an empty
      // list maps to absent).
      const imageRefsPatch = imageRefs && imageRefs.length > 0 ? { imageRefs } : {};
      if (targetKey) {
        const existing = newMap.get(targetKey)!;
        newMap.set(targetKey, { ...existing, status, result, output, durationMs, ...(diff ? { diff } : {}), ...imageRefsPatch });
      } else {
        const id = toolCallId || nextId('tc');
        newMap.set(id, { id, name, args: '', status, result, output, durationMs, ...(diff ? { diff } : {}), ...imageRefsPatch });
      }
      return { activeToolCalls: newMap };
    });
  },

  addDiff(diff: EditDiff) {
    const msg: UiMessage = {
      ts: uniqueTs(),
      type: 'SAY',
      say: 'TOOL',
      text: JSON.stringify(diff),
      toolCallData: {
        toolCallId: nextId('diff'),
        toolName: 'edit_file',
        args: JSON.stringify({ file_path: diff.filePath }),
        status: 'COMPLETED',
      },
    };
    set(state => ({ messages: capMessages([...state.messages, msg]) }));
  },

  addDiffExplanation(title: string, diffSource: string) {
    const msg: UiMessage = {
      ts: uniqueTs(),
      type: 'SAY',
      say: 'TEXT',
      text: JSON.stringify({ type: 'diff-explanation', title, diffSource }),
    };
    set(state => ({ messages: capMessages([...state.messages, msg]) }));
  },

  addCompletionCard(data: CompletionData) {
    set(state => {
      const flushed: UiMessage[] = (state.streamingText != null && state.streamingMsgTs != null)
        ? [{ ts: state.streamingMsgTs, type: 'SAY', say: 'TEXT', text: state.streamingText, partial: false }]
        : [];
      const msg: UiMessage = { ts: uniqueTs(), type: 'ASK', ask: 'COMPLETION_RESULT', completionData: data };
      const hint = data.nextStep && data.nextStep.trim().length > 0 ? data.nextStep.trim() : null;
      return {
        messages: capMessages([...state.messages, ...flushed, msg]),
        streamingText: null,
        streamingMsgTs: null,
        streamingThinkingText: null,
        streamingThinkingTs: null,
        nextStepHint: hint,
      };
    });
  },

  clearNextStepHint() {
    set({ nextStepHint: null });
  },

  addStatus(message: string, type: StatusType) {
    // ERROR gets its own say; all other status types use the generic STATUS
    // SAY which the ChatView renders as a muted status line.
    const msg: UiMessage = {
      ts: uniqueTs(),
      type: 'SAY',
      say: type === 'ERROR' ? 'ERROR' : 'STATUS',
      text: message,
    };
    set(state => ({ messages: capMessages([...state.messages, msg]) }));
  },

  addThinking(text: string) {
    const msg: UiMessage = {
      ts: uniqueTs(),
      type: 'SAY',
      say: 'REASONING',
      text,
    };
    set(state => ({ messages: capMessages([...state.messages, msg]) }));
  },

  /**
   * Incremental thinking delta. Creates a new streaming-thinking block on first
   * call after an `endThinking` (or initial state); subsequent calls append to
   * the running buffer. ChatFooter watches `streamingThinkingText` and renders
   * `<ThinkingView isStreaming={true}>` live with a shimmer animation.
   */
  appendToThinking(text: string) {
    set(state => {
      if (state.streamingThinkingTs == null) {
        return {
          streamingThinkingText: text,
          streamingThinkingTs: uniqueTs(),
        };
      }
      return {
        streamingThinkingText: (state.streamingThinkingText ?? '') + text,
      };
    });
  },

  /**
   * Finalize the in-flight thinking block. Flushes the accumulated buffer into
   * `messages` as a REASONING UiMessage (reusing the same ts for DOM continuity)
   * so ChatView's renderItem switch picks it up and renders it with
   * `isStreaming={false}` — at which point ThinkingView's auto-collapse kicks
   * in after 600 ms. No-op if there is no active streaming thinking.
   */
  endThinking(durationMs: number = 0) {
    set(state => {
      if (state.streamingThinkingTs == null || state.streamingThinkingText == null) {
        return {};
      }
      const finalized: UiMessage = {
        ts: state.streamingThinkingTs,
        type: 'SAY',
        say: 'REASONING',
        text: state.streamingThinkingText,
        ...(durationMs > 0 && { thinkingDurationMs: durationMs }),
      };
      return {
        messages: capMessages([...state.messages, finalized]),
        streamingThinkingText: null,
        streamingThinkingTs: null,
      };
    });
  },

  // ── Streaming `edit_file` preview actions (Commit 2 of live-preview feature) ──
  //
  // These are driven by Kotlin's StreamingEditTracker via the four
  // `_streamingEdit{Open,Update,Finalize,Cancel}` JCEF bridges. The map
  // `streamingEdits` is rendered by <StreamingEditPreviewView /> inside
  // ChatFooter; an empty map renders nothing. On stream end / new chat
  // we drop the whole map (see endStream / clearChat / startSession /
  // completeSession) — same lifecycle as `streamingText`.

  streamingEditOpen(callId: string, path: string, initialDiff: string) {
    set(state => {
      if (state.streamingEdits[callId]) {
        // Kotlin should never re-open the same callId, but guard anyway —
        // a duplicate open could be a re-parse fluke at the parser boundary.
        return {};
      }
      return {
        streamingEdits: {
          ...state.streamingEdits,
          [callId]: { path, diff: initialDiff, status: 'streaming' as const },
        },
      };
    });
  },

  streamingEditUpdate(callId: string, diff: string) {
    set(state => {
      const entry = state.streamingEdits[callId];
      if (!entry) return {};  // late update after cancel — drop silently
      if (entry.diff === diff) return {};  // no-op — same content
      return {
        streamingEdits: {
          ...state.streamingEdits,
          [callId]: { ...entry, diff },
        },
      };
    });
  },

  streamingEditFinalize(callId: string) {
    set(state => {
      const entry = state.streamingEdits[callId];
      if (!entry) return {};
      if (entry.status === 'finalized') return {};
      return {
        streamingEdits: {
          ...state.streamingEdits,
          [callId]: { ...entry, status: 'finalized' as const },
        },
      };
    });
  },

  streamingEditCancel(callId: string) {
    set(state => {
      if (!(callId in state.streamingEdits)) return {};
      const next = { ...state.streamingEdits };
      delete next[callId];
      return { streamingEdits: next };
    });
  },

  clearStreamingEdits() {
    set(state => Object.keys(state.streamingEdits).length === 0 ? {} : { streamingEdits: {} });
  },

  finalizeToolChain() {
    // Move current active tool calls into individual UiMessage entries.
    // Before clearing toolOutputStreams, merge any accumulated stream output
    // into each tool call's data so it survives finalization.
    const state = get();
    const tools = Array.from(state.activeToolCalls.values());
    if (tools.length === 0) return;
    const streams = state.toolOutputStreams;
    const toolMessages: UiMessage[] = tools.map(tc => {
      const stream = streams[tc.id];
      const output = tc.output || stream || undefined;
      return {
        ts: uniqueTs(),
        type: 'SAY' as const,
        say: 'TOOL' as const,
        toolCallData: {
          toolCallId: tc.id,
          toolName: tc.name,
          args: tc.args,
          status: tc.status,
          result: tc.result,
          output,
          durationMs: tc.durationMs,
          diff: tc.diff,
          // Multimodal-agent Phase 6 — carry tool-produced image metadata
          // through into the persisted UiMessage so reloading a session
          // shows the badge.
          imageRefs: tc.imageRefs,
        },
      };
    });
    set({
      messages: capMessages([...state.messages, ...toolMessages]),
      activeToolCalls: new Map(),
      toolOutputStreams: {},
      toolCallOpen: {},
    });
  },

  clearChat() {
    set({
      messages: [],
      streamingText: null,
      streamingMsgTs: null,
      streamingThinkingText: null,
      streamingThinkingTs: null,
      streamingEdits: {},
      activeToolCalls: new Map(),
      // Drop tool output streams in lockstep with activeToolCalls.
      toolOutputStreams: {},
      toolCallOpen: {},
      plan: null,
      handoff: null,
      planCompletedPendingClear: false,
      seenPlanSummaries: new Set<string>(),
      workingFallbackPhrase: null,
      questions: null,
      questionSummary: null,
      retryState: null,
      tasks: [],
      sessionStats: null,
      viewMode: 'chat',
      resumeSessionId: null,
      nextStepHint: null,
      aggregateDiff: null,
    });
  },

  setPlan(plan: Plan) {
    set(state => {
      const current = state.plan;
      const isIdentical = current !== null &&
        plan.title === current.title &&
        plan.summary === current.summary &&
        plan.markdown === current.markdown;
      if (isIdentical) {
        // Content unchanged — update non-comment fields but keep planCommentCount
        return { plan };
      }
      return { plan, planCommentCount: 0 };
    });
  },

  clearPlan() {
    set({ plan: null, planCommentCount: 0 });
  },

  setHandoff(handoff: Handoff) {
    set({ handoff });
  },

  clearHandoff() {
    set({ handoff: null });
  },

  approvePlan() {
    set(state => {
      if (!state.plan) return {};
      return { plan: { ...state.plan, approved: true } };
    });
  },

  markPlanSummarySeen(identity: string) {
    set(state => {
      if (state.seenPlanSummaries.has(identity)) return {};
      const next = new Set(state.seenPlanSummaries);
      next.add(identity);
      return { seenPlanSummaries: next };
    });
  },

  setWorkingFallbackPhrase(phrase: string) {
    set({ workingFallbackPhrase: phrase });
  },

  updatePlanSummary(summary: string) {
    set(state => {
      if (!state.plan) return {};
      return { plan: { ...state.plan, summary } };
    });
  },

  setPlanCommentCount(count: number) {
    set({ planCommentCount: count });
  },

  setPlanPending(state: 'approve' | 'revise' | null) {
    set({ planPending: state });
  },

  showQuestions(questions: Question[]) {
    set({ questions, activeQuestionIndex: 0, questionSummary: null });
  },

  clearQuestions() {
    set({ questions: null, questionSummary: null, activeQuestionIndex: 0 });
  },

  finalizeQuestionsAsMessage() {
    set(state => {
      const snapshot = state.questions;
      if (!snapshot || snapshot.length === 0) {
        return { questions: null, questionSummary: null, activeQuestionIndex: 0 };
      }
      const msg: UiMessage = {
        ts: uniqueTs(),
        type: 'ASK',
        ask: 'QUESTION_WIZARD',
        questionData: {
          questions: snapshot.map(q => ({
            text: q.text,
            options: q.options.map(o => o.label),
          })),
          currentIndex: snapshot.length - 1,
          answers: snapshot.reduce((acc, q, i) => {
            if (q.answer) {
              const ids = Array.isArray(q.answer) ? q.answer : [q.answer];
              const labels = ids.map(id => {
                const opt = q.options.find(o => (o.id ?? o.label) === id);
                return opt?.label ?? id;  // fallback: if id not found in options, show it as-is (handles free-text answers)
              });
              acc[i] = labels.join(', ');
            }
            return acc;
          }, {} as Record<number, string>),
          status: 'COMPLETED',
        },
      };
      return {
        messages: capMessages([...state.messages, msg]),
        questions: null,
        questionSummary: null,
        activeQuestionIndex: 0,
      };
    });
  },

  showQuestion(index: number) {
    set({ activeQuestionIndex: index });
  },

  showQuestionSummary(summary: any) {
    set({ questionSummary: summary });
  },

  answerQuestion(qid: string, answer: string[]) {
    set(state => ({
      questions: state.questions?.map(q =>
        q.id === qid ? { ...q, answer, skipped: false } : q
      ) ?? null,
    }));
  },

  skipQuestion(qid: string) {
    set(state => ({
      questions: state.questions?.map(q =>
        q.id === qid ? { ...q, skipped: true, answer: undefined } : q
      ) ?? null,
    }));
  },

  setInputLocked(locked: boolean) {
    set(state => ({
      inputState: { ...state.inputState, locked },
    }));
  },

  setInputMode(mode: 'agent' | 'plan') {
    set(state => ({
      inputState: { ...state.inputState, mode },
    }));
  },

  setBusy(busy: boolean) {
    // Bug 9 — do NOT clear smartWorkingPhrase on every busy=false.
    // The previous behaviour cleared it whenever the loop briefly went non-busy
    // between iterations, which combined with WorkingIndicator unmounting and
    // re-rolling its random fallback meant the phrase changed every iteration.
    // smartWorkingPhrase now persists until the task fully completes (clearChat /
    // resetSession path) or until Haiku replaces it with a meaningful new phrase.
    set({ busy });
  },

  setCompactionState(active: boolean, phase: string) {
    set({ compactionState: { active, phase } });
  },

  insertCompactionMarker(payload) {
    const marker: UiMessage = {
      ts: uniqueTs(),
      type: 'SAY',
      say: 'COMPACTION_MARKER',
      compactionMarker: payload,
    };
    set((state) => ({ messages: capMessages([...state.messages, marker]) }));
  },

  setSteeringMode(enabled: boolean) {
    set({ steeringMode: enabled });
  },

  setModelName(model: string) {
    set(state => ({
      inputState: { ...state.inputState, model },
    }));
  },

  setModelFallbackState(isFallback: boolean, reason: string | null) {
    set(state => ({
      inputState: {
        ...state.inputState,
        modelFallbackReason: isFallback ? (reason ?? '') : null,
      },
    }));
  },

  updateTokenBudget(used: number, max: number) {
    set({ tokenBudget: { used, max } });
  },

  updateSkillsList(skills: Skill[]) {
    set({ skillsList: skills });
  },

  showSkillBanner(name: string) {
    set({ skillBanner: name });
  },

  hideSkillBanner() {
    set({ skillBanner: null });
  },

  showToolsPanel(toolsJson: string) {
    set({ showingToolsPanel: true, toolsPanelData: toolsJson });
  },

  hideToolsPanel() {
    set({ showingToolsPanel: false, toolsPanelData: null });
  },

  showRetryButton(kind: 'continue' | 'retry', caption: string) {
    set({ retryState: { kind, caption } });
  },

  focusInput() {
    set(state => ({ focusInputTrigger: state.focusInputTrigger + 1 }));
  },

  addChart(chartConfigJson: string) {
    const msg: UiMessage = {
      ts: uniqueTs(),
      type: 'SAY',
      say: 'TEXT',
      text: JSON.stringify({ type: 'chart', config: chartConfigJson }),
    };
    set(state => ({ messages: capMessages([...state.messages, msg]) }));
  },

  addAnsiOutput(text: string) {
    const msg: UiMessage = {
      ts: uniqueTs(),
      type: 'SAY',
      say: 'TEXT',
      text: JSON.stringify({ type: 'ansi', text }),
    };
    set(state => ({ messages: capMessages([...state.messages, msg]) }));
  },

  addTabs(tabsJson: string) {
    const msg: UiMessage = {
      ts: uniqueTs(),
      type: 'SAY',
      say: 'TEXT',
      text: JSON.stringify({ type: 'tabs', data: tabsJson }),
    };
    set(state => ({ messages: capMessages([...state.messages, msg]) }));
  },

  addTimeline(itemsJson: string) {
    const msg: UiMessage = {
      ts: uniqueTs(),
      type: 'SAY',
      say: 'TEXT',
      text: JSON.stringify({ type: 'timeline', data: itemsJson }),
    };
    set(state => ({ messages: capMessages([...state.messages, msg]) }));
  },

  addProgressBar(percent: number, type: string) {
    const msg: UiMessage = {
      ts: uniqueTs(),
      type: 'SAY',
      say: 'TEXT',
      text: JSON.stringify({ type: 'progressBar', percent, barType: type }),
    };
    set(state => ({ messages: capMessages([...state.messages, msg]) }));
  },

  addJiraCard(cardJson: string) {
    const msg: UiMessage = {
      ts: uniqueTs(),
      type: 'SAY',
      say: 'TEXT',
      text: JSON.stringify({ type: 'jiraCard', data: cardJson }),
    };
    set(state => ({ messages: capMessages([...state.messages, msg]) }));
  },

  addSonarBadge(badgeJson: string) {
    const msg: UiMessage = {
      ts: uniqueTs(),
      type: 'SAY',
      say: 'TEXT',
      text: JSON.stringify({ type: 'sonarBadge', data: badgeJson }),
    };
    set(state => ({ messages: capMessages([...state.messages, msg]) }));
  },

  showSkeleton() {
    set({ showingSkeleton: true });
  },

  hideSkeleton() {
    set({ showingSkeleton: false });
  },

  showToast(message: string, type: string, durationMs: number) {
    const toast: Toast = {
      id: nextId('toast'),
      message,
      type: type as ToastType,
      durationMs,
    };
    set(state => ({ toasts: [...state.toasts, toast] }));
    if (durationMs > 0) {
      setTimeout(() => {
        get().dismissToast(toast.id);
      }, durationMs);
    }
  },

  dismissToast(id: string) {
    set(state => ({
      toasts: state.toasts.filter(t => t.id !== id),
    }));
  },

  receiveMentionResults(query: string, results: MentionSearchResult[]) {
    set({ mentionResults: results, mentionResultsQuery: query });
  },

  showApproval(toolName: string, riskLevel: string, description?: string, metadata?: Array<{ key: string; value: string }>, diffContent?: string, commandPreview?: ApprovalCommandPreview, allowSessionApproval: boolean = true, originAgentId?: string | null, originLabel?: string | null) {
    set(state => {
      const approval: PendingApproval = {
        toolName,
        riskLevel,
        title: `Approve ${toolName}? (${riskLevel} risk)`,
        description,
        metadata,
        diffContent,
        commandPreview,
        allowSessionApproval,
        originAgentId,
        originLabel,
      };

      // Drain any active tool chain into individual UiMessage entries and
      // flush any in-flight streaming buffer so the approval card renders
      // below all prior content.
      const hadStream = state.streamingText != null && state.streamingMsgTs != null;
      const tools = Array.from(state.activeToolCalls.values());
      let newMessages = [...state.messages];

      if (hadStream) {
        newMessages.push({
          ts: state.streamingMsgTs!,
          type: 'SAY' as const,
          say: 'TEXT' as const,
          text: state.streamingText!,
          partial: false,
        });
      }

      if (tools.length > 0) {
        const streams = state.toolOutputStreams;
        for (const tc of tools) {
          const s = streams[tc.id];
          const output = tc.output || s || undefined;
          newMessages.push({
            ts: uniqueTs(),
            type: 'SAY' as const,
            say: 'TOOL' as const,
            toolCallData: {
              toolCallId: tc.id,
              toolName: tc.name,
              args: tc.args,
              status: tc.status,
              result: tc.result,
              output,
              durationMs: tc.durationMs,
              diff: tc.diff,
              // Multimodal-agent Phase 6 — preserve tool-produced image
              // metadata across this drain path too.
              imageRefs: tc.imageRefs,
            },
          });
        }
      }

      return {
        pendingApproval: approval,
        ...(hadStream ? { streamingText: null, streamingMsgTs: null } : {}),
        ...(state.streamingThinkingTs != null ? { streamingThinkingText: null, streamingThinkingTs: null } : {}),
        ...(tools.length > 0 ? { activeToolCalls: new Map(), toolOutputStreams: {}, toolCallOpen: {} } : {}),
        ...(newMessages.length !== state.messages.length ? { messages: capMessages(newMessages) } : {}),
      };
    });
  },

  resolveApproval(decision: 'approve' | 'deny' | 'allowForSession') {
    const pending = get().pendingApproval;
    set({ pendingApproval: null });
    import('../bridge/jcef-bridge').then(({ kotlinBridge }) => {
      if (decision === 'approve') {
        (kotlinBridge as any).approveToolCall();
      } else if (decision === 'allowForSession' && pending?.toolName) {
        (kotlinBridge as any).allowToolForSession(pending.toolName);
      } else {
        (kotlinBridge as any).denyToolCall();
      }
    });
  },

  showProcessInput(processId: string, description: string, prompt: string, command: string) {
    set({ pendingProcessInput: { processId, description, prompt, command } });
  },

  resolveProcessInput(input: string) {
    set({ pendingProcessInput: null });
    import('../bridge/jcef-bridge').then(({ kotlinBridge }) => {
      (kotlinBridge as any).resolveProcessInput(input);
    });
  },

  sendMessage(text: string, mentions: Mention[], attachments?: Array<{ sha256: string; mime: string; size: number; originalFilename: string }>) {
    // Do NOT add the user message here — Kotlin is authoritative.
    // For first messages: startSession() adds it atomically.
    // For subsequent messages: appendUserMessage() adds it from Kotlin.
    // Clear the retry pill immediately so it doesn't persist into the new turn.
    set({ retryState: null });
    const hasAttachments = attachments && attachments.length > 0;
    import('../bridge/jcef-bridge').then(({ kotlinBridge }) => {
      // Route through sendMessageWithMentions whenever there are mentions OR
      // attachments — that's the single Kotlin path that carries structured
      // payloads. Plain sendMessage is text-only and would lose attachments.
      if (mentions.length > 0 || hasAttachments) {
        kotlinBridge.sendMessageWithMentions(text, JSON.stringify(mentions), hasAttachments ? JSON.stringify(attachments) : undefined);
      } else {
        kotlinBridge.sendMessage(text);
      }
    });
  },

  setDebugLogVisible(visible: boolean) {
    set({ debugLogVisible: visible });
  },

  addDebugLogEntry(entry: DebugLogEntry) {
    set(state => {
      const entries = [...state.debugLogEntries, entry];
      // Cap at max entries, FIFO — drop oldest when over limit
      const capped = entries.length > DEBUG_LOG_MAX_ENTRIES
        ? entries.slice(entries.length - DEBUG_LOG_MAX_ENTRIES)
        : entries;
      return { debugLogEntries: capped };
    });
  },

  clearDebugLog() {
    set({ debugLogEntries: [] });
  },

  appendToolOutput(toolCallId: string, chunk: string) {
    set(state => ({
      toolOutputStreams: {
        ...state.toolOutputStreams,
        [toolCallId]: (state.toolOutputStreams[toolCallId] || '') + chunk,
      }
    }));
  },

  killToolCall(toolCallId: string) {
    import('../bridge/jcef-bridge').then(({ kotlinBridge }) => {
      (kotlinBridge as any).killToolCall(toolCallId);
    });
  },

  // ── Aggregate Diff Actions ──
  updateAggregateDiff(diff: AggregateDiff) {
    set({ aggregateDiff: diff });
  },

  setSmartWorkingPhrase(phrase: string) {
    set({ smartWorkingPhrase: phrase });
  },

  setSessionTitle(title: string) {
    set({ sessionTitle: title });
  },
  /** Bumps sessionTitleAnimateKey so the SessionTitle component plays the
   *  scramble transition. Called by Kotlin after Haiku's on-completion eval. */
  setSessionTitleAnimated(title: string) {
    set(state => ({
      sessionTitle: title,
      sessionTitleAnimateKey: state.sessionTitleAnimateKey + 1,
    }));
  },

  // ── Queued Steering Actions ──
  addQueuedSteeringMessage(id: string, text: string) {
    set((state) => ({
      queuedSteeringMessages: [...state.queuedSteeringMessages, { id, text, timestamp: Date.now() }],
    }));
  },

  removeQueuedSteeringMessage(id: string) {
    set((state) => ({
      queuedSteeringMessages: state.queuedSteeringMessages.filter(m => m.id !== id),
    }));
  },

  promoteQueuedSteeringMessages(ids: string[]) {
    // Promote only the specific messages that were drained by the agent.
    // Before appending the user messages, we must flush any in-flight streaming
    // buffer AND drain any still-active tool calls into `messages[]` so the
    // visual order matches the real-time order: prior agent text and tool calls
    // first, then the user's steering message at the bottom.
    //
    // Without this drain, the user message would land at the end of `messages[]`
    // while the active tool calls remained in `activeToolCalls` (rendered inside
    // `ChatFooter` below the message list), producing the inversion bug where
    // the user's "wait, also do C" appears above tool calls that completed
    // before it was typed.
    set((state) => {
      const idSet = new Set(ids);
      const toPromote = state.queuedSteeringMessages.filter(m => idSet.has(m.id));
      const remaining = state.queuedSteeringMessages.filter(m => !idSet.has(m.id));

      // 1. Flush in-flight stream into a finalized TEXT message.
      const streamFlush: UiMessage[] = (state.streamingText != null && state.streamingMsgTs != null)
        ? [{
            ts: state.streamingMsgTs,
            type: 'SAY' as const,
            say: 'TEXT' as const,
            text: state.streamingText,
            partial: false,
          }]
        : [];

      // 2. Drain still-active tool calls into TOOL messages (mirrors the
      //    drain logic at appendToken:676-703 and finalizeToolChain).
      const streams = state.toolOutputStreams;
      const toolDrain: UiMessage[] = state.activeToolCalls.size > 0
        ? Array.from(state.activeToolCalls.values()).map(tc => {
            const s = streams[tc.id];
            const output = tc.output || s || undefined;
            return {
              ts: uniqueTs(),
              type: 'SAY' as const,
              say: 'TOOL' as const,
              toolCallData: {
                toolCallId: tc.id,
                toolName: tc.name,
                args: tc.args,
                status: tc.status,
                result: tc.result,
                output,
                durationMs: tc.durationMs,
                diff: tc.diff,
                imageRefs: tc.imageRefs,
              },
            };
          })
        : [];

      // 3. Build the promoted user messages last.
      const newMessages: UiMessage[] = toPromote.map(m => ({
        ts: m.timestamp,
        type: 'SAY' as const,
        say: 'USER_MESSAGE' as const,
        text: m.text,
      }));

      return {
        messages: capMessages([...state.messages, ...streamFlush, ...toolDrain, ...newMessages]),
        queuedSteeringMessages: remaining,
        streamingText: null,
        streamingMsgTs: null,
        activeToolCalls: state.activeToolCalls.size > 0 ? new Map() : state.activeToolCalls,
        toolOutputStreams: state.activeToolCalls.size > 0 ? {} : state.toolOutputStreams,
        toolCallOpen: state.activeToolCalls.size > 0 ? {} : state.toolCallOpen,
      };
    });
  },

  restoreInputText(text: string) {
    set({ restoredInputText: text });
  },

  clearRestoredInputText() {
    set({ restoredInputText: null });
  },

  // ── Artifact Actions ──
  addArtifact(payload: string) {
    try {
      const { title: _title, source, renderId } = JSON.parse(payload);
      if (!renderId || typeof renderId !== 'string') {
        console.warn('[chatStore] addArtifact: payload missing renderId', payload);
        return;
      }
      const msg: UiMessage = {
        ts: uniqueTs(),
        type: 'SAY',
        say: 'ARTIFACT_RESULT',
        text: source,
        artifactId: renderId,
      };
      set((state) => ({
        messages: capMessages([...state.messages, msg]),
      }));
    } catch (e) {
      console.warn('[chatStore] addArtifact: malformed payload', e);
    }
  },

  // ── Sub-Agent Actions ──
  // messages[] is the single source of truth for sub-agent state.
  // Each sub-agent corresponds to exactly one UiMessage whose subagentData
  // holds the full SubAgentState. All mutations update that field in-place.
  // Use selectActiveSubAgents(state) to derive a Map<agentId, SubAgentState>.

  spawnSubAgent(payload: string) {
    const data = JSON.parse(payload);

    set((state) => {
      // Dedupe on agentId — messages[] is the sole source of truth now
      if (state.messages.some((m) => m.subagentData?.agentId === data.agentId)) {
        return state;
      }

      const agentState: SubAgentState = {
        agentId: data.agentId,
        label: data.label || 'general-purpose',
        model: typeof data.model === 'string' && data.model.length > 0 ? data.model : undefined,
        status: 'RUNNING',
        iteration: 1,
        tokensUsed: 0,
        messages: [],
        activeToolChain: [],
        summary: undefined,
        startedAt: Date.now(),
      };

      const msg: UiMessage = {
        ts: uniqueTs(),
        type: 'SAY',
        say: 'SUBAGENT_STARTED',
        subagentData: agentState,
      };

      return {
        messages: capMessages([...state.messages, msg]),
      };
    });
  },

  updateSubAgentIteration(payload: string) {
    const data = JSON.parse(payload);
    set((state) => {
      const messages = state.messages.map((m): UiMessage => {
        if (!m.subagentData || m.subagentData.agentId !== data.agentId) return m;
        const sub = m.subagentData;
        return {
          ...m,
          subagentData: {
            ...sub,
            iteration: data.iteration ?? ((sub.iteration ?? 0) + 1),
            tokensUsed: data.tokensUsed ?? sub.tokensUsed,
          } as SubAgentState,
        };
      });
      return { messages };
    });
  },

  addSubAgentToolCall(payload: string) {
    const data = JSON.parse(payload);
    set((state) => {
      // Field names use the `tool*` prefix to match the Kotlin payload keys from
      // AgentCefPanel.addSubAgentToolCall (put("toolArgs", ...)). Reading bare
      // `data.args` here silently returned undefined, which is one reason the
      // expanded view rendered empty.
      const tc: ToolCall = {
        id: data.toolCallId || `sa-tc-${uniqueTs()}`,
        name: data.toolName || 'unknown',
        args: data.toolArgs || '',
        status: 'RUNNING',
      };
      const messages = state.messages.map((m): UiMessage => {
        if (!m.subagentData || m.subagentData.agentId !== data.agentId) return m;
        const sub = m.subagentData;
        return {
          ...m,
          say: 'SUBAGENT_PROGRESS' as const,
          subagentData: {
            ...sub,
            activeToolChain: [...sub.activeToolChain, tc],
          } as SubAgentState,
        };
      });
      return { messages };
    });
  },

  updateSubAgentToolCall(payload: string) {
    const data = JSON.parse(payload);
    set((state) => {
      let newStreams = state.toolOutputStreams;
      const messages = state.messages.map((m): UiMessage => {
        if (!m.subagentData || m.subagentData.agentId !== data.agentId) return m;
        const agent = m.subagentData;
        const status: ToolCallStatus = data.isError ? 'ERROR' : 'COMPLETED';
        // Finalize the tool: move from activeToolChain to messages
        const matchIdx = agent.activeToolChain.findIndex(tc => tc.name === data.toolName && tc.status === 'RUNNING');
        const finalized = matchIdx >= 0 ? agent.activeToolChain[matchIdx]! : null;
        const remaining = matchIdx >= 0
          ? [...agent.activeToolChain.slice(0, matchIdx), ...agent.activeToolChain.slice(matchIdx + 1)]
          : agent.activeToolChain;

        // Kotlin payload keys use the `tool*` prefix (toolResult / toolDurationMs /
        // toolOutput / toolDiff — see AgentCefPanel.updateSubAgentToolCall). Reading
        // `data.result` / `data.durationMs` returned undefined previously, which is
        // why the expanded sub-agent tool view rendered empty even when the tool
        // produced output.
        const finalizedId = finalized?.id || data.toolCallId || `sa-tc-${uniqueTs()}`;
        // Fallback to accumulated stream chunks when the Kotlin side didn't ship an
        // explicit `toolOutput` (happens when ToolResult.content equals .summary —
        // e.g. a run_command whose only detail is in streamed stdout). Mirrors the
        // main agent's finalizeToolChain merge so the completed message carries the
        // full output instead of losing it when the stream entry is later cleared.
        const streamOutput = state.toolOutputStreams[finalizedId];
        const mergedOutput = data.toolOutput || streamOutput || undefined;

        // Release the accumulated stream entry now that its content is baked into
        // the finalized message — prevents unbounded toolOutputStreams growth over
        // long sessions with many sub-agent tool invocations.
        if (streamOutput !== undefined) {
          newStreams = { ...newStreams };
          delete newStreams[finalizedId];
        }

        // Multimodal-agent Phase 6 — accept tool-produced image metadata from
        // the sub-agent JSON payload. Defensive: tolerate missing/non-array
        // values without breaking the rest of the message build.
        const subagentImageRefs = Array.isArray(data.imageRefs) && data.imageRefs.length > 0
          ? (data.imageRefs as ImageRef[])
          : undefined;
        const toolMsg: UiMessage = {
          ts: uniqueTs(),
          type: 'SAY',
          say: 'TOOL',
          toolCallData: {
            toolCallId: finalizedId,
            toolName: data.toolName || 'unknown',
            args: finalized?.args || '',
            status,
            result: data.toolResult || '',
            output: mergedOutput,
            diff: data.toolDiff,
            durationMs: data.toolDurationMs || 0,
            imageRefs: subagentImageRefs,
          },
        };

        return {
          ...m,
          subagentData: {
            ...agent,
            activeToolChain: remaining,
            messages: [...agent.messages, toolMsg],
          } as SubAgentState,
        };
      });
      return { messages, toolOutputStreams: newStreams };
    });
  },

  updateSubAgentMessage(payload: string) {
    const data = JSON.parse(payload);
    set((state) => {
      const textMsg: UiMessage = {
        ts: uniqueTs(),
        type: 'SAY',
        say: 'TEXT',
        text: data.textContent || '',
      };
      const messages = state.messages.map((m): UiMessage => {
        if (!m.subagentData || m.subagentData.agentId !== data.agentId) return m;
        const sub = m.subagentData;
        return {
          ...m,
          say: 'SUBAGENT_PROGRESS' as const,
          subagentData: {
            ...sub,
            messages: [...sub.messages, textMsg],
          } as SubAgentState,
        };
      });
      return { messages };
    });
  },

  /**
   * Append a raw streaming token/chunk to the sub-agent card's last partial TEXT
   * message. Unlike [updateSubAgentMessage] which replaces the whole text, this
   * incrementally appends — mirroring how the main agent's streaming works —
   * so the user sees tokens flow in the sub-agent card rather than a single
   * delayed block.
   *
   * If no partial TEXT message exists yet for this sub-agent, one is created.
   */
  appendSubAgentStreamDelta(payload: string) {
    const { agentId, delta } = JSON.parse(payload) as { agentId: string; delta: string };
    if (!delta) return;
    set((state) => {
      const messages = state.messages.map((m): UiMessage => {
        if (!m.subagentData || m.subagentData.agentId !== agentId) return m;
        const agent = m.subagentData;
        const msgs = agent.messages;
        const lastIdx = msgs.length - 1;
        const last = lastIdx >= 0 ? msgs[lastIdx] : undefined;
        let nextMsgs: UiMessage[];
        if (last && last.type === 'SAY' && last.say === 'TEXT') {
          const updated: UiMessage = { ...last, text: (last.text ?? '') + delta };
          nextMsgs = msgs.slice(0, lastIdx).concat(updated);
        } else {
          const textMsg: UiMessage = {
            ts: uniqueTs(),
            type: 'SAY',
            say: 'TEXT',
            text: delta,
          };
          nextMsgs = [...msgs, textMsg];
        }
        return {
          ...m,
          subagentData: { ...agent, messages: nextMsgs } as SubAgentState,
        };
      });
      return { messages };
    });
  },

  /**
   * Append a thinking-block delta to the sub-agent's live streaming buffer.
   * Mirrors the main agent's appendToThinking path. No-op if the agentId is
   * not found in messages[].
   */
  appendSubAgentThinking(agentId: string, delta: string) {
    set((state) => {
      const messages = state.messages.map((m): UiMessage => {
        if (!m.subagentData || m.subagentData.agentId !== agentId) return m;
        const sub = m.subagentData;
        const prev = sub.streamingThinkingText ?? '';
        return {
          ...m,
          subagentData: { ...sub, streamingThinkingText: prev + delta } as SubAgentState,
        };
      });
      return { messages };
    });
  },

  /**
   * Mark the close of the current <thinking> block for agentId. Flushes the
   * accumulated buffer into the sub-agent's messages as a REASONING UiMessage
   * and clears the live buffer.
   */
  endSubAgentThinking(agentId: string) {
    set((state) => {
      const messages = state.messages.map((m): UiMessage => {
        if (!m.subagentData || m.subagentData.agentId !== agentId) return m;
        const sub = m.subagentData;
        const text = sub.streamingThinkingText ?? '';
        if (!text) {
          // Empty thinking block — nothing to finalise.
          return {
            ...m,
            subagentData: { ...sub, streamingThinkingText: null } as SubAgentState,
          };
        }
        const finalised: UiMessage = {
          ts: uniqueTs(),
          type: 'SAY',
          say: 'REASONING',
          text,
        };
        return {
          ...m,
          subagentData: {
            ...sub,
            messages: [...sub.messages, finalised],
            streamingThinkingText: null,
          } as SubAgentState,
        };
      });
      return { messages };
    });
  },

  completeSubAgent(payload: string) {
    const data = JSON.parse(payload);
    set((state) => {
      const messages = state.messages.map((m): UiMessage => {
        if (!m.subagentData || m.subagentData.agentId !== data.agentId) return m;
        const sub = m.subagentData;
        return {
          ...m,
          say: 'SUBAGENT_COMPLETED' as const,
          subagentData: {
            ...sub,
            status: (data.isError ? 'ERROR' : 'COMPLETED') as SubAgentState['status'],
            summary: data.textContent || '',
            tokensUsed: data.tokensUsed ?? sub.tokensUsed,
            activeToolChain: [], // clear any in-flight tools
          } as SubAgentState,
        };
      });
      return { messages };
    });
  },

  killSubAgent(agentId: string) {
    set((state) => {
      const messages = state.messages.map((m): UiMessage => {
        if (!m.subagentData || m.subagentData.agentId !== agentId) return m;
        const sub = m.subagentData;
        return {
          ...m,
          say: 'SUBAGENT_COMPLETED' as const,
          subagentData: {
            ...sub,
            status: 'KILLED' as const,
            activeToolChain: [],
          } as SubAgentState,
        };
      });
      return { messages };
    });
    // Notify Kotlin to cancel the worker
    import('../bridge/jcef-bridge').then(({ kotlinBridge }) => {
      kotlinBridge.killSubAgent(agentId);
    });
  },

  // ── Session Rehydration ──
  hydrateFromUiMessages(uiMessages: UiMessage[]) {
    // Filter out internal tracking messages and partial (interrupted) messages.
    const visible = uiMessages.filter(
      m => m.say !== 'API_REQ_STARTED' && m.say !== 'API_REQ_FINISHED' && !m.partial
    );

    // Upgrade legacy TOOL messages for communication tools into their proper
    // semantic types.  Old sessions (or sessions from SessionMigrator) may
    // have attempt_completion / plan_mode_respond / ask_followup_question /
    // ask_questions / act_mode_respond (removed) stored as generic TOOL messages.
    // The current persistence code (AgentLoop) already saves them correctly,
    // but we need to handle the old format gracefully.
    const upgraded = visible.map(m => {
      if (m.say !== 'TOOL' || !m.toolCallData) return m;
      const { toolName, args } = m.toolCallData;
      const parsed = safeJsonParse(args);

      switch (toolName) {
        case 'attempt_completion': {
          const result = parsed?.result ?? m.toolCallData.result ?? '';
          return {
            ...m,
            type: 'ASK' as const,
            say: undefined,
            ask: 'COMPLETION_RESULT' as const,
            text: result,
            toolCallData: undefined,
            completionData: { kind: 'done' as CompletionKind, result },
          };
        }
        case 'plan_mode_respond': {
          const response = parsed?.response ?? m.toolCallData.result ?? '';
          return {
            ...m,
            type: 'SAY' as const,
            say: 'TEXT' as const,
            ask: undefined,
            text: response,
            toolCallData: undefined,
          };
        }
        case 'act_mode_respond': {
          const response = parsed?.response ?? m.toolCallData.result ?? '';
          return {
            ...m,
            type: 'SAY' as const,
            say: 'TEXT' as const,
            ask: undefined,
            text: response,
            toolCallData: undefined,
          };
        }
        case 'ask_followup_question': {
          const question = parsed?.question ?? m.toolCallData.result ?? '';
          return {
            ...m,
            type: 'ASK' as const,
            say: undefined,
            ask: 'FOLLOWUP' as const,
            text: question,
            toolCallData: undefined,
          };
        }
        case 'ask_questions': {
          const question = parsed?.question ?? parsed?.questions?.[0]?.question ?? m.toolCallData.result ?? '';
          return {
            ...m,
            type: 'SAY' as const,
            say: 'TEXT' as const,
            ask: undefined,
            text: question,
            toolCallData: undefined,
          };
        }
        default:
          return m;
      }
    });

    // Upgrade legacy subagentData shapes (pre-Phase 4) to the full SubAgentState
    // shape so SubAgentView never calls .match on an undefined label.
    const withSubagentUpgrade = upgraded.map(m => {
      if (!m.subagentData) return m;
      const upgraded2 = upgradeLegacySubagentData(m.subagentData);
      if (upgraded2 === m.subagentData) return m;
      return { ...m, subagentData: upgraded2 };
    });

    // Restore plan from last PLAN_UPDATE message (steps field removed in Phase 5 — task system port)
    let restoredPlan: Plan | null = null;
    for (let i = withSubagentUpgrade.length - 1; i >= 0; i--) {
      const m = withSubagentUpgrade[i]!;
      if (m.planData) {
        const pd = m.planData;
        restoredPlan = {
          title: 'Plan',
          approved: pd.status === 'APPROVED' || pd.status === 'EXECUTING',
        };
        break;
      }
    }

    set({
      // capMessages on the hydration entry point so loading a multi-hour
      // session that already contains >1000 messages doesn't reintroduce the
      // long-conversation OOM trajectory that the live append paths now
      // guard against. UI-only — the agent's ContextManager still sees the
      // full persisted api_conversation_history.json.
      messages: capMessages(withSubagentUpgrade),
      streamingText: null,
      streamingMsgTs: null,
      streamingThinkingText: null,
      streamingThinkingTs: null,
      viewMode: 'chat',
      ...(restoredPlan ? { plan: restoredPlan } : {}),
    });
  },

  // ── History View ──
  setViewMode(mode: 'history' | 'chat') {
    set({ viewMode: mode });
  },
  setEditorTabMode(enabled: boolean) {
    set({ editorTabMode: enabled });
  },
  setHistoryItems(items: HistoryItem[]) {
    set({ historyItems: items });
  },
  setHistorySearch(query: string) {
    set({ historySearch: query });
  },

  setResumeSessionId(sessionId: string | null) {
    set({ resumeSessionId: sessionId });
  },

  // ── Session Stats Actions ──
  updateSessionStats(stats) {
    set({ sessionStats: stats });
  },

  clearSessionStats() {
    set({ sessionStats: null });
  },

  // ── Task Actions (task system port — Phase 5) ──
  // Bug 7 — short-circuit on structural equality so the Kotlin side re-emitting
  // an unchanged task list (e.g. on every persisted UI event) doesn't allocate
  // a new array reference and force every `s => s.tasks` subscriber to re-render.
  setTasks: (tasks) => set((state) => {
    if (tasksShallowEqual(state.tasks, tasks)) return {};
    return { tasks };
  }),
  applyTaskCreate: (task) => set((state) => {
    const existing = state.tasks.findIndex((t) => t.id === task.id);
    if (existing >= 0) {
      // Upsert: duplicate create (e.g. session resume replay) replaces in-place,
      // but only when the payload actually differs.
      if (taskFieldsEqual(state.tasks[existing]!, task)) return {};
      const updated = [...state.tasks];
      updated[existing] = task;
      return { tasks: updated };
    }
    return { tasks: [...state.tasks, task] };
  }),
  applyTaskUpdate: (task) => set((state) => {
    const existing = state.tasks.findIndex((t) => t.id === task.id);
    if (existing >= 0) {
      if (taskFieldsEqual(state.tasks[existing]!, task)) return {};
      const updated = [...state.tasks];
      updated[existing] = task;
      return { tasks: updated };
    }
    // Upsert: if the create event was missed (out-of-order delivery), fall through to append
    return { tasks: [...state.tasks, task] };
  }),

  // ── Background Process Actions (Phase 7, Task 7.3) ──
  setBackgroundProcesses: (snapshot) => set({ backgroundProcesses: snapshot }),
}));

/**
 * Derived selector: builds a Map<agentId, SubAgentState> from state.messages[].
 *
 * messages[] is the single source of truth for sub-agent state after the P4.T2
 * refactor. This selector replaces the former `state.activeSubAgents` field.
 *
 * The Map is rebuilt on every call — callers using this with useChatStore
 * should memoize via `useShallow` or a stable equality function to avoid
 * unnecessary re-renders.
 */
export function selectActiveSubAgents(state: ReturnType<typeof useChatStore.getState>): Map<string, SubAgentState> {
  const m = new Map<string, SubAgentState>();
  for (const msg of state.messages) {
    if (msg.subagentData) {
      m.set(msg.subagentData.agentId, msg.subagentData as SubAgentState);
    }
  }
  return m;
}
