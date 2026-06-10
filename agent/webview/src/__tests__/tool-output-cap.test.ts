/**
 * P2-17 — appendToolOutput + finalizeToolChain output cap
 *
 * At FINALIZE, stored toolCallData.output must be capped to a UI cap
 * (20_000 chars: head 4K + "…[truncated]…" + tail 16K).
 * The live streaming buffer in toolOutputStreams is untouched.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { useChatStore } from '@/stores/chatStore';

// Expose the cap constant so the tests remain DRY if the cap changes.
import { TOOL_OUTPUT_UI_CAP, TOOL_OUTPUT_HEAD, TOOL_OUTPUT_TAIL } from '@/stores/chatStore';

function resetStore() {
  const s = useChatStore.getState();
  s.clearChat();
}

beforeEach(resetStore);

describe('P2-17 — tool output UI cap at finalize', () => {
  it('short output is stored verbatim', () => {
    const store = useChatStore.getState();
    store.addToolCall('tc1', 'run_command', '{"command":"echo hi"}', 'RUNNING');
    store.appendToolOutput('tc1', 'hello world\n');
    store.updateToolCall('run_command', 'COMPLETED', '', 100, 'hello world\n', undefined, 'tc1');
    store.finalizeToolChain();

    const msg = useChatStore.getState().messages.find(
      m => m.say === 'TOOL' && m.toolCallData?.toolCallId === 'tc1',
    );
    expect(msg).toBeDefined();
    expect(msg!.toolCallData!.output).toBe('hello world\n');
  });

  it('output > TOOL_OUTPUT_UI_CAP is truncated with head+tail', () => {
    const store = useChatStore.getState();
    // Build a string longer than the cap
    const bigOutput = 'A'.repeat(TOOL_OUTPUT_HEAD) + 'B'.repeat(TOOL_OUTPUT_TAIL + 1000);
    expect(bigOutput.length).toBeGreaterThan(TOOL_OUTPUT_UI_CAP);

    store.addToolCall('tc2', 'run_command', '{"command":"big"}', 'RUNNING');
    store.updateToolCall('run_command', 'COMPLETED', '', 200, bigOutput, undefined, 'tc2');
    store.finalizeToolChain();

    const msg = useChatStore.getState().messages.find(
      m => m.say === 'TOOL' && m.toolCallData?.toolCallId === 'tc2',
    );
    expect(msg).toBeDefined();
    const stored = msg!.toolCallData!.output!;
    // Must be shorter than the original
    expect(stored.length).toBeLessThan(bigOutput.length);
    // Must contain the truncation notice
    expect(stored).toContain('[truncated, full output on disk]');
    // Must start with the head of the original
    expect(stored.startsWith('A'.repeat(TOOL_OUTPUT_HEAD))).toBe(true);
    // Must end with the tail of the original
    const tailContent = 'B'.repeat(TOOL_OUTPUT_TAIL + 1000).slice(-(TOOL_OUTPUT_TAIL));
    expect(stored.endsWith(tailContent)).toBe(true);
    // Total stored length is bounded
    expect(stored.length).toBeLessThanOrEqual(TOOL_OUTPUT_UI_CAP + 100); // small margin for the notice string
  });

  it('streaming buffer in toolOutputStreams is cleared after finalize (no leak)', () => {
    const store = useChatStore.getState();
    store.addToolCall('tc3', 'run_command', '{"command":"x"}', 'RUNNING');
    store.appendToolOutput('tc3', 'chunk1');
    store.appendToolOutput('tc3', 'chunk2');
    store.updateToolCall('run_command', 'COMPLETED', '', 50, undefined, undefined, 'tc3');
    store.finalizeToolChain();

    const streams = useChatStore.getState().toolOutputStreams;
    expect(Object.keys(streams)).toHaveLength(0);
  });

  it('exactly-at-cap output is NOT truncated', () => {
    const store = useChatStore.getState();
    const atCap = 'X'.repeat(TOOL_OUTPUT_UI_CAP);
    store.addToolCall('tc4', 'run_command', '{}', 'RUNNING');
    store.updateToolCall('run_command', 'COMPLETED', '', 10, atCap, undefined, 'tc4');
    store.finalizeToolChain();

    const msg = useChatStore.getState().messages.find(
      m => m.say === 'TOOL' && m.toolCallData?.toolCallId === 'tc4',
    );
    expect(msg!.toolCallData!.output).toBe(atCap);
    expect(msg!.toolCallData!.output).not.toContain('[truncated');
  });

  it('appendToken drain (the DOMINANT commit path) caps the streamed output', () => {
    // Real sessions commit intermediate tool chains via appendToken: when the
    // next iteration's first token arrives, activeToolCalls drain into
    // messages[]. The cap must apply there too, not only at finalizeToolChain.
    const store = useChatStore.getState();
    const bigStream = 'A'.repeat(TOOL_OUTPUT_HEAD) + 'B'.repeat(TOOL_OUTPUT_TAIL + 5000);
    expect(bigStream.length).toBeGreaterThan(TOOL_OUTPUT_UI_CAP);

    store.addToolCall('tc5', 'run_command', '{"command":"big"}', 'RUNNING');
    store.appendToolOutput('tc5', bigStream);
    // No explicit output param — the drain must fall back to the stream buffer.
    store.updateToolCall('run_command', 'COMPLETED', '', 300, undefined, undefined, 'tc5');
    // Next iteration's first token triggers the drain.
    store.appendToken('next');

    const msg = useChatStore.getState().messages.find(
      m => m.say === 'TOOL' && m.toolCallData?.toolCallId === 'tc5',
    );
    expect(msg).toBeDefined();
    const stored = msg!.toolCallData!.output!;
    expect(stored.length).toBeLessThan(bigStream.length);
    expect(stored).toContain('[truncated, full output on disk]');
    expect(stored.startsWith('A'.repeat(TOOL_OUTPUT_HEAD))).toBe(true);
    expect(stored.endsWith('B'.repeat(TOOL_OUTPUT_TAIL))).toBe(true);
    expect(stored.length).toBeLessThanOrEqual(TOOL_OUTPUT_UI_CAP + 100);
    // Drain cleared the active tool call + its stream buffer.
    expect(useChatStore.getState().activeToolCalls.size).toBe(0);
    expect(Object.keys(useChatStore.getState().toolOutputStreams)).toHaveLength(0);
  });
});
