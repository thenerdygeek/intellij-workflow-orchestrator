import { useEffect } from 'react'
import { initBridge, isJcefEnvironment } from './bridge/jcef-bridge'
import { installMockBridge, simulateTheme } from './bridge/mock-bridge'
import { useChatStore } from './stores/chatStore'
import { useThemeStore } from './stores/themeStore'
import { useSettingsStore } from './stores/settingsStore'
import { MessageList } from '@/components/chat/MessageList'

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
      <div className="border-t border-[var(--border,#333)] px-4 py-3">
        <div className="rounded-lg border border-[var(--input-border,#444)] bg-[var(--input-bg,#2a2a2a)] px-3 py-2 text-[13px] text-[var(--fg-muted,#888)]">
          Ask anything...
        </div>
      </div>
    </div>
  );
}

export default App
