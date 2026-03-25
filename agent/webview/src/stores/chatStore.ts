import { create } from 'zustand';
import type {
  Message,
  ToolCall,
  ToolCallStatus,
  Plan,
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

// ── Approval state ──
interface PendingApproval {
  toolName: string;
  riskLevel: string;
  title: string;
  description?: string;
  metadata?: Array<{ key: string; value: string }>;
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
  questions: Question[] | null;
  activeQuestionIndex: number;
  questionSummary: any | null;
  session: SessionInfo;
  inputState: {
    locked: boolean;
    mentions: Mention[];
    model: string;
    mode: 'agent' | 'plan';
  };
  busy: boolean;
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
  focusInputTrigger: number;
  debugLogVisible: boolean;
  debugLogEntries: DebugLogEntry[];

  // Actions
  startSession(task: string): void;
  completeSession(info: SessionInfo): void;
  addMessage(role: 'user' | 'agent', content: string): void;
  appendToken(token: string): void;
  endStream(): void;
  addToolCall(name: string, args: string, status: ToolCallStatus): void;
  updateToolCall(name: string, status: ToolCallStatus, result: string, durationMs: number): void;
  finalizeToolChain(): void;
  addDiff(diff: EditDiff): void;
  addStatus(message: string, type: StatusType): void;
  addThinking(text: string): void;
  clearChat(): void;
  setPlan(plan: Plan): void;
  updatePlanStep(stepId: string, status: string): void;
  showQuestions(questions: Question[]): void;
  showQuestion(index: number): void;
  showQuestionSummary(summary: any): void;
  setInputLocked(locked: boolean): void;
  setInputMode(mode: 'agent' | 'plan'): void;
  setBusy(busy: boolean): void;
  setModelName(model: string): void;
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
  showApproval(toolName: string, riskLevel: string, description?: string, metadata?: Array<{ key: string; value: string }>): void;
  resolveApproval(decision: 'approve' | 'deny' | 'allowForSession'): void;
  sendMessage(text: string, mentions: Mention[]): void;
  setDebugLogVisible(visible: boolean): void;
  addDebugLogEntry(entry: DebugLogEntry): void;
  clearDebugLog(): void;
}

export const useChatStore = create<ChatState>((set, get) => ({
  // Initial state
  messages: [],
  activeStream: null,
  activeToolCalls: new Map(),
  plan: null,
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
    mode: 'agent',
  },
  busy: false,
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
  focusInputTrigger: 0,
  debugLogVisible: false,
  debugLogEntries: [],

  // Actions
  startSession(task: string) {
    const firstMessage: Message = {
      id: nextId('msg'),
      role: 'user',
      content: task,
      timestamp: Date.now(),
    };
    set({
      messages: [firstMessage],
      activeStream: null,
      activeToolCalls: new Map(),
      plan: null,
      questions: null,
      questionSummary: null,
      busy: true,
      retryMessage: null,
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
      activeStream: null,
      activeToolCalls: new Map(),
      messages,
    });
  },

  addMessage(role: 'user' | 'agent', content: string) {
    const message: Message = {
      id: nextId('msg'),
      role,
      content,
      timestamp: Date.now(),
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
      });
    } else {
      set({ activeStream: null });
    }
  },

  addToolCall(name: string, args: string, status: ToolCallStatus) {
    set(state => {
      const id = nextId('tc');
      const newMap = new Map(state.activeToolCalls);
      newMap.set(id, { id, name, args, status });
      return { activeToolCalls: newMap };
    });
  },

  updateToolCall(name: string, status: ToolCallStatus, result: string, durationMs: number) {
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
        newMap.set(targetKey, { ...existing, status, result, durationMs });
      } else {
        const id = nextId('tc');
        newMap.set(id, { id, name, args: '', status, result, durationMs });
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
    // Move current active tool calls into messages as a toolchain entry
    const state = get();
    const tools = Array.from(state.activeToolCalls.values());
    if (tools.length === 0) return;
    const chainMsg: Message = { id: nextId('tc-chain'), role: 'system', content: '', timestamp: Date.now(), toolChain: tools };
    set({
      messages: [...state.messages, chainMsg],
      activeToolCalls: new Map(),
    });
  },

  clearChat() {
    set({
      messages: [],
      activeStream: null,
      activeToolCalls: new Map(),
      plan: null,
      questions: null,
      questionSummary: null,
      retryMessage: null,
    });
  },

  setPlan(plan: Plan) {
    set({ plan });
  },

  updatePlanStep(stepId: string, status: string) {
    set(state => {
      if (!state.plan) return {};
      const steps = state.plan.steps.map(step =>
        step.id === stepId ? { ...step, status: status as PlanStepStatus } : step
      );
      return { plan: { ...state.plan, steps } };
    });
  },

  showQuestions(questions: Question[]) {
    set({ questions, activeQuestionIndex: 0, questionSummary: null });
  },

  showQuestion(index: number) {
    set({ activeQuestionIndex: index });
  },

  showQuestionSummary(summary: any) {
    set({ questionSummary: summary });
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
    set({ busy });
  },

  setModelName(model: string) {
    set(state => ({
      inputState: { ...state.inputState, model },
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

  showApproval(toolName: string, riskLevel: string, description?: string, metadata?: Array<{ key: string; value: string }>) {
    set({
      pendingApproval: {
        toolName,
        riskLevel,
        title: `Approve ${toolName}? (${riskLevel} risk)`,
        description,
        metadata,
      }
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
}));
