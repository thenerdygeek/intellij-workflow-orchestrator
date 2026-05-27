import { useEffect } from 'react'
import { initBridge, isJcefEnvironment, kotlinBridge } from './bridge/jcef-bridge'
import { installMockBridge, simulateTheme } from './bridge/mock-bridge'
import { useChatStore } from './stores/chatStore'
import { useThemeStore } from './stores/themeStore'
import { useSettingsStore } from './stores/settingsStore'
import { ChatView } from '@/components/chat/ChatView'
import { TopBar } from '@/components/chat/TopBar'
import { DebugPanel } from '@/components/chat/DebugPanel'
import { InputBar } from '@/components/input/InputBar'
import { ErrorBoundary } from '@/components/chat/ErrorBoundary'
import { SkillBanner } from '@/components/chat/SkillBanner'
import { EditStatsBar } from '@/components/agent/EditStatsBar'
import { HistoryView } from './components/history/HistoryView'
import { DelegationBanner } from './components/history/DelegationBanner'
import { ScreenReaderAnnouncer } from '@/components/common/ScreenReaderAnnouncer'
import { ToastStack } from '@/components/chat/ToastStack'
import { useEscapeHandler } from '@/hooks/useEscapeHandler'

function App() {
  useEscapeHandler();
  const aggregateDiff = useChatStore(s => s.aggregateDiff);
  const viewMode = useChatStore(s => s.viewMode);
  const editorTabMode = useChatStore(s => s.editorTabMode);

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

  // Radix renders its popper into a portal inserted at the END of <body>. We
  // observe ONLY direct children of <body> (no subtree) so the callback fires
  // when the portal is added/removed, not for every span Streamdown emits
  // mid-stream.
  useEffect(() => {
    const fixPopperX = (root: HTMLElement) => {
      const wrapper = root.matches('[data-radix-popper-content-wrapper]')
        ? root
        : root.querySelector?.('[data-radix-popper-content-wrapper]');
      if (!wrapper) return;
      const trigger = document.querySelector('[data-slot="dropdown-menu-trigger"][aria-expanded="true"]');
      if (trigger) {
        const triggerRect = trigger.getBoundingClientRect();
        (wrapper as HTMLElement).style.setProperty('--popper-fix-x', `${triggerRect.left}px`);
      }
    };

    const popperObserver = new MutationObserver((mutations) => {
      for (const m of mutations) {
        for (const node of m.addedNodes) {
          if (node instanceof HTMLElement) fixPopperX(node);
        }
      }
    });
    popperObserver.observe(document.body, { childList: true });  // no subtree
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

  // Delegated click handler for .file-link elements produced by file-link-scanner.
  // A single document-level handler covers all scanner output regardless of where
  // it is rendered in the tree — no per-component wiring required.
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      const target = e.target as Element | null;
      if (!target) return;
      const el = target.closest('.file-link') as HTMLElement | null;
      if (!el) return;
      e.preventDefault();
      const canonical = el.dataset.canonical;
      const line = Number(el.dataset.line) || 0;
      if (canonical) kotlinBridge.navigateToFile(canonical, line);
    };
    document.addEventListener('click', handler);
    return () => document.removeEventListener('click', handler);
  }, []);

  if (editorTabMode) {
    return (
      <div className="flex h-screen flex-col bg-[var(--bg,#1e1e1e)] text-[var(--fg,#cccccc)]">
        <ScreenReaderAnnouncer />
        <ChatView />
        <ToastStack />
      </div>
    );
  }

  return (
    <div className="flex h-screen flex-col bg-[var(--bg,#1e1e1e)] text-[var(--fg,#cccccc)]">
      <ScreenReaderAnnouncer />
      <ToastStack />
      {viewMode === 'history' ? (
        <HistoryView />
      ) : (
        <>
          <TopBar />
          <SkillBanner />
          <DelegationBanner />
          <ChatView />
          <DebugPanel />
          <EditStatsBar diff={aggregateDiff} />
          <ErrorBoundary
            fallback={
              <div className="px-4 py-3 text-[12px]" style={{ color: 'var(--error, #ef4444)', borderTop: '1px solid var(--border)' }}>
                Input crashed. <button className="underline" onClick={() => window.location.reload()}>Reload</button>
              </div>
            }
          >
            <InputBar />
          </ErrorBoundary>
        </>
      )}
    </div>
  );
}

export default App
