import { useEffect } from 'react'
import { initBridge, isJcefEnvironment } from './bridge/jcef-bridge'
import { installMockBridge, simulateTheme } from './bridge/mock-bridge'
import { useChatStore } from './stores/chatStore'
import { useThemeStore } from './stores/themeStore'
import { useSettingsStore } from './stores/settingsStore'
import { MessageList } from '@/components/chat/MessageList'
import { ChatInput } from '@/components/input/ChatInput'

function App() {
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

  return (
    <div className="flex h-screen flex-col bg-[var(--bg,#1e1e1e)] text-[var(--fg,#cccccc)]">
      <MessageList />
      <ChatInput />
    </div>
  );
}

export default App
