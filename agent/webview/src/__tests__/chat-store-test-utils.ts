/**
 * Shared scaffolding for tests that drive `useChatStore` directly.
 * Keeps the per-file boilerplate minimal so store-level tests read as
 * sequences of store actions + assertions.
 */
import { useChatStore } from '@/stores/chatStore';
import type { ToolCall } from '@/bridge/types';

/** Current store snapshot — prefer this over `useChatStore.getState()` in tests. */
export function chatState() {
  return useChatStore.getState();
}

/** Reset all chat-related store slices. Call in `beforeEach`. */
export function resetChatStore() {
  useChatStore.getState().clearChat();
}

/** Insertion-ordered array view of `activeToolCalls`, which is a Map. */
export function activeToolCallsArray(): ToolCall[] {
  return Array.from(chatState().activeToolCalls.values());
}
