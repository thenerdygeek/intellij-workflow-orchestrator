import { useEffect } from 'react';

/**
 * Escape key cascading handler.
 * Priority order:
 *   1. Close FullscreenOverlay (if open — handled by FullscreenOverlay itself)
 *   2. Focus the chat input (fallback)
 *
 * FullscreenOverlay registers its own keydown listener with stopPropagation,
 * so this handler only fires when no overlay is open.
 */
export function useEscapeHandler() {
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key !== 'Escape') return;

      // If a FullscreenOverlay is open, it handles Escape via its own listener
      // (with stopPropagation), so we won't reach here.

      // Close mention dropdown if open — the dropdown handles its own dismiss,
      // but as a fallback, blur and refocus the input.
      const chatInput = document.querySelector<HTMLTextAreaElement>('[data-chat-input]');
      if (chatInput && document.activeElement !== chatInput) {
        chatInput.focus();
        return;
      }

      // If already focused on input, blur to defocus
      if (chatInput && document.activeElement === chatInput) {
        chatInput.blur();
      }
    }

    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, []);
}
