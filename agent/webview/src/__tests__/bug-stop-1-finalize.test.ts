/**
 * BUG-STOP-1 F1 — a tool call that is still RUNNING/PENDING when the active chain is
 * drained (session complete / cancel / stream end) must be finalized as a TERMINAL
 * status, never persisted verbatim as RUNNING. A finalized-but-RUNNING card spins its
 * spinner forever and its elapsed timer runs away (the orphaned-card symptom).
 *
 * Defense-in-depth: this holds even if the backend never emits its own terminal event.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { useChatStore } from '@/stores/chatStore';

function resetStore() {
  useChatStore.getState().clearChat();
}

beforeEach(resetStore);

describe('BUG-STOP-1 F1 — drained RUNNING tool calls are finalized terminal', () => {
  it('finalizeToolChain coerces a still-RUNNING tool to CANCELLED', () => {
    const store = useChatStore.getState();
    store.addToolCall('tc-run', 'run_command', '{"command":"grep -r x ."}', 'RUNNING');

    // No updateToolCall — the tool never completed (e.g. whole-loop cancel).
    store.finalizeToolChain();

    const msg = useChatStore.getState().messages.find(
      m => m.say === 'TOOL' && m.toolCallData?.toolCallId === 'tc-run',
    );
    expect(msg).toBeDefined();
    expect(msg!.toolCallData!.status).toBe('CANCELLED');
    expect(msg!.toolCallData!.status).not.toBe('RUNNING');
  });

  it('completeSession coerces a still-RUNNING tool to CANCELLED', () => {
    const store = useChatStore.getState();
    store.addToolCall('tc-run2', 'run_command', '{"command":"sleep 999"}', 'RUNNING');

    store.completeSession({
      status: 'COMPLETED',
      tokensUsed: 0,
      durationMs: 0,
      iterations: 0,
      filesModified: [],
    });

    const msg = useChatStore.getState().messages.find(
      m => m.say === 'TOOL' && m.toolCallData?.toolCallId === 'tc-run2',
    );
    expect(msg).toBeDefined();
    expect(msg!.toolCallData!.status).toBe('CANCELLED');
  });

  it('a PENDING tool is also coerced to CANCELLED on drain', () => {
    const store = useChatStore.getState();
    store.addToolCall('tc-pending', 'read_file', '{"path":"a.kt"}', 'PENDING');

    store.finalizeToolChain();

    const msg = useChatStore.getState().messages.find(
      m => m.say === 'TOOL' && m.toolCallData?.toolCallId === 'tc-pending',
    );
    expect(msg!.toolCallData!.status).toBe('CANCELLED');
  });

  it('already-terminal statuses pass through unchanged on drain', () => {
    const store = useChatStore.getState();
    store.addToolCall('tc-done', 'read_file', '{"path":"b.kt"}', 'RUNNING');
    store.updateToolCall('read_file', 'COMPLETED', 'ok', 42, 'file contents', undefined, 'tc-done');

    store.finalizeToolChain();

    const msg = useChatStore.getState().messages.find(
      m => m.say === 'TOOL' && m.toolCallData?.toolCallId === 'tc-done',
    );
    expect(msg!.toolCallData!.status).toBe('COMPLETED');
  });
});
