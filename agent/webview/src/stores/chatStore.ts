import { create } from 'zustand';
import type {
  ToolCall,
  ToolCallStatus,
  Plan,
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
  EditStats,
  CheckpointInfo,
  RollbackInfo,
  UiMessage,
  SubAgentState,
  HistoryItem,
  CompletionData,
  CompletionKind,
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

// ── Monotonic timestamp generator ──
// Guarantees unique ts values even when multiple UiMessages are created
// in the same millisecond (e.g., draining tool calls in appendToken).
let _lastTs = 0;
function uniqueTs(): number {
  const now = Date.now();
  _lastTs = now > _lastTs ? now : _lastTs + 1;
  return _lastTs;
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
   * Timestamp of the `UiMessage` currently being written by the token stream,
   * or `null` if no stream is active. The streaming message lives in `messages`
   * like any other, updated in place via `appendToken`; this field just points
   * at it so `ChatView` can toggle the caret on the right message.
   */
  activeStream: { messageTs: number } | null;
  activeToolCalls: Map<string, ToolCall>;  // key = unique tool call ID
  /** Live sub-agent state — accumulates internal messages and tool chains while running.
   *  On completion/kill, the state is frozen into the UiMessage and removed from this map. */
  activeSubAgents: Map<string, SubAgentState>;
  plan: Plan | null;
  planCommentCount: number;
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
    ralph: boolean;
  };
  busy: boolean;
  steeringMode: boolean;
  showingToolsPanel: boolean;
  toolsPanelData: string | null;
  showingSkeleton: boolean;
  retryState: { kind: 'continue' | 'retry'; caption: string } | null;
  toasts: Toast[];
  skillBanner: string | null;
  skillsList: Skill[];
  tokenBudget: { used: number; max: number };
  memoryStats: { coreChars: number; archivalCount: number } | null;
  mentionResults: MentionSearchResult[];
  pendingApproval: PendingApproval | null;
  pendingProcessInput: PendingProcessInput | null;
  focusInputTrigger: number;
  debugLogVisible: boolean;
  debugLogEntries: DebugLogEntry[];
  toolOutputStreams: Record<string, string>;
  editStats: EditStats | null;
  checkpoints: CheckpointInfo[];
  rollbackEvents: RollbackInfo[];
  smartWorkingPhrase: string | null;
  sessionTitle: string | null;
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

  // Resume bar state — non-null when viewing a resumable session
  resumeSessionId: string | null;

  // Task state (task system port — Phase 5)
  tasks: Task[];

  // Actions
  startSession(task: string, mentions?: Mention[]): void;
  completeSession(info: SessionInfo): void;
  addUserMessage(text: string, mentions?: Mention[]): void;
  addPlanApprovedMessage(planMarkdown: string): void;
  addAgentText(text: string): void;
  appendToken(token: string): void;
  endStream(): void;
  addToolCall(toolCallId: string, name: string, args: string, status: ToolCallStatus): void;
  updateToolCall(name: string, status: ToolCallStatus, result: string, durationMs: number, output?: string, diff?: string, toolCallId?: string): void;
  finalizeToolChain(): void;
  addDiff(diff: EditDiff): void;
  addDiffExplanation(title: string, diffSource: string): void;
  addCompletionCard(data: CompletionData): void;
  addStatus(message: string, type: StatusType): void;
  addThinking(text: string): void;
  clearChat(): void;
  setPlan(plan: Plan): void;
  clearPlan(): void;
  approvePlan(): void;
  setPlanPending(state: 'approve' | 'revise' | null): void;

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
  setRalphLoop(enabled: boolean): void;
  setBusy(busy: boolean): void;
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
  updateMemoryStats(coreChars: number, archivalCount: number): void;
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
  receiveMentionResults(results: MentionSearchResult[]): void;
  showApproval(toolName: string, riskLevel: string, description?: string, metadata?: Array<{ key: string; value: string }>, diffContent?: string, commandPreview?: ApprovalCommandPreview, allowSessionApproval?: boolean): void;
  resolveApproval(decision: 'approve' | 'deny' | 'allowForSession'): void;
  showProcessInput(processId: string, description: string, prompt: string, command: string): void;
  resolveProcessInput(input: string): void;
  sendMessage(text: string, mentions: Mention[]): void;
  setDebugLogVisible(visible: boolean): void;
  addDebugLogEntry(entry: DebugLogEntry): void;
  clearDebugLog(): void;
  appendToolOutput(toolCallId: string, chunk: string): void;
  killToolCall(toolCallId: string): void;

  // Edit Stats + Checkpoint + Rollback Actions
  updateEditStats(stats: EditStats): void;
  updateCheckpoints(checkpoints: CheckpointInfo[]): void;
  applyRollback(rollback: RollbackInfo): void;
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
  completeSubAgent(payload: string): void;
  killSubAgent(agentId: string): void;

  // Session rehydration
  hydrateFromUiMessages(uiMessages: UiMessage[]): void;

  // History view actions
  setViewMode(mode: 'history' | 'chat'): void;
  setHistoryItems(items: HistoryItem[]): void;
  setHistorySearch(query: string): void;

  // Resume bar
  setResumeSessionId(sessionId: string | null): void;
}

export const useChatStore = create<ChatState>((set, get) => ({
  // Initial state
  messages: [],
  activeStream: null,
  activeToolCalls: new Map(),
  activeSubAgents: new Map(),
  plan: null,
  planCommentCount: 0,
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
    ralph: false,
  },
  busy: false,
  steeringMode: false,
  showingToolsPanel: false,
  toolsPanelData: null,
  showingSkeleton: false,
  retryState: null,
  toasts: [],
  skillBanner: null,
  skillsList: [],
  tokenBudget: { used: 0, max: 0 },
  memoryStats: null,
  mentionResults: [],
  pendingApproval: null,
  pendingProcessInput: null,
  focusInputTrigger: 0,
  debugLogVisible: false,
  debugLogEntries: [],
  toolOutputStreams: {},
  editStats: null,
  checkpoints: [],
  rollbackEvents: [],
  smartWorkingPhrase: null,
  sessionTitle: null,
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

  // Actions
  startSession(task: string, mentions?: Mention[]) {
    const firstMessage: UiMessage = {
      ts: uniqueTs(),
      type: 'SAY',
      say: 'USER_MESSAGE',
      text: task,
      ...(mentions && mentions.length > 0 ? { mentions } : {}),
    };
    set({
      messages: [firstMessage],
      activeStream: null,
      activeToolCalls: new Map(),
  activeSubAgents: new Map(),
      // Reset tool output streams alongside activeToolCalls — they are keyed by
      // the same tool call IDs and would otherwise leak across sessions.
      toolOutputStreams: {},
      plan: null,
      planCompletedPendingClear: false,
      questions: null,
      questionSummary: null,
      busy: true,
      steeringMode: true,
      retryState: null,
      smartWorkingPhrase: null,
      sessionTitle: null,
      editStats: null,
      checkpoints: [],
      queuedSteeringMessages: [],
      restoredInputText: null,
      tasks: [],
      sessionStats: null,
      viewMode: 'chat' as const,
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
        },
      };
    });
    const messages = toolMessages.length > 0
      ? [...state.messages, ...toolMessages]
      : state.messages;
    set({
      session: info,
      busy: false,
      steeringMode: false,
      activeStream: null,
      activeToolCalls: new Map(),
  activeSubAgents: new Map(),
      // Drop all tool output streams — the map was keyed by the now-cleared
      // tool call IDs and would otherwise leak for the rest of the app's life.
      toolOutputStreams: {},
      messages,
      queuedSteeringMessages: [],
      // Clear completed plan on session end (no more messages will arrive to trigger deferred clear)
      ...(state.planCompletedPendingClear ? { plan: null, planCompletedPendingClear: false } : {}),
    });
  },

  addUserMessage(text: string, mentions?: Mention[]) {
    const msg: UiMessage = {
      ts: uniqueTs(), type: 'SAY', say: 'USER_MESSAGE', text,
      ...(mentions && mentions.length > 0 ? { mentions } : {}),
    };
    set(state => ({ messages: [...state.messages, msg] }));
  },

  addPlanApprovedMessage(planMarkdown: string) {
    const msg: UiMessage = {
      ts: uniqueTs(),
      type: 'SAY',
      say: 'PLAN_APPROVED',
      text: 'Implementation plan approved',
      planApprovalData: { planMarkdown },
    };
    set(state => ({ messages: [...state.messages, msg] }));
  },

  addAgentText(text: string) {
    const msg: UiMessage = { ts: uniqueTs(), type: 'SAY', say: 'TEXT', text };
    set(state => ({ messages: [...state.messages, msg] }));
  },

  appendToken(token: string) {
    // Defensive no-op: Kotlin StreamBatcher may flush an empty coalesced chunk
    // on a timer boundary. Returning the same state object avoids thrashing
    // React.memo on every message for a non-event.
    if (token.length === 0) return;

    set(state => {
      const current = state.activeStream;

      if (current == null) {
        // First token of a new stream. Drain any leftover active tool calls
        // into individual UiMessage{say:'TOOL'} entries so the chat flow stays
        // chronological: prior tools → streaming text → next tool chain.
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
              },
            };
          });
          messages = [...messages, ...toolMsgs];
          activeToolCalls = new Map();
          toolOutputStreams = {};
        }

        const messageTs = uniqueTs();
        const placeholder: UiMessage = {
          ts: messageTs,
          type: 'SAY',
          say: 'TEXT',
          text: token,
          partial: true,
        };

        return {
          messages: [...messages, placeholder],
          activeStream: { messageTs },
          activeToolCalls,
          toolOutputStreams,
        };
      }

      // Subsequent tokens update the existing placeholder in place. Keeping
      // the same message ts lets React.memo skip every other message (we
      // return `m` by reference for non-matching items) so rendering stays
      // O(1) in the streaming message per token.
      return {
        messages: state.messages.map(m =>
          m.ts === current.messageTs ? { ...m, text: (m.text || '') + token } : m
        ),
      };
    });
  },

  endStream() {
    const state = get();
    const shouldClearPlan = state.planCompletedPendingClear;
    const streamTs = state.activeStream?.messageTs;
    set({
      // Flip partial to false on the streaming message
      ...(streamTs != null ? {
        messages: state.messages.map(m =>
          m.ts === streamTs ? { ...m, partial: false } : m
        ),
      } : {}),
      activeStream: null,
      ...(shouldClearPlan ? { plan: null, planCompletedPendingClear: false } : {}),
    });
  },

  addToolCall(toolCallId: string, name: string, args: string, status: ToolCallStatus) {
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

      newMap.set(id, { id, name, args, status });

      // Clear the streaming caret on any in-flight message so the tool call
      // below it renders as the next visual item. The message text itself
      // already lives in `messages` and stays put.
      if (state.activeStream != null) {
        return {
          activeStream: null,
          activeToolCalls: newMap,
        };
      }

      return { activeToolCalls: newMap };
    });
  },

  updateToolCall(name: string, status: ToolCallStatus, result: string, durationMs: number, output?: string, diff?: string, toolCallId?: string) {
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
      if (targetKey) {
        const existing = newMap.get(targetKey)!;
        newMap.set(targetKey, { ...existing, status, result, output, durationMs, ...(diff ? { diff } : {}) });
      } else {
        const id = toolCallId || nextId('tc');
        newMap.set(id, { id, name, args: '', status, result, output, durationMs, ...(diff ? { diff } : {}) });
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
    set(state => ({ messages: [...state.messages, msg] }));
  },

  addDiffExplanation(title: string, diffSource: string) {
    const msg: UiMessage = {
      ts: uniqueTs(),
      type: 'SAY',
      say: 'TEXT',
      text: JSON.stringify({ type: 'diff-explanation', title, diffSource }),
    };
    set(state => ({ messages: [...state.messages, msg] }));
  },

  addCompletionCard(data: CompletionData) {
    const msg: UiMessage = {
      ts: uniqueTs(),
      type: 'ASK',
      ask: 'COMPLETION_RESULT',
      completionData: data,
    };
    set(state => ({
      messages: [...state.messages, msg],
      activeStream: null,
    }));
  },

  addStatus(message: string, type: StatusType) {
    // ERROR gets its own say; all other status types use CHECKPOINT_CREATED
    // which the ChatView renders as a muted status line.
    const msg: UiMessage = {
      ts: uniqueTs(),
      type: 'SAY',
      say: type === 'ERROR' ? 'ERROR' : 'CHECKPOINT_CREATED',
      text: message,
    };
    set(state => ({ messages: [...state.messages, msg] }));
  },

  addThinking(text: string) {
    const msg: UiMessage = {
      ts: uniqueTs(),
      type: 'SAY',
      say: 'REASONING',
      text,
    };
    set(state => ({ messages: [...state.messages, msg] }));
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
        },
      };
    });
    set({
      messages: [...state.messages, ...toolMessages],
      activeToolCalls: new Map(),
  activeSubAgents: new Map(),
      toolOutputStreams: {},
    });
  },

  clearChat() {
    set({
      messages: [],
      activeStream: null,
      activeToolCalls: new Map(),
  activeSubAgents: new Map(),
      // Drop tool output streams in lockstep with activeToolCalls.
      toolOutputStreams: {},
      plan: null,
      planCompletedPendingClear: false,
      questions: null,
      questionSummary: null,
      retryState: null,
      tasks: [],
      sessionStats: null,
      viewMode: 'chat',
      resumeSessionId: null,
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

  approvePlan() {
    set(state => {
      if (!state.plan) return {};
      return { plan: { ...state.plan, approved: true } };
    });
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
        messages: [...state.messages, msg],
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

  setRalphLoop(enabled: boolean) {
    set(state => ({
      inputState: { ...state.inputState, ralph: enabled },
    }));
  },

  setBusy(busy: boolean) {
    set({ busy, ...(busy ? {} : { smartWorkingPhrase: null }) });
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

  updateMemoryStats(coreChars: number, archivalCount: number) {
    set({ memoryStats: { coreChars, archivalCount } });
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
    set(state => ({ messages: [...state.messages, msg] }));
  },

  addAnsiOutput(text: string) {
    const msg: UiMessage = {
      ts: uniqueTs(),
      type: 'SAY',
      say: 'TEXT',
      text: JSON.stringify({ type: 'ansi', text }),
    };
    set(state => ({ messages: [...state.messages, msg] }));
  },

  addTabs(tabsJson: string) {
    const msg: UiMessage = {
      ts: uniqueTs(),
      type: 'SAY',
      say: 'TEXT',
      text: JSON.stringify({ type: 'tabs', data: tabsJson }),
    };
    set(state => ({ messages: [...state.messages, msg] }));
  },

  addTimeline(itemsJson: string) {
    const msg: UiMessage = {
      ts: uniqueTs(),
      type: 'SAY',
      say: 'TEXT',
      text: JSON.stringify({ type: 'timeline', data: itemsJson }),
    };
    set(state => ({ messages: [...state.messages, msg] }));
  },

  addProgressBar(percent: number, type: string) {
    const msg: UiMessage = {
      ts: uniqueTs(),
      type: 'SAY',
      say: 'TEXT',
      text: JSON.stringify({ type: 'progressBar', percent, barType: type }),
    };
    set(state => ({ messages: [...state.messages, msg] }));
  },

  addJiraCard(cardJson: string) {
    const msg: UiMessage = {
      ts: uniqueTs(),
      type: 'SAY',
      say: 'TEXT',
      text: JSON.stringify({ type: 'jiraCard', data: cardJson }),
    };
    set(state => ({ messages: [...state.messages, msg] }));
  },

  addSonarBadge(badgeJson: string) {
    const msg: UiMessage = {
      ts: uniqueTs(),
      type: 'SAY',
      say: 'TEXT',
      text: JSON.stringify({ type: 'sonarBadge', data: badgeJson }),
    };
    set(state => ({ messages: [...state.messages, msg] }));
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

  receiveMentionResults(results: MentionSearchResult[]) {
    set({ mentionResults: results });
  },

  showApproval(toolName: string, riskLevel: string, description?: string, metadata?: Array<{ key: string; value: string }>, diffContent?: string, commandPreview?: ApprovalCommandPreview, allowSessionApproval: boolean = true) {
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
      };

      // Drain any active tool chain into individual UiMessage entries and
      // clear the streaming caret so the approval card renders below all
      // prior content.
      const hadStream = state.activeStream != null;
      const tools = Array.from(state.activeToolCalls.values());
      const newMessages = [...state.messages];

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
            },
          });
        }
      }

      return {
        pendingApproval: approval,
        ...(hadStream ? { activeStream: null } : {}),
        ...(tools.length > 0 ? { activeToolCalls: new Map(), toolOutputStreams: {} } : {}),
        ...(newMessages.length !== state.messages.length ? { messages: newMessages } : {}),
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

  sendMessage(text: string, mentions: Mention[]) {
    // Do NOT add the user message here — Kotlin is authoritative.
    // For first messages: startSession() adds it atomically.
    // For subsequent messages: appendUserMessage() adds it from Kotlin.
    // Clear the retry pill immediately so it doesn't persist into the new turn.
    set({ retryState: null });
    import('../bridge/jcef-bridge').then(({ kotlinBridge }) => {
      if (mentions.length > 0) {
        kotlinBridge.sendMessageWithMentions(text, JSON.stringify(mentions));
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

  // ── Edit Stats + Checkpoint Actions ──
  updateEditStats(stats: EditStats) {
    set({ editStats: stats });
  },

  updateCheckpoints(checkpoints: CheckpointInfo[]) {
    set({ checkpoints });
  },

  applyRollback(rollback: RollbackInfo) {
    set((state) => {
      const rolledBackIds = new Set(rollback.rolledBackEntryIds);
      const rolledBackFiles = new Set(rollback.affectedFiles);

      const messages = state.messages.map((msg) => {
        // Mark tool messages whose toolCallId matches a rolled-back entry
        if (msg.toolCallData) {
          const tcId = msg.toolCallData.toolCallId;
          if (rolledBackIds.has(tcId)) {
            return { ...msg, toolCallData: { ...msg.toolCallData, isError: true } };
          }
          // Also check by file path in args
          try {
            const parsed = JSON.parse(msg.toolCallData.args || '{}') as Record<string, unknown>;
            const filePath = parsed.file_path || parsed.path;
            if (typeof filePath === 'string' && rolledBackFiles.has(filePath)) {
              return { ...msg, toolCallData: { ...msg.toolCallData, isError: true } };
            }
          } catch { /* not JSON, skip */ }
        }
        return msg;
      });

      return {
        messages,
        rollbackEvents: [...state.rollbackEvents, rollback],
      };
    });
  },

  setSmartWorkingPhrase(phrase: string) {
    set({ smartWorkingPhrase: phrase });
  },

  setSessionTitle(title: string) {
    set({ sessionTitle: title });
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
    // Promote only the specific messages that were drained by the agent
    set((state) => {
      const idSet = new Set(ids);
      const toPromote = state.queuedSteeringMessages.filter(m => idSet.has(m.id));
      const remaining = state.queuedSteeringMessages.filter(m => !idSet.has(m.id));
      const newMessages: UiMessage[] = toPromote.map(m => ({
        ts: m.timestamp,
        type: 'SAY' as const,
        say: 'USER_MESSAGE' as const,
        text: m.text,
      }));
      return {
        messages: [...state.messages, ...newMessages],
        queuedSteeringMessages: remaining,
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
        messages: [...state.messages, msg],
      }));
    } catch (e) {
      console.warn('[chatStore] addArtifact: malformed payload', e);
    }
  },

  // ── Sub-Agent Actions ──
  // Live sub-agent state is tracked in activeSubAgents (like activeToolCalls).
  // Internal messages and tool chains accumulate there while running.
  // On completion/kill, the state is frozen into the UiMessage and removed from the map.

  spawnSubAgent(payload: string) {
    const data = JSON.parse(payload);

    set((state) => {
      // Dedupe on agentId
      if (state.activeSubAgents.has(data.agentId) ||
          state.messages.some((m) => m.subagentData?.agentId === data.agentId)) {
        return state;
      }

      const agentState: SubAgentState = {
        agentId: data.agentId,
        label: data.label || 'general-purpose',
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
        subagentData: {
          agentId: data.agentId,
          agentType: data.label || 'general-purpose',
          description: data.label,
          status: 'RUNNING',
          iterations: 1,
        },
      };

      const newMap = new Map(state.activeSubAgents);
      newMap.set(data.agentId, agentState);

      return {
        messages: [...state.messages, msg],
        activeSubAgents: newMap,
      };
    });
  },

  updateSubAgentIteration(payload: string) {
    const data = JSON.parse(payload);
    set((state) => {
      // Update live state
      const newMap = new Map(state.activeSubAgents);
      const agent = newMap.get(data.agentId);
      if (agent) {
        newMap.set(data.agentId, {
          ...agent,
          iteration: data.iteration || (agent.iteration + 1),
          tokensUsed: data.tokensUsed ?? agent.tokensUsed,
        });
      }
      // Also update the UiMessage for persistence
      const messages = state.messages.map(m =>
        m.subagentData?.agentId === data.agentId
          ? { ...m, subagentData: { ...m.subagentData!, iterations: data.iteration || (m.subagentData!.iterations + 1) } }
          : m
      );
      return { messages, activeSubAgents: newMap };
    });
  },

  addSubAgentToolCall(payload: string) {
    const data = JSON.parse(payload);
    set((state) => {
      const newMap = new Map(state.activeSubAgents);
      const agent = newMap.get(data.agentId);
      if (agent) {
        const tc: ToolCall = {
          id: data.toolCallId || `sa-tc-${uniqueTs()}`,
          name: data.toolName || 'unknown',
          args: data.args || '',
          status: 'RUNNING',
        };
        newMap.set(data.agentId, {
          ...agent,
          activeToolChain: [...agent.activeToolChain, tc],
        });
      }
      // Update UiMessage say to PROGRESS
      const messages = state.messages.map(m =>
        m.subagentData?.agentId === data.agentId
          ? { ...m, say: 'SUBAGENT_PROGRESS' as const }
          : m
      );
      return { messages, activeSubAgents: newMap };
    });
  },

  updateSubAgentToolCall(payload: string) {
    const data = JSON.parse(payload);
    set((state) => {
      const newMap = new Map(state.activeSubAgents);
      const agent = newMap.get(data.agentId);
      if (agent) {
        const status: ToolCallStatus = data.isError ? 'ERROR' : 'COMPLETED';
        // Finalize the tool: move from activeToolChain to messages
        const matchIdx = agent.activeToolChain.findIndex(tc => tc.name === data.toolName && tc.status === 'RUNNING');
        const finalized = matchIdx >= 0 ? agent.activeToolChain[matchIdx]! : null;
        const remaining = matchIdx >= 0
          ? [...agent.activeToolChain.slice(0, matchIdx), ...agent.activeToolChain.slice(matchIdx + 1)]
          : agent.activeToolChain;

        const toolMsg: UiMessage = {
          ts: uniqueTs(),
          type: 'SAY',
          say: 'TOOL',
          toolCallData: {
            toolCallId: finalized?.id || data.toolCallId || `sa-tc-${uniqueTs()}`,
            toolName: data.toolName || 'unknown',
            args: finalized?.args || '',
            status,
            result: data.result || '',
            durationMs: data.durationMs || 0,
          },
        };

        newMap.set(data.agentId, {
          ...agent,
          activeToolChain: remaining,
          messages: [...agent.messages, toolMsg],
        });
      }
      return { activeSubAgents: newMap };
    });
  },

  updateSubAgentMessage(payload: string) {
    const data = JSON.parse(payload);
    set((state) => {
      const newMap = new Map(state.activeSubAgents);
      const agent = newMap.get(data.agentId);
      if (agent) {
        const textMsg: UiMessage = {
          ts: uniqueTs(),
          type: 'SAY',
          say: 'TEXT',
          text: data.textContent || '',
        };
        newMap.set(data.agentId, {
          ...agent,
          messages: [...agent.messages, textMsg],
        });
      }
      // Update UiMessage text for persistence
      const messages = state.messages.map(m =>
        m.subagentData?.agentId === data.agentId
          ? { ...m, say: 'SUBAGENT_PROGRESS' as const, text: data.textContent || '' }
          : m
      );
      return { messages, activeSubAgents: newMap };
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
      const newMap = new Map(state.activeSubAgents);
      const agent = newMap.get(agentId);
      if (!agent) return state;
      const msgs = agent.messages;
      const lastIdx = msgs.length - 1;
      const last = lastIdx >= 0 ? msgs[lastIdx] : undefined;
      if (last && last.type === 'SAY' && last.say === 'TEXT') {
        const updated: UiMessage = { ...last, text: (last.text ?? '') + delta };
        const nextMessages = msgs.slice(0, lastIdx).concat(updated);
        newMap.set(agentId, { ...agent, messages: nextMessages });
      } else {
        const textMsg: UiMessage = {
          ts: uniqueTs(),
          type: 'SAY',
          say: 'TEXT',
          text: delta,
        };
        newMap.set(agentId, { ...agent, messages: [...msgs, textMsg] });
      }
      return { activeSubAgents: newMap };
    });
  },

  completeSubAgent(payload: string) {
    const data = JSON.parse(payload);
    set((state) => {
      // Mark as completed in the map (keep entry so SubAgentView retains internal messages)
      const newMap = new Map(state.activeSubAgents);
      const agent = newMap.get(data.agentId);
      if (agent) {
        newMap.set(data.agentId, {
          ...agent,
          status: data.isError ? 'ERROR' : 'COMPLETED',
          summary: data.textContent || '',
          tokensUsed: data.tokensUsed ?? agent.tokensUsed,
          activeToolChain: [], // clear any in-flight tools
        });
      }

      // Freeze into the UiMessage for persistence
      const messages = state.messages.map(m => {
        if (m.subagentData?.agentId === data.agentId) {
          return {
            ...m,
            say: 'SUBAGENT_COMPLETED' as const,
            subagentData: {
              ...m.subagentData!,
              status: data.isError ? 'FAILED' : 'COMPLETED',
              summary: data.textContent || '',
            },
          };
        }
        return m;
      });
      return { messages, activeSubAgents: newMap };
    });
  },

  killSubAgent(agentId: string) {
    set((state) => {
      // Mark as killed in map (keep entry for SubAgentView to show history)
      const newMap = new Map(state.activeSubAgents);
      const agent = newMap.get(agentId);
      if (agent) {
        newMap.set(agentId, { ...agent, status: 'KILLED', activeToolChain: [] });
      }

      const messages = state.messages.map(m =>
        m.subagentData?.agentId === agentId
          ? { ...m, say: 'SUBAGENT_COMPLETED' as const, subagentData: { ...m.subagentData!, status: 'KILLED' } }
          : m
      );
      return { messages, activeSubAgents: newMap };
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

    // Restore plan from last PLAN_UPDATE message (steps field removed in Phase 5 — task system port)
    let restoredPlan: Plan | null = null;
    for (let i = upgraded.length - 1; i >= 0; i--) {
      const m = upgraded[i]!;
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
      messages: upgraded,
      activeStream: null,
      viewMode: 'chat',
      ...(restoredPlan ? { plan: restoredPlan } : {}),
    });
  },

  // ── History View ──
  setViewMode(mode: 'history' | 'chat') {
    set({ viewMode: mode });
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
  setTasks: (tasks) => set({ tasks }),
  applyTaskCreate: (task) => set((state) => {
    const existing = state.tasks.findIndex((t) => t.id === task.id);
    if (existing >= 0) {
      // Upsert: duplicate create (e.g. session resume replay) replaces in-place
      const updated = [...state.tasks];
      updated[existing] = task;
      return { tasks: updated };
    }
    return { tasks: [...state.tasks, task] };
  }),
  applyTaskUpdate: (task) => set((state) => {
    const existing = state.tasks.findIndex((t) => t.id === task.id);
    if (existing >= 0) {
      const updated = [...state.tasks];
      updated[existing] = task;
      return { tasks: updated };
    }
    // Upsert: if the create event was missed (out-of-order delivery), fall through to append
    return { tasks: [...state.tasks, task] };
  }),
}));
