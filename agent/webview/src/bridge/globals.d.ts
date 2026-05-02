import type { Task } from './types';
import type { BackgroundProcessSnapshot } from '../stores/chatStore';

export {};

declare global {
  interface Window {
    // Background process bridge (Phase 7, Task 7.3)
    _loadBackgroundSnapshot?: (sessionId: string) => Promise<BackgroundProcessSnapshot[]>;
    __receiveBackgroundUpdate?: (snapshot: BackgroundProcessSnapshot[]) => void;
    _sendMessage?: (text: string) => void;
    _sendMessageWithMentions?: (payload: string) => void;
    _searchMentions?: (data: string) => void;
    _searchTickets?: (query: string) => void;
    _validateTicket?: (ticketKey: string, callbackName: string) => void;
    _validatePaths?: (pathsJson: string, callbackName: string) => void;
    _cancelTask?: () => void;
    _newChat?: () => void;
    _requestUndo?: () => void;
    _requestViewTrace?: () => void;
    _openSettings?: () => void;
    _openToolsPanel?: () => void;
    _changeModel?: (modelId: string) => void;
    /** Pull the model list from Kotlin (recovers when initial push was lost or returned empty). */
    _requestModelList?: () => void;
    _togglePlanMode?: (enabled: boolean) => void;
    _compactContext?: (force: boolean) => void;
    _toggleRalphLoop?: (enabled: boolean) => void;
    _navigateToFile?: (path: string) => void;
    _requestFocusIde?: () => void;
    _submitPrompt?: (text: string) => void;
    _approvePlan?: () => void;
    _revisePlan?: (comments: string) => void;
    _dismissPlan?: () => void;
    _toggleTool?: (data: string) => void;
    _questionAnswered?: (qid: string, opts: string) => void;
    _questionSkipped?: (qid: string) => void;
    _chatAboutOption?: (qid: string, label: string, msg: string) => void;
    _questionsSubmitted?: () => void;
    _questionsCancelled?: () => void;
    _editQuestion?: (qid: string) => void;
    _approveToolCall?: () => void;
    _denyToolCall?: () => void;
    _allowToolForSession?: (toolName: string) => void;
    _deactivateSkill?: () => void;
    _activateSkill?: (name: string) => void;
    _openInEditorTab?: (payload: string) => void;
    _viewInEditor?: () => void;
    _interactiveHtmlMessage?: (json: string) => void;
    _acceptDiffHunk?: (filePath: string, hunkIndex: number, editedContent?: string) => void;
    _rejectDiffHunk?: (filePath: string, hunkIndex: number) => void;
    _killToolCall?: (toolCallId: string) => void;
    _revertCheckpoint?: (checkpointId: string) => void;
    _cancelSteering?: (steeringId: string) => void;
    _retryLastTask?: () => void;
    _reportInteractiveRender?: (json: string) => void;
    _resumeViewedSession?: () => void;
    _loadSessionState?: (uiMessagesJson: string) => void;
    _bulkDeleteSessions?: (sessionIdsJson: string) => void;
    _exportSession?: (sessionId: string) => void;
    _exportAllSessions?: () => void;
    __mock?: Record<string, (...args: any[]) => any>;
    // Debug log panel — pushed from Kotlin via AgentCefPanel.updateDebugLogVisibility()
    setDebugLogVisible?: (visible: boolean) => void;
    // Debug log entry — pushed from Kotlin to append a structured log entry
    addDebugLogEntry?: (entryJson: string) => void;
    // Task bridge functions (Phase 5 task system port)
    _applyTaskCreate?: (task: Task) => void;
    _applyTaskUpdate?: (task: Task) => void;
    _setTasks?: (tasks: Task[]) => void;
    _appendCompletionCard?: (json: string) => void;
    _openApprovedPlan?: () => void;
    _receiveSessionStats?: (json: string) => void;
    _openInsightsTab?: () => void;
    // Multimodal-agent Phase 7 — chat input usage indicator + Phase 5/6 followups
    _getContextUsage?: () => Promise<{ used: number; max: number }>;
    _getImageSettings?: () => Promise<{ maxBytes: number; mimeWhitelist: string[]; maxPerTurn: number; enabled: boolean } | null>;
    /**
     * Phase 7 — namespace for new bridges. Distinct from the legacy `window._xxx`
     * flat namespace; the picker / usage indicator / image-settings push live
     * here so future additions don't pollute the global object.
     */
    workflowAgent?: {
      getContextUsage?: () => Promise<{ used: number; max: number }>;
      refreshImageSettings?: () => Promise<void>;
    };
    /**
     * Phase 7 followup F-P5-2 / F-P6-1 — global hook the bridge uses to push
     * fresh image settings into the AttachmentManager singleton. Defined by
     * `<InputBar>` at mount; the bridge calls it after Settings.apply.
     */
    __applyImageSettings?: (json: string) => void;
  }
}
