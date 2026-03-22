import { useEffect } from 'react'
import { initBridge, isJcefEnvironment } from './bridge/jcef-bridge'
import { installMockBridge, simulateTheme } from './bridge/mock-bridge'
import { useChatStore } from './stores/chatStore'
import { useThemeStore } from './stores/themeStore'
import { useSettingsStore } from './stores/settingsStore'
import { MessageList } from '@/components/chat/MessageList'
import { ChatInput } from '@/components/input/ChatInput'
import { ScreenReaderAnnouncer } from '@/components/common/ScreenReaderAnnouncer'
import { useEscapeHandler } from '@/hooks/useEscapeHandler'

function App() {
  useEscapeHandler();

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
        const chatInput = document.querySelector<HTMLTextAreaElement>('[data-chat-input]');
        chatInput?.focus();
      }
    }
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, []);

  return (
    <div className="flex h-screen flex-col bg-[var(--bg,#1e1e1e)] text-[var(--fg,#cccccc)]">
      <ScreenReaderAnnouncer />
      <MessageList />
      <ChatInput />
    </div>
  );
}

export default App
