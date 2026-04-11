/**
 * Scenario tests for the unified streaming-message model in chatStore.
 *
 * **Why these tests exist.** Earlier in this branch the streaming text lived
 * in `activeStream` and was rendered through a dedicated `StreamingMessage`
 * component. On stream end, the chat store pushed a NEW agent message into
 * `messages`, and `ChatView` rendered it through `AgentMessage` — a
 * different component with a different wrapper (avatar, "Agent" label,
 * 85% max-width bubble, entrance animation). The user saw the finalized
 * message flash/reformat because the DOM structure changed between the two
 * render paths.
 *
 * The fix reshapes the store: on the first token, `appendToken` creates a
 * placeholder `Message` in `messages` with a fresh id and points
 * `activeStream.messageId` at it. Every subsequent token updates that
 * message's `content` in place via `.map(...)`. `endStream` just clears
 * `activeStream` — it does NOT push another message. `ChatView` renders
 * every agent message through the same `AgentMessage` component,
 * toggling `isStreaming` based on whether `activeStream.messageId === msg.id`.
 * The DOM structure stays identical across the transition, so there's
 * nothing to flash through.
 *
 * These tests lock in the store-level contract:
 * - `appendToken` creates exactly one placeholder on the first token.
 * - Subsequent tokens update the SAME message id in place.
 * - `endStream` does not add or remove any messages — it only clears
 *   `activeStream`. The placeholder stays where it is, unchanged.
 * - New stream after `endStream` creates a NEW placeholder with a new id.
 * - `addToolCall` mid-stream clears `activeStream` but does not duplicate
 *   the streaming text into a second message.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { useChatStore } from '@/stores/chatStore';

function getState() {
  return useChatStore.getState();
}

function reset() {
  useChatStore.getState().clearChat();
}

describe('chatStore — unified streaming-message model', () => {
  beforeEach(reset);

  it('first appendToken creates a placeholder agent message in messages and points activeStream.messageId at it', () => {
    const { appendToken } = getState();
    appendToken('Hello');

    const state = getState();
    expect(state.messages).toHaveLength(1);
    expect(state.messages[0]!.role).toBe('agent');
    expect(state.messages[0]!.content).toBe('Hello');

    expect(state.activeStream).not.toBeNull();
    expect(state.activeStream!.messageId).toBe(state.messages[0]!.id);
    expect(state.activeStream!.text).toBe('Hello');
    expect(state.activeStream!.isStreaming).toBe(true);
  });

  it('subsequent appendToken updates the SAME message in place — id never changes', () => {
    const { appendToken } = getState();
    appendToken('Hello');
    const firstId = getState().messages[0]!.id;

    appendToken(', world');
    appendToken('. How are you?');

    const state = getState();
    // Still exactly one message — no new ones were created.
    expect(state.messages).toHaveLength(1);
    // Same id — React reconciles in place, no remount.
    expect(state.messages[0]!.id).toBe(firstId);
    // Content accumulated correctly.
    expect(state.messages[0]!.content).toBe('Hello, world. How are you?');
    // activeStream pointer unchanged.
    expect(state.activeStream?.messageId).toBe(firstId);
    expect(state.activeStream?.text).toBe('Hello, world. How are you?');
  });

  it('endStream does NOT add or remove messages — it only clears activeStream', () => {
    const { appendToken, endStream } = getState();
    appendToken('Some text');
    appendToken(' streamed in.');

    const beforeEndStream = getState();
    const beforeLength = beforeEndStream.messages.length;
    const beforeId = beforeEndStream.messages[0]!.id;
    const beforeContent = beforeEndStream.messages[0]!.content;

    endStream();

    const after = getState();
    // ── The core no-flash contract ──
    // No new message was pushed — the placeholder stays in place with
    // identical id and content. React sees the same key, same prop
    // reference, no reconciliation delta → no remount → no flash.
    expect(after.messages).toHaveLength(beforeLength);
    expect(after.messages[0]!.id).toBe(beforeId);
    expect(after.messages[0]!.content).toBe(beforeContent);
    // activeStream is cleared, so ChatView's isStreaming flag flips false
    // on the next render — the caret goes away without disturbing the DOM.
    expect(after.activeStream).toBeNull();
  });

  it('a second stream after endStream creates a NEW placeholder with a different id', () => {
    const { appendToken, endStream } = getState();
    appendToken('First response.');
    const firstId = getState().messages[0]!.id;
    endStream();

    appendToken('Second response.');
    const state = getState();

    expect(state.messages).toHaveLength(2);
    expect(state.messages[0]!.id).toBe(firstId);
    expect(state.messages[0]!.content).toBe('First response.');
    expect(state.messages[1]!.id).not.toBe(firstId);
    expect(state.messages[1]!.content).toBe('Second response.');
    expect(state.activeStream!.messageId).toBe(state.messages[1]!.id);
  });

  it('addToolCall mid-stream clears activeStream but does NOT duplicate the streaming message', () => {
    const { appendToken, addToolCall } = getState();
    appendToken('Reading ');
    appendToken('the file...');

    // One streaming agent message.
    expect(getState().messages.filter(m => m.role === 'agent')).toHaveLength(1);

    addToolCall('tc-1', 'read_file', '{"path":"a.kt"}', 'RUNNING');

    const state = getState();
    // Still exactly one agent message — not two. Before the fix, addToolCall
    // pushed a second agent message with the stream text, and the
    // StreamingMessage component also rendered the same text, causing
    // visible duplication.
    expect(state.messages.filter(m => m.role === 'agent')).toHaveLength(1);
    expect(state.messages.filter(m => m.role === 'agent')[0]!.content).toBe('Reading the file...');
    // activeStream cleared → caret off on the streaming message.
    expect(state.activeStream).toBeNull();
    // Tool call landed in activeToolCalls.
    expect(state.activeToolCalls.size).toBe(1);
  });

  it('non-streaming messages keep the SAME object reference across token updates', () => {
    // This is the crucial contract for React.memo skipping re-renders of
    // unchanged messages. `appendToken` uses `.map(m => m.id === id ? { ...m, content } : m)`
    // so only the streaming message gets a new object reference; others
    // stay identity-stable.
    const { addMessage, appendToken } = getState();
    addMessage('user', 'First user question');
    const userMsgBefore = getState().messages[0]!;

    appendToken('Starting agent response');
    appendToken(' with more tokens');
    appendToken(' and more still.');

    const userMsgAfter = getState().messages[0]!;
    // Exact object identity — not just deep equality. If this fails,
    // React.memo cannot skip re-rendering the user message on every token,
    // which quietly tanks streaming performance.
    expect(userMsgAfter).toBe(userMsgBefore);
  });
});
