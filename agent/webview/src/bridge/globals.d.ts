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
    _resolveSymbols?: (hrefsJson: string, cb: string) => void;
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
    _cancelSteering?: (steeringId: string) => void;
    _revertToUserMessage?: (ts: number) => void;
    _revertFileToBaseline?: (path: string) => void;
    _revertAll?: () => void;
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
    // Sub-agent thinking bridge hooks (P2.T5)
    _appendSubAgentThinking?: (payload: { agentId: string; delta: string }) => void;
    _endSubAgentThinking?: (payload: { agentId: string }) => void;
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
    // Phase 4 chat hyperlink bridges. `_resolveLink` returns the raw JSON
    // string from the bridge (parsed inside the modal). `_openLink` is
    // fire-and-forget — the Kotlin side dispatches into the LinkResolver's
    // coroutine scope and never blocks.
    _resolveLink?: (href: string) => Promise<string>;
    _openLink?: (href: string) => void;
    _copyToClipboard?: (text: string) => void;
    /**
     * Document-extraction progress bridges.
     * Driven by Kotlin's AgentController.pushDocumentProgress while a
     * read_document call is blocking. The arg to `_documentExtractionProgress`
     * is a JSON string `{stage, pagesDone, pagesTotal, elapsedMs}`.
     * `_documentExtractionClear` fires when the read_document call completes.
     * See `DocumentExtractionProgressView.tsx` for the chat-side renderer.
     */
    _documentExtractionProgress?: (json: string) => void;
    _documentExtractionClear?: () => void;
    /**
     * Streaming `edit_file` preview bridges (Commit 2 of live-preview feature).
     * Driven by Kotlin's StreamingEditTracker during partial edit_file tool
     * calls. Args are JSON-encoded so multiline diffs and quotes survive the
     * literal. See `StreamingEditPreviewView.tsx` for the chat-side renderer.
     */
    _streamingEditOpen?: (callIdJson: string, pathJson: string, initialDiffJson: string) => void;
    _streamingEditUpdate?: (callIdJson: string, diffJson: string) => void;
    _streamingEditFinalize?: (callIdJson: string) => void;
    _streamingEditCancel?: (callIdJson: string) => void;
    /**
     * Plan 4 §5.5 — pushed by Kotlin's AgentController when a delegated question
     * arrives in IDE-B (active=true) or resolves (active=false).
     */
    _setDelegationQuestionPending?: (json: string) => void;
    /**
     * Plan 6 §incoming-delegation-topbar — pushed by Kotlin's AgentController when
     * IDE-B (currently busy) receives a delegation knock and needs the human to
     * click Start within 60 s.
     * JSON shape: { key: string; delegatorRepo: string; deadlineEpochMs: number }
     */
    _incomingDelegation?: (jsonString: string) => void;
    /**
     * Plan 6 §incoming-delegation-topbar — pushed by Kotlin when a pending
     * incoming delegation is started by the human or expired on the Kotlin side.
     */
    _incomingDelegationCleared?: (key: string) => void;
    /**
     * Task 11 — drop-zone overlay driven by the JVM Swing DropTarget.
     * Pushed by Kotlin's AttachmentDropTarget via AgentCefPanel.callJs()
     * when an OS file drag enters (true) or exits (false) the JCEF component.
     */
    _setDropActive?: (active: boolean) => void;
    /**
     * Task 11/12 — pushes a chip metadata object into the webview
     * AttachmentManager for a file already stored on the JVM side
     * (picker or drop). Defined by InputBar at mount.
     */
    _addAttachmentChip?: (meta: unknown) => void;
    /**
     * Task 11/12 — invokes the JVM-side FileChooser picker.
     * Injected by AgentCefPanel as a JBCefJSQuery bridge function.
     */
    _pickAttachment?: () => void;
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
