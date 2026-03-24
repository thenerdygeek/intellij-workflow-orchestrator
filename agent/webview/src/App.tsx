import { useEffect } from 'react'
import { initBridge, isJcefEnvironment } from './bridge/jcef-bridge'
import { installMockBridge, simulateTheme } from './bridge/mock-bridge'
import { useChatStore } from './stores/chatStore'
import { useThemeStore } from './stores/themeStore'
import { useSettingsStore } from './stores/settingsStore'
import { ChatView, WorkingIndicator } from '@/components/chat/ChatView'
import { InputBar } from '@/components/input/InputBar'
import { ScreenReaderAnnouncer } from '@/components/common/ScreenReaderAnnouncer'
import { useEscapeHandler } from '@/hooks/useEscapeHandler'

function App() {
  useEscapeHandler();
  const busy = useChatStore(s => s.busy);
  const activeStream = useChatStore(s => s.activeStream);
  const activeToolCalls = useChatStore(s => s.activeToolCalls);
  const showWorkingIndicator = busy && !activeStream && activeToolCalls.size === 0;

  useEffect(() => {
    initBridge({
      getChatStore: () => useChatStore.getState(),
      getThemeStore: () => useThemeStore.getState(),
      getSettingsStore: () => useSettingsStore.getState(),
    });

    if (!isJcefEnvironment()) {
      installMockBridge();
      simulateTheme(true);
    }
  }, []);

  // Ctrl/Cmd+K to focus the chat input
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        useChatStore.getState().focusInput();
      }
    }
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, []);

  return (
    <div className="flex h-screen flex-col overflow-hidden bg-[var(--bg,#1e1e1e)] text-[var(--fg,#cccccc)]">
      <ScreenReaderAnnouncer />
      <ChatView />
      {showWorkingIndicator && <WorkingIndicator />}
      <InputBar />
    </div>
  );
}

export default App
