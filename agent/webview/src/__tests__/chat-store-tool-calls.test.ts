/**
 * Scenario tests for tool call state in the chat store.
 *
 * These tests drive `useChatStore` directly (no React) and assert the shape
 * of `activeToolCalls` and `messages` after each action. They exist to lock
 * in correct behavior for every way the agent can surface tool calls in the
 * UI, including the bugs that motivated this file:
 *
 * - **Bug 1 regression** — cross-turn XML tool ID collision. Before the fix,
 *   turn 1's `xmltool_1` and turn 2's `xmltool_1` collided in the store's
 *   `activeToolCalls` map and the earlier tool's UI card silently vanished.
 *   The store now runs a defense-in-depth check: if an incoming tool call
 *   uses an existing id for a DIFFERENT tool name, it's rekeyed to a unique
 *   slot so both entries survive. The root cause is fixed on the Kotlin
 *   side (`SourcegraphChatClient.xmlToolIdCounter`); this guard catches
 *   future regressions regardless of source.
 *
 * - **Tool call / stream interleaving** — a tool call arriving mid-stream
 *   must not duplicate the streaming text into a second message. Under the
 *   unified streaming-message model, the text already lives in `messages`
 *   (updated in place), so auto-finalize on `addToolCall` only clears
 *   `activeStream` (caret off) without pushing a new message.
 *
 * - **First-token drain** — when a new text turn arrives after a completed
 *   tool chain, the active tool calls drain into a `tc-chain` system message
 *   BEFORE the streaming placeholder is created, so the chat flow stays
 *   chronological: …prior message → tc-chain → streaming text.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { useChatStore } from '@/stores/chatStore';
import type { ToolCall } from '@/bridge/types';

// ── Helpers ──

function getState() {
  return useChatStore.getState();
}

function reset() {
  useChatStore.getState().clearChat();
}

function toolCallsArray(): ToolCall[] {
  return Array.from(getState().activeToolCalls.values());
}

// ── Tests ──

describe('chatStore — tool call UI state', () => {
  beforeEach(reset);

  it('adds a single tool call to activeToolCalls keyed by id', () => {
    const { addToolCall } = getState();
    addToolCall('tc-1', 'glob_files', '{"pattern":"**/*.kt"}', 'RUNNING');

    const arr = toolCallsArray();
    expect(arr).toHaveLength(1);
    expect(arr[0]).toMatchObject({
      id: 'tc-1',
      name: 'glob_files',
      status: 'RUNNING',
    });
  });

  it('updates a running tool call in place when the result arrives (same id)', () => {
    const { addToolCall, updateToolCall } = getState();
    addToolCall('tc-1', 'glob_files', '{"pattern":"**/*.kt"}', 'RUNNING');
    updateToolCall('glob_files', 'COMPLETED', 'found 3 files', 12, 'found 3 files', undefined, 'tc-1');

    const arr = toolCallsArray();
    expect(arr).toHaveLength(1);
    expect(arr[0]).toMatchObject({
      id: 'tc-1',
      name: 'glob_files',
      status: 'COMPLETED',
      result: 'found 3 files',
      durationMs: 12,
    });
  });

  it('keeps every tool call visible when 4 parallel calls arrive with distinct ids', () => {
    const { addToolCall } = getState();
    addToolCall('tc-1', 'read_file', '{"path":"a.kt"}', 'RUNNING');
    addToolCall('tc-2', 'read_file', '{"path":"b.kt"}', 'RUNNING');
    addToolCall('tc-3', 'read_file', '{"path":"c.kt"}', 'RUNNING');
    addToolCall('tc-4', 'read_file', '{"path":"d.kt"}', 'RUNNING');

    const arr = toolCallsArray();
    expect(arr.map(tc => tc.id)).toEqual(['tc-1', 'tc-2', 'tc-3', 'tc-4']);
    // All four cards render with the same tool name but different file paths.
    expect(arr.every(tc => tc.name === 'read_file')).toBe(true);
  });

  it('routes parallel updateToolCall results to the correct card by toolCallId', () => {
    const { addToolCall, updateToolCall } = getState();
    addToolCall('tc-1', 'read_file', '{"path":"a.kt"}', 'RUNNING');
    addToolCall('tc-2', 'read_file', '{"path":"b.kt"}', 'RUNNING');
    addToolCall('tc-3', 'read_file', '{"path":"c.kt"}', 'RUNNING');

    // Completions arrive out of order — tc-2 finishes first.
    updateToolCall('read_file', 'COMPLETED', 'body B', 5, 'body B', undefined, 'tc-2');
    updateToolCall('read_file', 'COMPLETED', 'body A', 8, 'body A', undefined, 'tc-1');
    updateToolCall('read_file', 'ERROR', 'denied', 3, 'denied', undefined, 'tc-3');

    const byId = new Map(toolCallsArray().map(tc => [tc.id, tc]));
    expect(byId.get('tc-1')?.result).toBe('body A');
    expect(byId.get('tc-1')?.status).toBe('COMPLETED');
    expect(byId.get('tc-2')?.result).toBe('body B');
    expect(byId.get('tc-2')?.status).toBe('COMPLETED');
    expect(byId.get('tc-3')?.status).toBe('ERROR');
    expect(byId.get('tc-3')?.result).toBe('denied');
  });

  it('defense-in-depth: colliding id for a DIFFERENT tool rekeys the new entry and preserves the original', () => {
    // This is the direct Bug 1 regression guard. Even if the Kotlin side
    // regresses its id generator to a per-response counter, the JS store
    // must not silently overwrite a prior tool's entry.
    const { addToolCall } = getState();
    addToolCall('xmltool_1', 'glob_files', '{"pattern":"**/*.kt"}', 'RUNNING');
    addToolCall('xmltool_1', 'read_file', '{"path":"a.kt"}', 'RUNNING');

    const arr = toolCallsArray();
    // BOTH tool cards must still be in the map — glob at its original key,
    // read_file under a derived unique key.
    expect(arr).toHaveLength(2);
    const names = arr.map(tc => tc.name).sort();
    expect(names).toEqual(['glob_files', 'read_file']);

    // The original entry keeps its original id.
    const glob = arr.find(tc => tc.name === 'glob_files')!;
    expect(glob.id).toBe('xmltool_1');

    // The new entry was rekeyed to something derived but not equal.
    const readFile = arr.find(tc => tc.name === 'read_file')!;
    expect(readFile.id).not.toBe('xmltool_1');
    expect(readFile.id).toContain('xmltool_1'); // derivation keeps original prefix for debuggability
  });

  it('same-id same-name is treated as a legitimate update, not a collision', () => {
    // Legitimate case: addToolCall('tc-1', 'read_file', RUNNING) followed by
    // addToolCall('tc-1', 'read_file', ...) with an updated status. This is
    // NOT a collision — same tool, same id, just a status transition.
    const { addToolCall } = getState();
    addToolCall('tc-1', 'read_file', '{"path":"a.kt"}', 'RUNNING');
    addToolCall('tc-1', 'read_file', '{"path":"a.kt"}', 'COMPLETED');

    const arr = toolCallsArray();
    expect(arr).toHaveLength(1);
    expect(arr[0]).toMatchObject({
      id: 'tc-1',
      name: 'read_file',
      status: 'COMPLETED',
    });
  });

  it('Bug 1 scenario end-to-end — glob(xmltool_1), then 4 read_files (xmltool_1..4): glob survives', () => {
    // The exact reproduction the user reported:
    // 1. Agent turn 1 emits <glob_files> → parser synthesizes xmltool_1.
    // 2. Agent turn 2 emits 4× <read_file> → parser synthesizes xmltool_1..4
    //    (buggy per-response counter resets to 1 at the start of turn 2).
    // 3. JS store receives xmltool_1 for read_file → MUST NOT overwrite glob.
    const { addToolCall, updateToolCall } = getState();

    // Turn 1: glob runs and completes.
    addToolCall('xmltool_1', 'glob_files', '{"pattern":"**/*.kt"}', 'RUNNING');
    updateToolCall('glob_files', 'COMPLETED', '3 matches', 10, '3 matches', undefined, 'xmltool_1');

    // Turn 2: 4 parallel read_files, buggy ids.
    addToolCall('xmltool_1', 'read_file', '{"path":"a.kt"}', 'RUNNING');
    addToolCall('xmltool_2', 'read_file', '{"path":"b.kt"}', 'RUNNING');
    addToolCall('xmltool_3', 'read_file', '{"path":"c.kt"}', 'RUNNING');
    addToolCall('xmltool_4', 'read_file', '{"path":"d.kt"}', 'RUNNING');

    const arr = toolCallsArray();
    // 5 cards visible: 1 glob (completed) + 4 read_files (running).
    expect(arr).toHaveLength(5);

    const byName = new Map<string, ToolCall[]>();
    for (const tc of arr) {
      const list = byName.get(tc.name) ?? [];
      list.push(tc);
      byName.set(tc.name, list);
    }
    expect(byName.get('glob_files')).toHaveLength(1);
    expect(byName.get('read_file')).toHaveLength(4);

    // The glob's status must still be COMPLETED (not overwritten to RUNNING).
    expect(byName.get('glob_files')![0]!.status).toBe('COMPLETED');
    expect(byName.get('glob_files')![0]!.result).toBe('3 matches');
  });

  it('auto-finalize on first-token drain: completed tool chain becomes a tc-chain system message', () => {
    // Scenario: agent runs a tool, then streams a text response. The first
    // text token must drain the completed tool chain into a `tc-chain`
    // message BEFORE creating the streaming placeholder, so the visual
    // order is: prior messages → tc-chain → streaming text.
    const { addToolCall, updateToolCall, appendToken } = getState();

    addToolCall('tc-1', 'glob_files', '{"pattern":"**"}', 'RUNNING');
    updateToolCall('glob_files', 'COMPLETED', 'ok', 5, 'ok', undefined, 'tc-1');

    // First text token of the next response.
    appendToken('Here is what I found:');

    const state = getState();
    // activeToolCalls should be drained.
    expect(state.activeToolCalls.size).toBe(0);
    // messages should now contain the tc-chain AND the streaming placeholder.
    const tcChainMessages = state.messages.filter(m => m.toolChain != null);
    expect(tcChainMessages).toHaveLength(1);
    expect(tcChainMessages[0]!.toolChain).toHaveLength(1);
    expect(tcChainMessages[0]!.toolChain![0]!.name).toBe('glob_files');
    expect(tcChainMessages[0]!.toolChain![0]!.status).toBe('COMPLETED');

    // The streaming placeholder is an agent message that comes AFTER the tc-chain.
    const agentMessages = state.messages.filter(m => m.role === 'agent');
    expect(agentMessages).toHaveLength(1);
    expect(agentMessages[0]!.content).toBe('Here is what I found:');

    const tcChainIdx = state.messages.findIndex(m => m.toolChain != null);
    const agentIdx = state.messages.findIndex(m => m.role === 'agent');
    expect(tcChainIdx).toBeLessThan(agentIdx);
  });

  it('tool call arriving mid-stream auto-finalizes the stream WITHOUT duplicating text', () => {
    // Before the fix, `addToolCall` pushed a new agent message into
    // `messages` with the current stream text. Under the unified streaming
    // model the placeholder is already in `messages`, so the auto-finalize
    // must only clear `activeStream` — pushing a second message would
    // duplicate the text in the UI.
    const { appendToken, addToolCall } = getState();

    appendToken('Let me check ');
    appendToken('the files. ');

    // Snapshot: one streaming agent message in messages.
    let state = getState();
    expect(state.messages.filter(m => m.role === 'agent')).toHaveLength(1);
    expect(state.messages.filter(m => m.role === 'agent')[0]!.content).toBe('Let me check the files. ');

    // Tool call arrives mid-stream.
    addToolCall('tc-1', 'read_file', '{"path":"a.kt"}', 'RUNNING');

    state = getState();
    // activeStream cleared (caret off on the streaming message).
    expect(state.activeStream).toBeNull();
    // STILL exactly one agent message — not two.
    const agentMessages = state.messages.filter(m => m.role === 'agent');
    expect(agentMessages).toHaveLength(1);
    expect(agentMessages[0]!.content).toBe('Let me check the files. ');
    // New tool call is in activeToolCalls.
    expect(state.activeToolCalls.size).toBe(1);
    expect(state.activeToolCalls.get('tc-1')?.name).toBe('read_file');
  });
});
