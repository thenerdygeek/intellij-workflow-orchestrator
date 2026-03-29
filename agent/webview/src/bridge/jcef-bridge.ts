import type {
  ToolCallStatus,
  StatusType,
  SessionStatus,
} from './types';

// Zustand store accessors — set by initBridge() after stores are created
type StoreAccessors = {
  getChatStore: () => any;
  getThemeStore: () => any;
  getSettingsStore: () => any;
};

let stores: StoreAccessors | null = null;

export function isJcefEnvironment(): boolean {
  return window.location.origin === 'http://workflow-agent';
}

// ═══ Kotlin → JS bridge functions (39 chat + 3 theme = 42 unique JS functions) ═══
// Note: Kotlin side has 44 methods but some are composites:
//   setText() = clearChat + appendToken + endStream
//   appendError() = appendStatus('ERROR')

const bridgeFunctions: Record<string, (...args: any[]) => void> = {
  startSession(task: string) {
    stores?.getChatStore().startSession(task);
  },
  appendUserMessage(text: string) {
    stores?.getChatStore().addMessage('user', text);
  },
  endStream() {
    stores?.getChatStore().endStream();
  },
  completeSession(status: string, tokensUsed: number, durationMs: number, iterations: number, filesModified: string[]) {
    stores?.getChatStore().completeSession({
      status: status as SessionStatus, tokensUsed, durationMs, iterations,
      filesModified: filesModified || [],
    });
  },
  appendToken(token: string) {
    stores?.getChatStore().appendToken(token);
  },
  appendToolCall(toolName: string, args: string, status: string) {
    stores?.getChatStore().addToolCall(toolName, args, status as ToolCallStatus);
  },
  updateToolResult(result: string, durationMs: number, toolName: string, status?: string, output?: string | null) {
    const resolvedStatus = (status === 'ERROR' ? 'ERROR' : 'COMPLETED') as ToolCallStatus;
    stores?.getChatStore().updateToolCall(toolName, resolvedStatus, result, durationMs, output ?? undefined);
  },
  finalizeToolChain() {
    stores?.getChatStore().finalizeToolChain();
  },
  appendDiff(filePath: string, oldLines: string[], newLines: string[], accepted: boolean | null) {
    stores?.getChatStore().addDiff({ filePath, oldLines, newLines, accepted });
  },
  appendCompletionSummary(result: string, verifyCommand?: string) {
    stores?.getChatStore().addCompletionSummary(result, verifyCommand ?? undefined);
  },
  appendStatus(message: string, type: string) {
    stores?.getChatStore().addStatus(message, type as StatusType);
  },
  appendThinking(text: string) {
    stores?.getChatStore().addThinking(text);
  },
  clearChat() {
    stores?.getChatStore().clearChat();
  },
  showToolsPanel(toolsJson: string) {
    stores?.getChatStore().showToolsPanel(toolsJson);
  },
  closeToolsPanel() {
    stores?.getChatStore().hideToolsPanel();
  },
  renderPlan(planJson: string) {
    const plan = JSON.parse(planJson);
    stores?.getChatStore().setPlan(plan);
  },
  updatePlanStep(stepId: string, status: string) {
    stores?.getChatStore().updatePlanStep(stepId, status);
  },
  showQuestions(questionsJson: string) {
    const questions = JSON.parse(questionsJson);
    stores?.getChatStore().showQuestions(questions);
  },
  showQuestion(index: number) {
    stores?.getChatStore().showQuestion(index);
  },
  showQuestionSummary(summaryJson: string) {
    const summary = JSON.parse(summaryJson);
    stores?.getChatStore().showQuestionSummary(summary);
  },
  enableChatInput() {
    stores?.getChatStore().setInputLocked(false);
  },
  setBusy(busy: boolean) {
    stores?.getChatStore().setBusy(busy);
  },
  setInputLocked(locked: boolean) {
    stores?.getChatStore().setInputLocked(locked);
  },
  updateTokenBudget(used: number, max: number) {
    stores?.getChatStore().updateTokenBudget(used, max);
  },
  setModelName(name: string) {
    stores?.getChatStore().setModelName(name);
  },
  setPlanMode(enabled: boolean) {
    stores?.getChatStore().setInputMode(enabled ? 'plan' : 'agent');
  },
  updateSkillsList(skillsJson: string) {
    const skills = JSON.parse(skillsJson);
    stores?.getChatStore().updateSkillsList(skills);
  },
  showRetryButton(lastMessage: string) {
    stores?.getChatStore().showRetryButton(lastMessage);
  },
  focusInput() {
    stores?.getChatStore().focusInput();
  },
  showSkillBanner(name: string) {
    stores?.getChatStore().showSkillBanner(name);
  },
  hideSkillBanner() {
    stores?.getChatStore().hideSkillBanner();
  },
  appendChart(chartConfigJson: string) {
    stores?.getChatStore().addChart(chartConfigJson);
  },
  updateChart(id: string, dataJson: string) {
    const registry = (window as any).__chartRegistry as Map<string, any> | undefined;
    const chart = registry?.get(id);
    if (chart && chart.canvas?.isConnected) {
      try {
        const update = JSON.parse(dataJson);
        if (update.data) {
          Object.assign(chart.data, update.data);
        }
        if (update.options) {
          Object.assign(chart.options, update.options);
        }
        chart.update('active');
      } catch { /* ignore malformed JSON */ }
    }
  },
  appendAnsiOutput(text: string) {
    stores?.getChatStore().addAnsiOutput(text);
  },
  showSkeleton() {
    stores?.getChatStore().showSkeleton();
  },
  hideSkeleton() {
    stores?.getChatStore().hideSkeleton();
  },
  showToast(message: string, type: string, durationMs: number) {
    stores?.getChatStore().showToast(message, type, durationMs);
  },
  appendTabs(tabsJson: string) {
    stores?.getChatStore().addTabs(tabsJson);
  },
  appendTimeline(itemsJson: string) {
    stores?.getChatStore().addTimeline(itemsJson);
  },
  appendProgressBar(percent: number, type: string) {
    stores?.getChatStore().addProgressBar(percent, type);
  },
  appendJiraCard(cardJson: string) {
    stores?.getChatStore().addJiraCard(cardJson);
  },
  appendSonarBadge(badgeJson: string) {
    stores?.getChatStore().addSonarBadge(badgeJson);
  },
  showApproval(toolName: string, riskLevel: string, description: string, metadataJson: string, diffContent?: string | null) {
    const metadata = metadataJson ? JSON.parse(metadataJson) : [];
    stores?.getChatStore().showApproval(toolName, riskLevel, description, metadata, diffContent ?? undefined);
  },
  showProcessInput(processId: string, description: string, prompt: string, command: string) {
    stores?.getChatStore().showProcessInput(processId, description, prompt, command);
  },
  receiveMentionResults(resultsJson: string) {
    try {
      const results = JSON.parse(resultsJson);
      stores?.getChatStore().receiveMentionResults(results);
    } catch (e) {
      console.error('[bridge] receiveMentionResults parse error:', e);
    }
  },
  applyTheme(vars: Record<string, string> | string) {
    stores?.getThemeStore().applyTheme(vars);
  },
  setPrismTheme(isDark: boolean) {
    stores?.getThemeStore().setIsDark(isDark);
  },
  setMermaidTheme(isDark: boolean) {
    stores?.getThemeStore().setIsDark(isDark);
  },
  applyVisualizationSettings(settingsJson: string) {
    try {
      const settings = JSON.parse(settingsJson);
      for (const [type, config] of Object.entries(settings)) {
        stores?.getSettingsStore().updateVisualization(type, config);
      }
    } catch (e) {
      console.error('Failed to apply visualization settings:', e);
    }
  },
  setDebugLogVisible(visible: boolean) {
    stores?.getChatStore().setDebugLogVisible(visible);
  },
  addDebugLogEntry(entryJson: string) {
    try {
      const entry = JSON.parse(entryJson);
      stores?.getChatStore().addDebugLogEntry(entry);
    } catch { /* ignore malformed JSON */ }
  },
  appendToolOutput(toolCallId: string, chunk: string) {
    stores?.getChatStore().appendToolOutput(toolCallId, chunk);
  },
};

// ═══ JS → Kotlin bridge wrappers (25 unique methods) ═══
// Note: Spec lists 26 but #26 (sendMessageWithMentionsQuery) = #25 (sendMessageWithMentions)

function callKotlin(fnName: string, ...args: any[]): void {
  const fn = (window as any)[fnName];
  if (typeof fn === 'function') {
    fn(...args);
  } else {
    console.log(`[bridge:dev] ${fnName}(${args.map(a => JSON.stringify(a)).join(', ')})`);
  }
}

export const kotlinBridge = {
  requestUndo(): void { callKotlin('_requestUndo'); },
  requestViewTrace(): void { callKotlin('_requestViewTrace'); },
  submitPrompt(text: string): void { callKotlin('_submitPrompt', text); },
  approvePlan(): void { callKotlin('_approvePlan'); },
  revisePlan(comments: string): void { callKotlin('_revisePlan', comments); },
  toggleTool(toolName: string, enabled: boolean): void { callKotlin('_toggleTool', `${toolName}:${enabled ? '1' : '0'}`); },
  questionAnswered(questionId: string, selectedOptionsJson: string): void { callKotlin('_questionAnswered', questionId, selectedOptionsJson); },
  questionSkipped(questionId: string): void { callKotlin('_questionSkipped', questionId); },
  chatAboutOption(questionId: string, optionLabel: string, message: string): void { callKotlin('_chatAboutOption', questionId, optionLabel, message); },
  questionsSubmitted(): void { callKotlin('_questionsSubmitted'); },
  questionsCancelled(): void { callKotlin('_questionsCancelled'); },
  editQuestion(questionId: string): void { callKotlin('_editQuestion', questionId); },
  approveToolCall(): void { callKotlin('_approveToolCall'); },
  denyToolCall(): void { callKotlin('_denyToolCall'); },
  allowToolForSession(toolName: string): void { callKotlin('_allowToolForSession', toolName); },
  deactivateSkill(): void { callKotlin('_deactivateSkill'); },
  navigateToFile(filePath: string, line?: number): void {
    const path = line && line > 0 ? `${filePath}:${line}` : filePath;
    callKotlin('_navigateToFile', path);
  },
  cancelTask(): void { callKotlin('_cancelTask'); },
  newChat(): void { callKotlin('_newChat'); },
  sendMessage(text: string): void { callKotlin('_sendMessage', text); },
  changeModel(modelId: string): void { callKotlin('_changeModel', modelId); },
  togglePlanMode(enabled: boolean): void { callKotlin('_togglePlanMode', enabled); },
  activateSkill(name: string): void { callKotlin('_activateSkill', name); },
  requestFocusIde(): void { callKotlin('_requestFocusIde'); },
  openSettings(): void { callKotlin('_openSettings'); },
  openToolsPanel(): void { callKotlin('_openToolsPanel'); },
  killToolCall(toolCallId: string): void { callKotlin('_killToolCall', toolCallId); },
  resolveProcessInput(input: string): void { callKotlin('_resolveProcessInput', input); },
  searchMentions(type: string, query: string): void { callKotlin('_searchMentions', `${type}:${query}`); },
  sendMessageWithMentions(text: string, mentionsJson: string): void {
    const payload = JSON.stringify({ text, mentions: JSON.parse(mentionsJson) });
    callKotlin('_sendMessageWithMentions', payload);
  },
};

// ═══ Editor Tab Popout ═══

export function openInEditorTab(type: string, content: string): void {
  const bridge = (window as any)._openInEditorTab;
  if (bridge) {
    bridge(JSON.stringify({ type, content }));
  } else {
    console.warn('[bridge] _openInEditorTab not available — running in browser mode');
  }
}

// ═══ Initialization ═══

export function initBridge(storeAccessors: StoreAccessors): void {
  stores = storeAccessors;
  const bridge: Record<string, (...args: any[]) => void> = {};
  for (const [name, fn] of Object.entries(bridgeFunctions)) {
    (window as any)[name] = fn;
    bridge[name] = fn;
  }
  (window as any).__bridge = bridge;
}
