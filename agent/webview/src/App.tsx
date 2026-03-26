import { useEffect } from 'react'
import { initBridge, isJcefEnvironment } from './bridge/jcef-bridge'
import { installMockBridge, simulateTheme } from './bridge/mock-bridge'
import { useChatStore } from './stores/chatStore'
import { useThemeStore } from './stores/themeStore'
import { useSettingsStore } from './stores/settingsStore'
import { ChatView } from '@/components/chat/ChatView'
import { TopBar } from '@/components/chat/TopBar'
import { DebugPanel } from '@/components/chat/DebugPanel'
import { InputBar } from '@/components/input/InputBar'
import { SkillBanner } from '@/components/chat/SkillBanner'
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

  // Fix Radix Popper positioning: @floating-ui miscalculates the portal position
  // in our layout (flex + overflow: hidden chain), sending dropdowns off-screen.
  // We fix Y via CSS (bottom: 60px) and align X to the trigger element here.
  useEffect(() => {
    const popperObserver = new MutationObserver((mutations) => {
      for (const m of mutations) {
        for (const node of m.addedNodes) {
          if (!(node instanceof HTMLElement)) continue;
          const wrapper = node.matches('[data-radix-popper-content-wrapper]')
            ? node
            : node.querySelector?.('[data-radix-popper-content-wrapper]');
          if (!wrapper) continue;

          const trigger = document.querySelector('[data-slot="dropdown-menu-trigger"][aria-expanded="true"]');
          if (trigger) {
            const triggerRect = trigger.getBoundingClientRect();
            (wrapper as HTMLElement).style.setProperty('--popper-fix-x', `${triggerRect.left}px`);
          }
        }
      }
    });
    popperObserver.observe(document.body, { childList: true, subtree: true });
    return () => popperObserver.disconnect();
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
    <div className="flex h-screen flex-col bg-[var(--bg,#1e1e1e)] text-[var(--fg,#cccccc)]">
      <ScreenReaderAnnouncer />
      <TopBar />
      <SkillBanner />
      <ChatView />
      <DebugPanel />
      <InputBar />
    </div>
  );
}

export default App
