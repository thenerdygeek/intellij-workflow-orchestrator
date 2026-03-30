export {};

declare global {
  interface Window {
    _sendMessage?: (text: string) => void;
    _sendMessageWithMentions?: (payload: string) => void;
    _searchMentions?: (data: string) => void;
    _searchTickets?: (query: string) => void;
    _validateTicket?: (ticketKey: string, callbackName: string) => void;
    _cancelTask?: () => void;
    _newChat?: () => void;
    _requestUndo?: () => void;
    _requestViewTrace?: () => void;
    _openSettings?: () => void;
    _openToolsPanel?: () => void;
    _changeModel?: (modelId: string) => void;
    _togglePlanMode?: (enabled: boolean) => void;
    _navigateToFile?: (path: string) => void;
    _requestFocusIde?: () => void;
    _submitPrompt?: (text: string) => void;
    _approvePlan?: () => void;
    _revisePlan?: (comments: string) => void;
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
    __mock?: Record<string, (...args: any[]) => any>;
    // Debug log panel — pushed from Kotlin via AgentCefPanel.updateDebugLogVisibility()
    setDebugLogVisible?: (visible: boolean) => void;
    // Debug log entry — pushed from Kotlin to append a structured log entry
    addDebugLogEntry?: (entryJson: string) => void;
  }
}
