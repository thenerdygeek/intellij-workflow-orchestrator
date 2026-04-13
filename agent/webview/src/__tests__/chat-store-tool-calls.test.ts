/**
 * Scenario tests for tool call state in the chat store.
 *
 * Contracts:
 * - Every tool call in `activeToolCalls` is keyed by a unique id.
 * - Parallel tool calls with distinct ids are all retained.
 * - Same-id + same-name is a legitimate status update.
 * - Same-id + different-name is a caller bug (Kotlin-side id scope); the
 *   store must rekey the incoming entry instead of silently overwriting.
 * - A completed tool chain drains into a `tc-chain` system message when the
 *   next text turn starts, preserving chronological order in the chat.
 * - A tool call arriving mid-stream must not duplicate the streaming text
 *   into a second message — the text already lives in `messages`.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import type { ToolCall } from '@/bridge/types';
import { chatState, resetChatStore, activeToolCallsArray } from './chat-store-test-utils';

describe('chatStore — tool call UI state', () => {
  beforeEach(resetChatStore);

  it('adds a single tool call to activeToolCalls keyed by id', () => {
    const { addToolCall } = chatState();
    addToolCall('tc-1', 'glob_files', '{"pattern":"**/*.kt"}', 'RUNNING');

    const arr = activeToolCallsArray();
    expect(arr).toHaveLength(1);
    expect(arr[0]).toMatchObject({
      id: 'tc-1',
      name: 'glob_files',
      status: 'RUNNING',
    });
  });

  it('updates a running tool call in place when the result arrives (same id)', () => {
    const { addToolCall, updateToolCall } = chatState();
    addToolCall('tc-1', 'glob_files', '{"pattern":"**/*.kt"}', 'RUNNING');
    updateToolCall('glob_files', 'COMPLETED', 'found 3 files', 12, 'found 3 files', undefined, 'tc-1');

    const arr = activeToolCallsArray();
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
    const { addToolCall } = chatState();
    addToolCall('tc-1', 'read_file', '{"path":"a.kt"}', 'RUNNING');
    addToolCall('tc-2', 'read_file', '{"path":"b.kt"}', 'RUNNING');
    addToolCall('tc-3', 'read_file', '{"path":"c.kt"}', 'RUNNING');
    addToolCall('tc-4', 'read_file', '{"path":"d.kt"}', 'RUNNING');

    const arr = activeToolCallsArray();
    expect(arr.map(tc => tc.id)).toEqual(['tc-1', 'tc-2', 'tc-3', 'tc-4']);
    // All four cards render with the same tool name but different file paths.
    expect(arr.every(tc => tc.name === 'read_file')).toBe(true);
  });

  it('routes parallel updateToolCall results to the correct card by toolCallId', () => {
    const { addToolCall, updateToolCall } = chatState();
    addToolCall('tc-1', 'read_file', '{"path":"a.kt"}', 'RUNNING');
    addToolCall('tc-2', 'read_file', '{"path":"b.kt"}', 'RUNNING');
    addToolCall('tc-3', 'read_file', '{"path":"c.kt"}', 'RUNNING');

    // Completions arrive out of order — tc-2 finishes first.
    updateToolCall('read_file', 'COMPLETED', 'body B', 5, 'body B', undefined, 'tc-2');
    updateToolCall('read_file', 'COMPLETED', 'body A', 8, 'body A', undefined, 'tc-1');
    updateToolCall('read_file', 'ERROR', 'denied', 3, 'denied', undefined, 'tc-3');

    const byId = new Map(activeToolCallsArray().map(tc => [tc.id, tc]));
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
    const { addToolCall } = chatState();
    addToolCall('xmltool_1', 'glob_files', '{"pattern":"**/*.kt"}', 'RUNNING');
    addToolCall('xmltool_1', 'read_file', '{"path":"a.kt"}', 'RUNNING');

    const arr = activeToolCallsArray();
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

  it('same-id same-name is treated as a legitimate status update, not a collision', () => {
    const { addToolCall } = chatState();
    addToolCall('tc-1', 'read_file', '{"path":"a.kt"}', 'RUNNING');
    addToolCall('tc-1', 'read_file', '{"path":"a.kt"}', 'COMPLETED');

    const arr = activeToolCallsArray();
    expect(arr).toHaveLength(1);
    expect(arr[0]).toMatchObject({
      id: 'tc-1',
      name: 'read_file',
      status: 'COMPLETED',
    });
  });

  it('cross-turn id collision: completed glob survives when the next turn reuses xmltool_1 for a different tool', () => {
    // Models a Kotlin-side id scope bug where a per-response counter resets
    // to 1 on every LLM call. The defense-in-depth rekey must keep every
    // prior completed tool card addressable.
    const { addToolCall, updateToolCall } = chatState();

    addToolCall('xmltool_1', 'glob_files', '{"pattern":"**/*.kt"}', 'RUNNING');
    updateToolCall('glob_files', 'COMPLETED', '3 matches', 10, '3 matches', undefined, 'xmltool_1');

    addToolCall('xmltool_1', 'read_file', '{"path":"a.kt"}', 'RUNNING');
    addToolCall('xmltool_2', 'read_file', '{"path":"b.kt"}', 'RUNNING');
    addToolCall('xmltool_3', 'read_file', '{"path":"c.kt"}', 'RUNNING');
    addToolCall('xmltool_4', 'read_file', '{"path":"d.kt"}', 'RUNNING');

    const arr = activeToolCallsArray();
    expect(arr).toHaveLength(5);

    const byName = new Map<string, ToolCall[]>();
    for (const tc of arr) {
      const list = byName.get(tc.name) ?? [];
      list.push(tc);
      byName.set(tc.name, list);
    }
    expect(byName.get('glob_files')).toHaveLength(1);
    expect(byName.get('read_file')).toHaveLength(4);

    expect(byName.get('glob_files')![0]!.status).toBe('COMPLETED');
    expect(byName.get('glob_files')![0]!.result).toBe('3 matches');
  });

  it('first-token drain: completed tool chain becomes individual TOOL messages before the streaming placeholder', () => {
    // Agent runs a tool, then streams a text response. The first text token
    // must drain the completed tool chain into individual UiMessage{say:'TOOL'}
    // entries BEFORE creating the streaming placeholder, so the visual
    // order is: prior messages -> tool messages -> streaming text.
    const { addToolCall, updateToolCall, appendToken } = chatState();

    addToolCall('tc-1', 'glob_files', '{"pattern":"**"}', 'RUNNING');
    updateToolCall('glob_files', 'COMPLETED', 'ok', 5, 'ok', undefined, 'tc-1');

    // First text token of the next response.
    appendToken('Here is what I found:');

    const state = chatState();
    // activeToolCalls should be drained.
    expect(state.activeToolCalls.size).toBe(0);
    // messages should now contain TOOL message(s) AND the streaming placeholder.
    const toolMessages = state.messages.filter(m => m.say === 'TOOL');
    expect(toolMessages).toHaveLength(1);
    expect(toolMessages[0]!.toolCallData!.toolName).toBe('glob_files');
    expect(toolMessages[0]!.toolCallData!.status).toBe('COMPLETED');

    // The streaming placeholder is a TEXT message that comes AFTER the tool messages.
    const textMessages = state.messages.filter(m => m.say === 'TEXT');
    expect(textMessages).toHaveLength(1);
    expect(textMessages[0]!.text).toBe('Here is what I found:');

    const toolIdx = state.messages.findIndex(m => m.say === 'TOOL');
    const textIdx = state.messages.findIndex(m => m.say === 'TEXT');
    expect(toolIdx).toBeLessThan(textIdx);
  });

  it('tool call arriving mid-stream clears the caret without duplicating the streaming text', () => {
    const { appendToken, addToolCall } = chatState();

    appendToken('Let me check ');
    appendToken('the files. ');

    let state = chatState();
    expect(state.messages.filter(m => m.say === 'TEXT')).toHaveLength(1);
    expect(state.messages.filter(m => m.say === 'TEXT')[0]!.text).toBe('Let me check the files. ');

    addToolCall('tc-1', 'read_file', '{"path":"a.kt"}', 'RUNNING');

    state = chatState();
    expect(state.activeStream).toBeNull();
    const textMessages = state.messages.filter(m => m.say === 'TEXT');
    expect(textMessages).toHaveLength(1);
    expect(textMessages[0]!.text).toBe('Let me check the files. ');
    expect(state.activeToolCalls.size).toBe(1);
    expect(state.activeToolCalls.get('tc-1')?.name).toBe('read_file');
  });
});
