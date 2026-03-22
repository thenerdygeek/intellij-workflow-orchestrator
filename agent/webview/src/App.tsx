import { useEffect } from 'react'
import { initBridge, isJcefEnvironment } from './bridge/jcef-bridge'
import { installMockBridge, simulateTheme } from './bridge/mock-bridge'
import { useChatStore } from './stores/chatStore'
import { useThemeStore } from './stores/themeStore'
import { useSettingsStore } from './stores/settingsStore'

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
    <div className="flex flex-col items-center justify-center h-screen bg-[var(--bg,#2b2d30)] text-[var(--fg,#cbd5e1)]">
      <h1 className="text-2xl font-semibold">Agent Chat</h1>
      <p className="text-sm text-[var(--fg-secondary,#94a3b8)] mt-2">
        Bridge initialized. {isJcefEnvironment() ? 'JCEF mode' : 'Dev mode — open console for __mock commands.'}
      </p>
    </div>
  );
}

export default App
