import type {
  ToolCallStatus,
  StatusType,
  SessionStatus,
  UiMessage,
  HistoryItem,
  Task,
  CompletionData,
} from './types';
import { preloadDiff2Html } from '../components/rich/DiffHtml';
import { updateChartById } from '../components/rich/chartUtils';

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

// ═══ Kotlin → JS bridge functions (40 chat + 3 theme = 43 unique JS functions) ═══
// Note: Kotlin side has 44 methods but some are composites:
//   setText() = clearChat + appendToken + endStream
//   appendError() = appendStatus('ERROR')

const bridgeFunctions: Record<string, (...args: any[]) => void> = {
  startSession(task: string) {
    stores?.getChatStore().startSession(task);
  },
  startSessionWithMentions(task: string, mentionsJson: string) {
    try {
      const mentions = JSON.parse(mentionsJson);
      stores?.getChatStore().startSession(task, mentions);
    } catch {
      stores?.getChatStore().startSession(task);
    }
  },
  appendUserMessage(text: string) {
    stores?.getChatStore().addUserMessage(text);
  },
  appendPlanApprovedMessage(planMarkdown: string) {
    stores?.getChatStore().addPlanApprovedMessage(planMarkdown);
  },
  appendUserMessageWithMentions(text: string, mentionsJson: string) {
    try {
      const mentions = JSON.parse(mentionsJson);
      stores?.getChatStore().addUserMessage(text, mentions);
    } catch {
      stores?.getChatStore().addUserMessage(text);
    }
  },
  finalizeQuestionsAsMessage() {
    stores?.getChatStore().finalizeQuestionsAsMessage();
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
  updateToolResult(result: string, durationMs: number, toolName: string, status?: string, output?: string | null, diff?: string | null, toolCallId?: string) {
    const resolvedStatus = (status === 'ERROR' ? 'ERROR' : 'COMPLETED') as ToolCallStatus;
    stores?.getChatStore().updateToolCall(toolName, resolvedStatus, result, durationMs, output ?? undefined, diff ?? undefined, toolCallId ?? undefined);
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
  _appendCompletionCard(json: string) {
    try {
      const data = JSON.parse(json) as CompletionData;
      stores?.getChatStore().addCompletionCard(data);
    } catch (e) {
      console.error('[bridge] _appendCompletionCard parse error', e);
    }
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
  clearPlan() {
    stores?.getChatStore().clearPlan();
  },
  approvePlan() {
    stores?.getChatStore().approvePlan();
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
    try {
      const parsed = JSON.parse(questionsJson);
      // Kotlin sends either { questions: [...] } wrapper or bare [...] array
      const rawQuestions = Array.isArray(parsed) ? parsed : parsed.questions;
      if (rawQuestions && rawQuestions.length > 0) {
        // Map Kotlin field names to TS types: question→text, single→single-select
        const questions = rawQuestions.map((q: any) => ({
          ...q,
          text: q.text ?? q.question,  // Kotlin sends 'question', TS expects 'text'
          type: q.type === 'single' ? 'single-select'
              : q.type === 'multiple' ? 'multi-select'
              : q.type,
        }));
        console.log(`[bridge] showQuestions: ${questions.length} question(s), types=[${questions.map((q: any) => q.type).join(',')}], options=[${questions.map((q: any) => q.options?.length ?? 0).join(',')}]`);
        stores?.getChatStore().showQuestions(questions);
        // Verify the store accepted the questions
        const storeQuestions = stores?.getChatStore().questions;
        console.log(`[bridge] showQuestions: store has ${storeQuestions?.length ?? 'null'} questions after set`);
        // Round-trip: confirm successful render back to Kotlin
        window._reportInteractiveRender?.(JSON.stringify({ type: 'question', status: 'ok', count: questions.length }));
      } else {
        // rawQuestions was falsy or empty — extract question text and show as agent message
        console.warn('[bridge] showQuestions: no questions array — falling back to text');
        const questionText = parsed?.questions?.[0]?.question ?? parsed?.question ?? questionsJson;
        stores?.getChatStore().addAgentText(questionText);
        window._reportInteractiveRender?.(JSON.stringify({ type: 'question', status: 'error', message: 'No questions array — fell back to text' }));
      }
    } catch (e) {
      // JSON parse or mapping failed — show whatever we have as plain text so the user
      // sees the question instead of a stuck working indicator
      console.error('[bridge] showQuestions failed:', e);
      stores?.getChatStore().addAgentText('[Question] ' + (questionsJson ?? '(empty)'));
      window._reportInteractiveRender?.(JSON.stringify({ type: 'question', status: 'error', message: String(e) }));
    }
    // Always ensure busy is cleared and steering enabled — the agent is waiting for user input.
    console.log('[bridge] showQuestions: clearing busy, unlocking input, enabling steering');
    stores?.getChatStore().setBusy(false);
    stores?.getChatStore().setInputLocked(false);
    stores?.getChatStore().setSteeringMode(true);
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
  showResumeBar(sessionId: string) {
    stores?.getChatStore().setResumeSessionId(sessionId);
  },
  hideResumeBar() {
    stores?.getChatStore().setResumeSessionId(null);
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
  updateMemoryStats(coreChars: number, archivalCount: number) {
    stores?.getChatStore().updateMemoryStats(coreChars, archivalCount);
  },
  setModelName(name: string) {
    stores?.getChatStore().setModelName(name);
  },
  setModelFallbackState(isFallback: boolean, reason: string | null) {
    stores?.getChatStore().setModelFallbackState(isFallback, reason);
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
  showRetryButton(kind: 'continue' | 'retry', caption: string) {
    stores?.getChatStore().showRetryButton(kind, caption);
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
    updateChartById(id, dataJson);
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
  showApproval(
    toolName: string,
    riskLevel: string,
    description: string,
    metadataJson: string,
    diffContent?: string | null,
    commandPreviewJson?: string | null,
    allowSessionApproval?: boolean,
  ) {
    // Preload diff2html immediately so the module is ready (or nearly ready) by the time
    // ApprovalView mounts and DiffHtml's useEffect fires — eliminates the loading skeleton.
    if (diffContent) preloadDiff2Html();
    const metadata = metadataJson ? JSON.parse(metadataJson) : [];
    let commandPreview: unknown = undefined;
    if (commandPreviewJson) {
      try {
        commandPreview = JSON.parse(commandPreviewJson);
      } catch (e) {
        console.error('[bridge] commandPreview parse error:', e);
      }
    }
    stores?.getChatStore().showApproval(
      toolName,
      riskLevel,
      description,
      metadata,
      diffContent ?? undefined,
      commandPreview ?? undefined,
      allowSessionApproval ?? true,
    );
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
    } catch (e) {
      console.warn('[bridge] addDebugLogEntry: malformed JSON', e);
    }
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
    } catch (e) {
      console.warn('[bridge] updateCheckpoints: malformed JSON', e);
    }
  },
  notifyRollback(json: string) {
    try {
      const rollback = JSON.parse(json);
      stores?.getChatStore().applyRollback(rollback);
    } catch (e) {
      console.warn('[bridge] notifyRollback: malformed JSON', e);
    }
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

  // Artifact rendering from Kotlin
  renderArtifact(payload: string) {
    stores?.getChatStore().addArtifact(payload);
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
  appendSubAgentStreamDelta(payload: string) {
    stores?.getChatStore().appendSubAgentStreamDelta(payload);
  },
  completeSubAgent(payload: string) {
    stores?.getChatStore().completeSubAgent(payload);
  },
  loadChatSnapshot(snapshotJson: string) {
    try {
      const snapshot = JSON.parse(snapshotJson);
      const store = stores?.getChatStore();
      if (!store) return;
      // Restore messages — snapshot may contain old Message format or new UiMessage format.
      // Convert all to UiMessage[], then hydrate as a single batch.
      if (snapshot.messages) {
        const converted: UiMessage[] = snapshot.messages.map((msg: any) => {
          // Already UiMessage format
          if (msg.ts && msg.type) return msg as UiMessage;
          // Legacy Message format — convert to UiMessage
          return {
            ts: msg.timestamp || Date.now(),
            type: 'SAY' as const,
            say: msg.role === 'user' ? 'USER_MESSAGE' as const : 'TEXT' as const,
            text: msg.content || '',
          };
        });
        store.hydrateFromUiMessages(converted);
      }
      // Restore plan
      if (snapshot.plan) store.setPlan(snapshot.plan);
      // Restore session info via completeSession (proper Zustand set)
      if (snapshot.session) {
        store.completeSession(snapshot.session);
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
  loadSessionState(uiMessagesJson: string) {
    try {
      const uiMessages: UiMessage[] = JSON.parse(uiMessagesJson);
      stores?.getChatStore().hydrateFromUiMessages(uiMessages);
    } catch (e) {
      console.error('[bridge] loadSessionState error:', e);
    }
  },

  // ── History view methods ──
  loadSessionHistory(historyItemsJson: string) {
    try {
      const items: HistoryItem[] = JSON.parse(historyItemsJson);
      stores?.getChatStore().setHistoryItems(items);
      stores?.getChatStore().setViewMode('history');
    } catch (e) {
      console.error('[bridge] loadSessionHistory error:', e);
    }
  },

  showHistoryView() {
    stores?.getChatStore().setViewMode('history');
  },

  showChatView() {
    stores?.getChatStore().setViewMode('chat');
  },
};

// Task bridge functions registered separately (Kotlin→JS underscore-prefix convention)
// These are registered in initBridge() after stores are ready.
function registerTaskBridges(): void {
  (window as any)._applyTaskCreate = (task: Task) => {
    stores?.getChatStore().applyTaskCreate(task);
  };
  (window as any)._applyTaskUpdate = (task: Task) => {
    stores?.getChatStore().applyTaskUpdate(task);
  };
  (window as any)._setTasks = (tasks: Task[]) => {
    stores?.getChatStore().setTasks(tasks);
  };
}

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
  dismissPlan(): void { callKotlin('_dismissPlan'); },
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
  openMemorySettings(): void { callKotlin('_openMemorySettings'); },
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
  retryLastTask(): void { callKotlin('_retryLastTask'); },

  // ── History view actions ──
  showSession(sessionId: string) {
    callKotlin('_showSession', sessionId);
  },
  resumeViewedSession() {
    callKotlin('_resumeViewedSession');
  },
  deleteSession(sessionId: string) {
    callKotlin('_deleteSession', sessionId);
  },
  toggleFavorite(sessionId: string) {
    callKotlin('_toggleFavorite', sessionId);
  },
  startNewSession() {
    callKotlin('_startNewSession');
  },
  bulkDeleteSessions(sessionIdsJson: string) {
    callKotlin('_bulkDeleteSessions', sessionIdsJson);
  },
  exportSession(sessionId: string) {
    callKotlin('_exportSession', sessionId);
  },
  exportAllSessions() {
    callKotlin('_exportAllSessions');
  },
  copyToClipboard(text: string) {
    callKotlin('_copyToClipboard', text);
  },
  requestHistory() {
    callKotlin('_requestHistory');
  },

  /**
   * Report the outcome of an artifact render round-trip back to Kotlin so the
   * suspended `render_artifact` tool call can resume with a structured result.
   * Called by `ArtifactRenderer` after the sandbox iframe posts `rendered` or `error`.
   *
   * Payload shape:
   *   { renderId, status: "success" | "error",
   *     heightPx?, phase?, message?, missingSymbols?, line? }
   */
  reportArtifactResult(payload: {
    renderId: string;
    status: 'success' | 'error';
    heightPx?: number;
    phase?: string;
    message?: string;
    missingSymbols?: string[];
    line?: number;
  }): void {
    callKotlin('_reportArtifactResult', JSON.stringify(payload));
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

// Alias: Kotlin calls _loadSessionState (underscore prefix convention for Kotlin→JS resume path)
(window as any)._loadSessionState = (window as any).loadSessionState;

// History view aliases (Kotlin→JS)
(window as any)._loadSessionHistory = (window as any).loadSessionHistory;
(window as any)._showHistoryView = (window as any).showHistoryView;
(window as any)._showChatView = (window as any).showChatView;

// Task bridge early registration — module-scope safety net so session-resume
// _setTasks calls arriving before initBridge() don't race-drop.
// Uses same pendingCalls buffer pattern as bridgeFunctions above.
// In initBridge(), these are replaced with direct functions via registerTaskBridges().
const taskBridgeFunctions: Record<string, (...args: any[]) => void> = {
  _applyTaskCreate: (task: Task) => { stores?.getChatStore().applyTaskCreate(task); },
  _applyTaskUpdate: (task: Task) => { stores?.getChatStore().applyTaskUpdate(task); },
  _setTasks: (tasks: Task[]) => { stores?.getChatStore().setTasks(tasks); },
};
for (const [name, fn] of Object.entries(taskBridgeFunctions)) {
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

  // Re-register aliases after stores are set (direct, no buffering)
  (window as any)._loadSessionState = bridgeFunctions.loadSessionState;
  (window as any)._loadSessionHistory = bridgeFunctions.loadSessionHistory;
  (window as any)._showHistoryView = bridgeFunctions.showHistoryView;
  (window as any)._showChatView = bridgeFunctions.showChatView;

  // Register task bridge functions (Kotlin→JS, Phase 5 task system port)
  // Re-register with direct references now that stores are available (no buffering wrapper needed).
  registerTaskBridges();
  for (const [name, fn] of Object.entries(taskBridgeFunctions)) {
    (window as any)[name] = fn;
  }

  // Replay any calls that arrived before stores were ready
  for (const call of pendingCalls) {
    const fn = (bridgeFunctions as Record<string, (...args: any[]) => void>)[call.name]
      ?? taskBridgeFunctions[call.name];
    if (fn) {
      try { fn(...call.args); } catch (_) { /* best effort */ }
    }
  }
  pendingCalls.length = 0;
}
