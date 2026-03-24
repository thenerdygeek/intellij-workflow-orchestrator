import { useEffect } from 'react';

/**
 * Escape key cascading handler.
 * Priority order:
 *   1. Close Dialog overlay (if open — handled by Radix Dialog internally)
 *   2. Focus the chat input (fallback)
 *
 * Radix Dialog handles its own Escape key dismiss,
 * so this handler only fires when no dialog is open.
 */
export function useEscapeHandler() {
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key !== 'Escape') return;

      // If a Dialog is open, Radix handles Escape internally,
      // so we won't reach here.

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
