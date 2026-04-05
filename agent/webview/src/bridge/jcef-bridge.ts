import type {
  ToolCallStatus,
  StatusType,
  SessionStatus,
} from './types';
import { preloadDiff2Html } from '../components/rich/DiffHtml';

// Zustand store accessors — set by initBridge() after stores are created
type StoreAccessors = {
  getChatStore: () => any;
  getThemeStore: () => any;
  getSettingsStore: () => any;
};

let stores: StoreAccessors | null = null;

// Buffer for calls that arrive before stores are initialized (race between
// Kotlin pendingCalls flush and React useEffect that calls initBridge).
// Kotlin's onLoadingStateChange fires BEFORE React's useEffect, so early
// calls like updateSkillsList would be lost without this buffer.
const pendingCalls: Array<{ name: string; args: any[] }> = [];

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
  appendToolCall(toolCallId: string, toolName: string, args: string, status: string) {
    stores?.getChatStore().addToolCall(toolCallId, toolName, args, status as ToolCallStatus);
  },
  updateToolResult(result: string, durationMs: number, toolName: string, status?: string, output?: string | null, diff?: string | null) {
    const resolvedStatus = (status === 'ERROR' ? 'ERROR' : 'COMPLETED') as ToolCallStatus;
    stores?.getChatStore().updateToolCall(toolName, resolvedStatus, result, durationMs, output ?? undefined, diff ?? undefined);
  },
  finalizeToolChain() {
    stores?.getChatStore().finalizeToolChain();
  },
  appendDiff(filePath: string, oldLines: string[], newLines: string[], accepted: boolean | null) {
    stores?.getChatStore().addDiff({ filePath, oldLines, newLines, accepted });
  },
  appendDiffExplanation(title: string, diffSource: string) {
    // Preload diff2html so it's ready when the component mounts
    preloadDiff2Html();
    stores?.getChatStore().addDiffExplanation(title, diffSource);
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
  approvePlan() {
    stores?.getChatStore().approvePlan();
  },
  updatePlanStep(stepId: string, status: string) {
    stores?.getChatStore().updatePlanStep(stepId, status);
  },
  setPlanPending(state: string) {
    const value = (state === 'approve' || state === 'revise') ? state : null;
    stores?.getChatStore().setPlanPending(value);
  },
  setPlanCommentCount(count: number) {
    stores?.getChatStore().setPlanCommentCount(count);
  },
  updatePlanSummary(summary: string) {
    stores?.getChatStore().updatePlanSummary(summary);
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
  setSteeringMode(enabled: boolean) {
    stores?.getChatStore().setSteeringMode(enabled);
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
  setRalphLoop(enabled: boolean) {
    stores?.getChatStore().setRalphLoop(enabled);
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
    // Preload diff2html immediately so the module is ready (or nearly ready) by the time
    // ApprovalView mounts and DiffHtml's useEffect fires — eliminates the loading skeleton.
    if (diffContent) preloadDiff2Html();
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

  // Edit stats + checkpoint methods from Kotlin
  updateEditStats(added: number, removed: number, files: number) {
    stores?.getChatStore().updateEditStats({ totalLinesAdded: added, totalLinesRemoved: removed, filesModified: files });
  },
  updateCheckpoints(json: string) {
    try {
      const checkpoints = JSON.parse(json);
      stores?.getChatStore().updateCheckpoints(checkpoints);
    } catch { /* ignore malformed JSON */ }
  },
  notifyRollback(json: string) {
    try {
      const rollback = JSON.parse(json);
      stores?.getChatStore().applyRollback(rollback);
    } catch { /* ignore malformed JSON */ }
  },
  setSmartWorkingPhrase(phrase: string) {
    stores?.getChatStore().setSmartWorkingPhrase(phrase);
  },
  setSessionTitle(title: string) {
    stores?.getChatStore().setSessionTitle(title);
  },

  // Queued steering message methods from Kotlin
  addQueuedSteeringMessage(id: string, text: string) {
    stores?.getChatStore().addQueuedSteeringMessage(id, text);
  },
  removeQueuedSteeringMessage(id: string) {
    stores?.getChatStore().removeQueuedSteeringMessage(id);
  },
  promoteQueuedSteeringMessages(ids: string[]) {
    stores?.getChatStore().promoteQueuedSteeringMessages(ids);
  },
  restoreInputText(text: string) {
    stores?.getChatStore().restoreInputText(text);
  },

  // Sub-Agent methods from Kotlin
  spawnSubAgent(payload: string) {
    stores?.getChatStore().spawnSubAgent(payload);
  },
  updateSubAgentIteration(payload: string) {
    stores?.getChatStore().updateSubAgentIteration(payload);
  },
  addSubAgentToolCall(payload: string) {
    stores?.getChatStore().addSubAgentToolCall(payload);
  },
  updateSubAgentToolCall(payload: string) {
    stores?.getChatStore().updateSubAgentToolCall(payload);
  },
  updateSubAgentMessage(payload: string) {
    stores?.getChatStore().updateSubAgentMessage(payload);
  },
  completeSubAgent(payload: string) {
    stores?.getChatStore().completeSubAgent(payload);
  },
  loadChatSnapshot(snapshotJson: string) {
    try {
      const snapshot = JSON.parse(snapshotJson);
      const store = stores?.getChatStore();
      if (!store) return;
      // Restore messages
      if (snapshot.messages) {
        for (const msg of snapshot.messages) {
          if (msg.toolChain) {
            store.addMessage('agent', '');
            // Patch the last message to include the toolChain
            const messages = store.messages ?? [];
            if (messages.length > 0) {
              messages[messages.length - 1].toolChain = msg.toolChain;
            }
          } else if (msg.subAgent) {
            store.addMessage('agent', '');
            const messages = store.messages ?? [];
            if (messages.length > 0) {
              messages[messages.length - 1].subAgent = msg.subAgent;
            }
          } else {
            store.addMessage(msg.role, msg.content || '');
          }
        }
      }
      // Restore plan
      if (snapshot.plan) store.setPlan(snapshot.plan);
      // Restore session info
      if (snapshot.session) {
        store.session = snapshot.session;
      }
      // Restore token budget
      if (snapshot.tokenBudget) {
        store.updateTokenBudget(snapshot.tokenBudget.used, snapshot.tokenBudget.max);
      }
      // Restore busy state
      if (snapshot.busy) store.setBusy(true);
    } catch (e) {
      console.error('[bridge] loadChatSnapshot error:', e);
    }
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
  toggleRalphLoop(enabled: boolean): void { callKotlin('_toggleRalphLoop', enabled); },
  activateSkill(name: string): void { callKotlin('_activateSkill', name); },
  requestFocusIde(): void { callKotlin('_requestFocusIde'); },
  openSettings(): void { callKotlin('_openSettings'); },
  openToolsPanel(): void { callKotlin('_openToolsPanel'); },
  viewInEditor(): void { callKotlin('_viewInEditor'); },
  killToolCall(toolCallId: string): void { callKotlin('_killToolCall', toolCallId); },
  killSubAgent(agentId: string): void { callKotlin('_killSubAgent', agentId); },
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

// ═══ Early registration ═══
// Register bridge functions on window IMMEDIATELY at module load, not in useEffect.
// This ensures Kotlin's pendingCalls (flushed on onLoadingStateChange) can find them.
// Calls that arrive before stores are set get buffered and replayed in initBridge().
for (const [name, fn] of Object.entries(bridgeFunctions)) {
  (window as any)[name] = (...args: any[]) => {
    if (stores) {
      fn(...args);
    } else {
      pendingCalls.push({ name, args });
    }
  };
}

// ═══ Initialization ═══

export function initBridge(storeAccessors: StoreAccessors): void {
  stores = storeAccessors;

  // Re-register with direct function references (no buffering wrapper needed now)
  const bridge: Record<string, (...args: any[]) => void> = {};
  for (const [name, fn] of Object.entries(bridgeFunctions)) {
    (window as any)[name] = fn;
    bridge[name] = fn;
  }
  (window as any).__bridge = bridge;

  // Replay any calls that arrived before stores were ready
  for (const call of pendingCalls) {
    const fn = bridgeFunctions[call.name];
    if (fn) {
      try { fn(...call.args); } catch (_) { /* best effort */ }
    }
  }
  pendingCalls.length = 0;
}
