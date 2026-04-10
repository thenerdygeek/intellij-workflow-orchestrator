import { create } from 'zustand';
import type {
  Message,
  MessageRole,
  ToolCall,
  ToolCallStatus,
  Plan,
  PlanStep,
  PlanStepStatus,
  Question,
  SessionInfo,
  SessionStatus,
  Mention,
  MentionSearchResult,
  StatusType,
  EditDiff,
  Skill,
  ToastType,
  SubAgentState,
  EditStats,
  CheckpointInfo,
  RollbackInfo,
} from '../bridge/types';

// ── Internal ID generator ──
let _idCounter = 0;
function nextId(prefix: string = 'msg'): string {
  return `${prefix}-${Date.now()}-${++_idCounter}`;
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
interface PendingApproval {
  toolName: string;
  riskLevel: string;
  title: string;
  description?: string;
  metadata?: Array<{ key: string; value: string }>;
  diffContent?: string;
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
  messages: Message[];
  activeStream: { text: string; isStreaming: boolean } | null;
  activeToolCalls: Map<string, ToolCall>;  // key = unique tool call ID
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
  retryMessage: string | null;
  toasts: Toast[];
  skillBanner: string | null;
  skillsList: Skill[];
  tokenBudget: { used: number; max: number };
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

  // Actions
  startSession(task: string, mentions?: Mention[]): void;
  completeSession(info: SessionInfo): void;
  addMessage(role: 'user' | 'agent', content: string, mentions?: Mention[]): void;
  appendToken(token: string): void;
  endStream(): void;
  addToolCall(toolCallId: string, name: string, args: string, status: ToolCallStatus): void;
  updateToolCall(name: string, status: ToolCallStatus, result: string, durationMs: number, output?: string, diff?: string): void;
  finalizeToolChain(): void;
  addDiff(diff: EditDiff): void;
  addDiffExplanation(title: string, diffSource: string): void;
  addCompletionSummary(result: string, verifyCommand?: string): void;
  addStatus(message: string, type: StatusType): void;
  addThinking(text: string): void;
  clearChat(): void;
  setPlan(plan: Plan): void;
  approvePlan(): void;
  updatePlanStep(stepId: string, status: string): void;
  replaceExecutionSteps(steps: PlanStep[]): void;
  setPlanPending(state: 'approve' | 'revise' | null): void;
  setPlanCommentCount(count: number): void;
  showQuestions(questions: Question[]): void;
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
  updateSkillsList(skills: Skill[]): void;
  showSkillBanner(name: string): void;
  hideSkillBanner(): void;
  showToolsPanel(toolsJson: string): void;
  hideToolsPanel(): void;
  showRetryButton(lastMessage: string): void;
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
  showApproval(toolName: string, riskLevel: string, description?: string, metadata?: Array<{ key: string; value: string }>, diffContent?: string): void;
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

  // Artifact Actions
  addArtifact(payload: string): void;

  // Sub-Agent Actions
  spawnSubAgent(payload: string): void;
  updateSubAgentIteration(payload: string): void;
  addSubAgentToolCall(payload: string): void;
  updateSubAgentToolCall(payload: string): void;
  updateSubAgentMessage(payload: string): void;
  completeSubAgent(payload: string): void;
  killSubAgent(agentId: string): void;
}

export const useChatStore = create<ChatState>((set, get) => ({
  // Initial state
  messages: [],
  activeStream: null,
  activeToolCalls: new Map(),
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
  retryMessage: null,
  toasts: [],
  skillBanner: null,
  skillsList: [],
  tokenBudget: { used: 0, max: 0 },
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

  // Actions
  startSession(task: string, mentions?: Mention[]) {
    const firstMessage: Message = {
      id: nextId('msg'),
      role: 'user',
      content: task,
      timestamp: Date.now(),
      ...(mentions && mentions.length > 0 ? { mentions } : {}),
    };
    set({
      messages: [firstMessage],
      activeStream: null,
      activeToolCalls: new Map(),
      plan: null,
      planCompletedPendingClear: false,
      questions: null,
      questionSummary: null,
      busy: true,
      steeringMode: true,
      retryMessage: null,
      smartWorkingPhrase: null,
      sessionTitle: null,
      editStats: null,
      checkpoints: [],
      queuedSteeringMessages: [],
      restoredInputText: null,
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
    // Finalize any remaining active tool calls as a toolchain message
    const state = get();
    const remaining = Array.from(state.activeToolCalls.values());
    const messages = remaining.length > 0
      ? [...state.messages, { id: nextId('tc-chain'), role: 'system' as const, content: '', timestamp: Date.now(), toolChain: remaining }]
      : state.messages;
    set({
      session: info,
      busy: false,
      steeringMode: false,
      activeStream: null,
      activeToolCalls: new Map(),
      messages,
      queuedSteeringMessages: [],
      // Clear completed plan on session end (no more messages will arrive to trigger deferred clear)
      ...(state.planCompletedPendingClear ? { plan: null, planCompletedPendingClear: false } : {}),
    });
  },

  addMessage(role: 'user' | 'agent', content: string, mentions?: Mention[]) {
    const message: Message = {
      id: nextId('msg'),
      role,
      content,
      timestamp: Date.now(),
      ...(mentions && mentions.length > 0 ? { mentions } : {}),
    };
    set(state => ({
      messages: [...state.messages, message],
    }));
  },

  appendToken(token: string) {
    set(state => {
      const isFirstToken = state.activeStream == null;
      const stream = state.activeStream ?? { text: '', isStreaming: true };

      // Auto-finalize the tool chain when the first token of a new text response arrives.
      // Inject as a toolchain message so it renders in the correct position.
      if (isFirstToken && state.activeToolCalls.size > 0) {
        const tools = Array.from(state.activeToolCalls.values());
        const chainMsg: Message = { id: nextId('tc-chain'), role: 'system', content: '', timestamp: Date.now(), toolChain: tools };
        return {
          activeStream: { text: token, isStreaming: true },
          messages: [...state.messages, chainMsg],
          activeToolCalls: new Map(),
        };
      }

      return {
        activeStream: {
          text: stream.text + token,
          isStreaming: true,
        },
      };
    });
  },

  endStream() {
    const state = get();
    const stream = state.activeStream;
    const shouldClearPlan = state.planCompletedPendingClear;

    if (stream && stream.text.length > 0) {
      const message: Message = {
        id: nextId('msg'),
        role: 'agent',
        content: stream.text,
        timestamp: Date.now(),
      };
      set({
        messages: [...state.messages, message],
        activeStream: null,
        ...(shouldClearPlan ? { plan: null, planCompletedPendingClear: false } : {}),
      });
    } else {
      set({
        activeStream: null,
        ...(shouldClearPlan ? { plan: null, planCompletedPendingClear: false } : {}),
      });
    }
  },

  addToolCall(toolCallId: string, name: string, args: string, status: ToolCallStatus) {
    set(state => {
      // Use the LLM-assigned tool call ID so streaming output (keyed by the same ID) resolves correctly.
      // Fall back to a generated ID for legacy callers that don't supply one.
      const id = toolCallId || nextId('tc');
      const newMap = new Map(state.activeToolCalls);
      newMap.set(id, { id, name, args, status });

      // Auto-finalize any active stream — ensures text appears in messages before tool calls.
      // Without this, text stays in activeStream (rendered at the bottom) while tool calls
      // render above it, causing text to "accumulate at the bottom."
      const stream = state.activeStream;
      if (stream && stream.text.length > 0) {
        const message: Message = { id: nextId('msg'), role: 'agent', content: stream.text, timestamp: Date.now() };
        return {
          messages: [...state.messages, message],
          activeStream: null,
          activeToolCalls: newMap,
        };
      }

      return { activeToolCalls: newMap };
    });
  },

  updateToolCall(name: string, status: ToolCallStatus, result: string, durationMs: number, output?: string, diff?: string) {
    set(state => {
      const newMap = new Map(state.activeToolCalls);
      // Find the first RUNNING tool call with this name (for parallel calls,
      // results arrive in order, so the first RUNNING one is the correct target)
      let targetKey: string | null = null;
      for (const [key, tc] of newMap) {
        if (tc.name === name && tc.status === 'RUNNING') {
          targetKey = key;
          break;  // first RUNNING match, not last
        }
      }
      // Fallback: if no RUNNING match, find any match with this name (last one)
      if (!targetKey) {
        for (const [key, tc] of newMap) {
          if (tc.name === name) targetKey = key;
        }
      }
      if (targetKey) {
        const existing = newMap.get(targetKey)!;
        newMap.set(targetKey, { ...existing, status, result, output, durationMs, ...(diff ? { diff } : {}) });
      } else {
        const id = nextId('tc');
        newMap.set(id, { id, name, args: '', status, result, output, durationMs, ...(diff ? { diff } : {}) });
      }
      return { activeToolCalls: newMap };
    });
  },

  addDiff(diff: EditDiff) {
    const message: Message = {
      id: nextId('diff'),
      role: 'system',
      content: JSON.stringify(diff),
      timestamp: Date.now(),
    };
    set(state => ({ messages: [...state.messages, message] }));
  },

  addDiffExplanation(title: string, diffSource: string) {
    const message: Message = {
      id: nextId('diff-exp'),
      role: 'system',
      content: JSON.stringify({ type: 'diff-explanation', title, diffSource }),
      timestamp: Date.now(),
    };
    set(state => ({ messages: [...state.messages, message] }));
  },

  addCompletionSummary(result: string, verifyCommand?: string) {
    const completionMessage: Message = {
      id: nextId('completion'),
      role: 'system',
      content: JSON.stringify({ type: 'completion', result, verifyCommand }),
      timestamp: Date.now(),
    };
    set(state => ({
      messages: [...state.messages, completionMessage],
      activeStream: null,
    }));
  },

  addStatus(message: string, type: StatusType) {
    const statusMessage: Message = {
      id: nextId('status'),
      role: 'system',
      content: JSON.stringify({ type: 'status', message, statusType: type }),
      timestamp: Date.now(),
    };
    set(state => ({ messages: [...state.messages, statusMessage] }));
  },

  addThinking(text: string) {
    const thinkingMessage: Message = {
      id: nextId('thinking'),
      role: 'system',
      content: JSON.stringify({ type: 'thinking', text }),
      timestamp: Date.now(),
    };
    set(state => ({ messages: [...state.messages, thinkingMessage] }));
  },

  finalizeToolChain() {
    // Move current active tool calls into messages as a toolchain entry.
    // Before clearing toolOutputStreams, merge any accumulated stream output
    // into each tool call's `output` field so it survives finalization.
    const state = get();
    const tools = Array.from(state.activeToolCalls.values());
    if (tools.length === 0) return;
    const streams = state.toolOutputStreams;
    const mergedTools = tools.map(tc => {
      const stream = streams[tc.id];
      if (stream && !tc.output) {
        return { ...tc, output: stream };
      }
      return tc;
    });
    const chainMsg: Message = { id: nextId('tc-chain'), role: 'system', content: '', timestamp: Date.now(), toolChain: mergedTools };
    set({
      messages: [...state.messages, chainMsg],
      activeToolCalls: new Map(),
      toolOutputStreams: {},
    });
  },

  clearChat() {
    set({
      messages: [],
      activeStream: null,
      activeToolCalls: new Map(),
      plan: null,
      planCompletedPendingClear: false,
      questions: null,
      questionSummary: null,
      retryMessage: null,
    });
  },

  setPlan(plan: Plan) {
    set({ plan, planCommentCount: 0 });
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

  updatePlanStep(stepId: string, status: string) {
    set(state => {
      if (!state.plan) return {};
      const steps = state.plan.steps.map(step =>
        step.id === stepId ? { ...step, status: status as PlanStepStatus } : step
      );
      const terminalStatuses = new Set(['completed', 'done', 'failed', 'skipped']);
      const allTerminal = steps.length > 0 && steps.every(s => terminalStatuses.has(s.status));
      return {
        plan: { ...state.plan, steps },
        planCompletedPendingClear: allTerminal,
      };
    });
  },

  replaceExecutionSteps(steps: PlanStep[]) {
    set(state => {
      if (!state.plan) return {};
      const terminalStatuses = new Set(['completed', 'done', 'failed', 'skipped']);
      const allTerminal = steps.length > 0 && steps.every(s => terminalStatuses.has(s.status));
      return {
        plan: { ...state.plan, steps },
        planCompletedPendingClear: allTerminal,
      };
    });
  },

  setPlanPending(state: 'approve' | 'revise' | null) {
    set({ planPending: state });
  },

  showQuestions(questions: Question[]) {
    set({ questions, activeQuestionIndex: 0, questionSummary: null });
  },

  finalizeQuestionsAsMessage() {
    set(state => {
      const snapshot = state.questions;
      if (!snapshot || snapshot.length === 0) {
        return { questions: null, questionSummary: null, activeQuestionIndex: 0 };
      }
      const message: Message = {
        id: nextId('msg'),
        role: 'user',
        content: '',
        timestamp: Date.now(),
        answeredQuestions: snapshot,
      };
      return {
        messages: [...state.messages, message],
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

  showRetryButton(lastMessage: string) {
    set({ retryMessage: lastMessage });
  },

  focusInput() {
    set(state => ({ focusInputTrigger: state.focusInputTrigger + 1 }));
  },

  addChart(chartConfigJson: string) {
    const message: Message = {
      id: nextId('chart'),
      role: 'system',
      content: JSON.stringify({ type: 'chart', config: chartConfigJson }),
      timestamp: Date.now(),
    };
    set(state => ({ messages: [...state.messages, message] }));
  },

  addAnsiOutput(text: string) {
    const message: Message = {
      id: nextId('ansi'),
      role: 'system',
      content: JSON.stringify({ type: 'ansi', text }),
      timestamp: Date.now(),
    };
    set(state => ({ messages: [...state.messages, message] }));
  },

  addTabs(tabsJson: string) {
    const message: Message = {
      id: nextId('tabs'),
      role: 'system',
      content: JSON.stringify({ type: 'tabs', data: tabsJson }),
      timestamp: Date.now(),
    };
    set(state => ({ messages: [...state.messages, message] }));
  },

  addTimeline(itemsJson: string) {
    const message: Message = {
      id: nextId('timeline'),
      role: 'system',
      content: JSON.stringify({ type: 'timeline', data: itemsJson }),
      timestamp: Date.now(),
    };
    set(state => ({ messages: [...state.messages, message] }));
  },

  addProgressBar(percent: number, type: string) {
    const message: Message = {
      id: nextId('progress'),
      role: 'system',
      content: JSON.stringify({ type: 'progressBar', percent, barType: type }),
      timestamp: Date.now(),
    };
    set(state => ({ messages: [...state.messages, message] }));
  },

  addJiraCard(cardJson: string) {
    const message: Message = {
      id: nextId('jira'),
      role: 'system',
      content: JSON.stringify({ type: 'jiraCard', data: cardJson }),
      timestamp: Date.now(),
    };
    set(state => ({ messages: [...state.messages, message] }));
  },

  addSonarBadge(badgeJson: string) {
    const message: Message = {
      id: nextId('sonar'),
      role: 'system',
      content: JSON.stringify({ type: 'sonarBadge', data: badgeJson }),
      timestamp: Date.now(),
    };
    set(state => ({ messages: [...state.messages, message] }));
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

  showApproval(toolName: string, riskLevel: string, description?: string, metadata?: Array<{ key: string; value: string }>, diffContent?: string) {
    set(state => {
      const approval: PendingApproval = {
        toolName,
        riskLevel,
        title: `Approve ${toolName}? (${riskLevel} risk)`,
        description,
        metadata,
        diffContent,
      };

      // Auto-finalize stream + tool chain so approval card appears after all prior content.
      const stream = state.activeStream;
      const tools = Array.from(state.activeToolCalls.values());
      const newMessages = [...state.messages];

      if (stream && stream.text.length > 0) {
        newMessages.push({ id: nextId('msg'), role: 'agent', content: stream.text, timestamp: Date.now() });
      }
      if (tools.length > 0) {
        newMessages.push({ id: nextId('tc-chain'), role: 'system', content: '', timestamp: Date.now(), toolChain: tools });
      }

      return {
        pendingApproval: approval,
        ...(stream && stream.text.length > 0 ? { activeStream: null } : {}),
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
      const rolledBackFiles = new Set(rollback.affectedFiles);

      const messages = state.messages.map((msg) => {
        // Mark messages that have a filePath matching a rolled-back file
        let msgRolledBack = msg.rolledBack;
        if ('filePath' in msg && msg.filePath && rolledBackFiles.has(msg.filePath)) {
          msgRolledBack = true;
        }

        // Mark tool calls within tool chains that reference rolled-back files
        let toolChain = msg.toolChain;
        if (toolChain && toolChain.length > 0) {
          toolChain = toolChain.map((tc) => {
            try {
              const parsed = JSON.parse(tc.args) as Record<string, unknown>;
              const filePath = parsed.file_path || parsed.path;
              if (typeof filePath === 'string' && rolledBackFiles.has(filePath)) {
                return { ...tc, rolledBack: true };
              }
            } catch { /* not JSON, skip */ }
            return tc;
          });
        }

        if (msgRolledBack !== msg.rolledBack || toolChain !== msg.toolChain) {
          return { ...msg, rolledBack: msgRolledBack, toolChain };
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
      const newMessages = toPromote.map(m => ({
        id: nextId('msg'),
        role: 'user' as const,
        content: m.text,
        timestamp: m.timestamp,
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
      const { title, source } = JSON.parse(payload);
      const msgId = nextId('artifact');
      set((state) => ({
        messages: [...state.messages, {
          id: msgId,
          role: 'system' as MessageRole,
          content: `artifact:${title}`,
          timestamp: Date.now(),
          artifact: { title, source },
        }],
      }));
    } catch (e) {
      console.warn('[chatStore] addArtifact: malformed payload', e);
    }
  },

  // ── Sub-Agent Actions ──
  spawnSubAgent(payload: string) {
    const data = JSON.parse(payload);

    set((state) => {
      // Dedupe on agentId. Defensive guard against any future Kotlin-side
      // regression that re-fires status="running" — without this, every
      // spurious spawn event renders a new card (the original 77-card bug).
      if (state.messages.some((m) => m.subAgent?.agentId === data.agentId)) {
        return state;
      }

      const subAgentState: SubAgentState = {
        agentId: data.agentId,
        label: data.label,
        status: 'RUNNING',
        iteration: 1,
        tokensUsed: 0,
        messages: [],
        activeToolChain: [],
        startedAt: Date.now(),
      };

      return {
        messages: [
          ...state.messages,
          {
            id: nextId('subagent'),
            role: 'system',
            content: 'subagent', // sentinel to trigger SubAgentView
            timestamp: Date.now(),
            subAgent: subAgentState,
          },
        ],
      };
    });
  },

  updateSubAgentIteration(payload: string) {
    const data = JSON.parse(payload);
    set((state) => {
      const messages = [...state.messages];
      const idx = messages.findIndex(m => m.subAgent?.agentId === data.agentId);
      const msg = messages[idx];
      if (msg && msg.subAgent) {
        messages[idx] = {
          ...msg,
          subAgent: {
            ...msg.subAgent,
            iteration: data.iteration || msg.subAgent.iteration + 1
          }
        };
      }
      return { messages };
    });
  },

  addSubAgentToolCall(payload: string) {
    const data = JSON.parse(payload);
    set((state) => {
      const messages = [...state.messages];
      const idx = messages.findIndex(m => m.subAgent?.agentId === data.agentId);
      const msg = messages[idx];
      if (msg && msg.subAgent) {
        const subAgent = { ...msg.subAgent };
        const resolvedStatus: ToolCallStatus =
          data.status === 'COMPLETED' ? 'COMPLETED'
            : data.status === 'ERROR' ? 'ERROR'
              : 'RUNNING';
        const toolCall: ToolCall = {
          id: nextId('satool'),
          name: data.toolName || 'unknown',
          args: data.toolArgs || data.args || '{}',
          status: resolvedStatus,
          durationMs: data.durationMs,
          result: data.result,
        };
        subAgent.activeToolChain = [...(subAgent.activeToolChain || []), toolCall];
        messages[idx] = { ...msg, subAgent };
      }
      return { messages };
    });
  },

  updateSubAgentToolCall(payload: string) {
    const data = JSON.parse(payload);
    set((state) => {
      const messages = [...state.messages];
      const idx = messages.findIndex(m => m.subAgent?.agentId === data.agentId);
      const msg = messages[idx];
      if (msg && msg.subAgent) {
        const subAgent = { ...msg.subAgent };
        const toolChain = [...(subAgent.activeToolChain || [])];
        // Manual reverse search (ES2022 compat — no findLastIndex)
        let toolIdx = -1;
        for (let i = toolChain.length - 1; i >= 0; i--) {
          const item = toolChain[i];
          if (item && item.status === 'RUNNING' && item.name === data.toolName) {
            toolIdx = i;
            break;
          }
        }
        if (toolIdx !== -1) {
          const tc = toolChain[toolIdx]!;
          toolChain[toolIdx] = {
            id: tc.id,
            name: tc.name,
            args: tc.args,
            status: data.isError ? 'ERROR' as const : 'COMPLETED' as const,
            durationMs: data.toolDurationMs,
            result: data.toolResult || '',
            output: tc.output,
          };
          subAgent.activeToolChain = toolChain;
          messages[idx] = { ...msg, subAgent };
        }
      }
      return { messages };
    });
  },

  updateSubAgentMessage(payload: string) {
    const data = JSON.parse(payload);
    set((state) => {
      const messages = [...state.messages];
      const idx = messages.findIndex(m => m.subAgent?.agentId === data.agentId);
      const msg = messages[idx];
      if (msg && msg.subAgent) {
        const subAgent = { ...msg.subAgent };
        const newMsg: Message = {
          id: nextId('samsg'),
          role: 'agent',
          content: data.textContent || '',
          timestamp: Date.now()
        };
        if (subAgent.activeToolChain && subAgent.activeToolChain.length > 0) {
           newMsg.toolChain = [...subAgent.activeToolChain];
           subAgent.activeToolChain = [];
        }
        subAgent.messages = [...(subAgent.messages || []), newMsg];
        messages[idx] = { ...msg, subAgent };
      }
      return { messages };
    });
  },

  completeSubAgent(payload: string) {
    const data = JSON.parse(payload);
    set((state) => {
      const messages = [...state.messages];
      const idx = messages.findIndex(m => m.subAgent?.agentId === data.agentId);
      const msg = messages[idx];
      if (msg && msg.subAgent) {
        const subAgent = { ...msg.subAgent };
        subAgent.status = data.isError ? 'ERROR' : 'COMPLETED';
        subAgent.tokensUsed = data.tokensUsed || subAgent.tokensUsed || 0;
        subAgent.summary = data.textContent || '';
        
        if (subAgent.activeToolChain && subAgent.activeToolChain.length > 0) {
           subAgent.messages = [
             ...(subAgent.messages || []),
             {
               id: nextId('satc'),
               role: 'system',
               content: '',
               timestamp: Date.now(),
               toolChain: subAgent.activeToolChain
             }
           ];
           subAgent.activeToolChain = [];
        }

        messages[idx] = { ...msg, subAgent };
      }
      return { messages };
    });
  },

  killSubAgent(agentId: string) {
    set((state) => {
      const messages = [...state.messages];
      const idx = messages.findIndex(m => m.subAgent?.agentId === agentId);
      const msg = messages[idx];
      if (msg && msg.subAgent) {
        const subAgent = { ...msg.subAgent };
        subAgent.status = 'KILLED';
        // Flush any pending tool chain
        if (subAgent.activeToolChain && subAgent.activeToolChain.length > 0) {
          subAgent.messages = [
            ...(subAgent.messages || []),
            {
              id: nextId('satc'),
              role: 'system' as const,
              content: '',
              timestamp: Date.now(),
              toolChain: subAgent.activeToolChain
            }
          ];
          subAgent.activeToolChain = [];
        }
        messages[idx] = { ...msg, subAgent };
      }
      return { messages };
    });
    // Notify Kotlin to cancel the worker
    import('../bridge/jcef-bridge').then(({ kotlinBridge }) => {
      kotlinBridge.killSubAgent(agentId);
    });
  },
}));
