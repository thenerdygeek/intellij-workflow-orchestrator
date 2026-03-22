import { create } from 'zustand';

// Minimal stub store — real implementation in Task 4
// Every method referenced by jcef-bridge.ts is a no-op here

interface ChatStoreStub {
  // State
  busy: boolean;
  inputLocked: boolean;

  // Methods (all no-ops for now)
  startSession: (task: string) => void;
  addMessage: (role: string, text: string) => void;
  endStream: () => void;
  completeSession: (info: any) => void;
  appendToken: (token: string) => void;
  addToolCall: (name: string, args: string, status: string) => void;
  updateToolCall: (name: string, status: string, result?: string, durationMs?: number) => void;
  addDiff: (diff: any) => void;
  addStatus: (message: string, type: string) => void;
  addThinking: (text: string) => void;
  clearChat: () => void;
  showToolsPanel: (toolsJson: string) => void;
  hideToolsPanel: () => void;
  setPlan: (plan: any) => void;
  updatePlanStep: (stepId: string, status: string) => void;
  showQuestions: (questions: any) => void;
  showQuestion: (index: number) => void;
  showQuestionSummary: (summary: any) => void;
  setInputLocked: (locked: boolean) => void;
  setBusy: (busy: boolean) => void;
  updateTokenBudget: (used: number, max: number) => void;
  setModelName: (name: string) => void;
  updateSkillsList: (skills: any) => void;
  showRetryButton: (lastMessage: string) => void;
  focusInput: () => void;
  showSkillBanner: (name: string) => void;
  hideSkillBanner: () => void;
  addChart: (chartConfigJson: string) => void;
  addAnsiOutput: (text: string) => void;
  showSkeleton: () => void;
  hideSkeleton: () => void;
  showToast: (message: string, type: string, durationMs: number) => void;
  addTabs: (tabsJson: string) => void;
  addTimeline: (itemsJson: string) => void;
  addProgressBar: (percent: number, type: string) => void;
  addJiraCard: (cardJson: string) => void;
  addSonarBadge: (badgeJson: string) => void;
  receiveMentionResults: (results: any) => void;
}

const noop = () => {};

export const useChatStore = create<ChatStoreStub>()((set) => ({
  busy: false,
  inputLocked: false,

  startSession: noop,
  addMessage: noop,
  endStream: noop,
  completeSession: noop,
  appendToken: noop,
  addToolCall: noop,
  updateToolCall: noop,
  addDiff: noop,
  addStatus: noop,
  addThinking: noop,
  clearChat: noop,
  showToolsPanel: noop,
  hideToolsPanel: noop,
  setPlan: noop,
  updatePlanStep: noop,
  showQuestions: noop,
  showQuestion: noop,
  showQuestionSummary: noop,
  setInputLocked: (locked: boolean) => set({ inputLocked: locked }),
  setBusy: (busy: boolean) => set({ busy }),
  updateTokenBudget: noop,
  setModelName: noop,
  updateSkillsList: noop,
  showRetryButton: noop,
  focusInput: noop,
  showSkillBanner: noop,
  hideSkillBanner: noop,
  addChart: noop,
  addAnsiOutput: noop,
  showSkeleton: noop,
  hideSkeleton: noop,
  showToast: noop,
  addTabs: noop,
  addTimeline: noop,
  addProgressBar: noop,
  addJiraCard: noop,
  addSonarBadge: noop,
  receiveMentionResults: noop,
}));
