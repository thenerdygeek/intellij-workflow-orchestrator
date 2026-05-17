import { describe, it, expect, beforeEach } from 'vitest';
import { useChatStore, selectActiveSubAgents } from '../chatStore';
import type { UiMessage } from '@/bridge/types';

describe('chatStore legacy subagentData hydration', () => {
  beforeEach(() => {
    useChatStore.setState({ messages: [] } as any);
  });

  it('upgrades legacy UiMessageSubagentData shape to SubAgentState on hydrate', () => {
    // Legacy persisted shape (pre-Phase 4): description/iterations/agentType, no label/iteration.
    const legacyMessage = {
      ts: 1000,
      say: 'SUBAGENT',
      text: '',
      subagentData: {
        agentId: 'a-legacy-1',
        description: 'Review files',
        agentType: 'code-reviewer',
        iterations: 7,
        // NO label, NO iteration, NO activeToolChain, NO startedAt, NO messages, NO status
      },
    } as unknown as UiMessage;

    useChatStore.getState().hydrateFromUiMessages([legacyMessage]);

    const hydrated = useChatStore.getState().messages[0];
    expect(hydrated?.subagentData).toBeTruthy();
    const sd: any = hydrated!.subagentData;
    // Required fields of SubAgentState must all be populated with safe defaults.
    expect(sd.label).toBeDefined();
    // Label should be the description (legacy primary identity) optionally suffixed with type.
    expect(typeof sd.label).toBe('string');
    expect(sd.label).toContain('Review files');
    expect(sd.iteration).toBe(7);             // legacy `iterations` → new `iteration`
    expect(Array.isArray(sd.activeToolChain)).toBe(true);
    expect(Array.isArray(sd.messages)).toBe(true);
    expect(typeof sd.startedAt).toBe('number');
    expect(sd.status).toBe('COMPLETED');      // legacy sessions are terminal — no live run resumes
    // selectActiveSubAgents should find it.
    const map = selectActiveSubAgents(useChatStore.getState());
    expect(map.get('a-legacy-1')?.label).toContain('Review files');
  });

  it('leaves modern SubAgentState shape untouched', () => {
    const modernMessage = {
      ts: 2000,
      say: 'SUBAGENT',
      text: '',
      subagentData: {
        agentId: 'a-modern-1',
        label: 'New Run (coder)',
        status: 'COMPLETED',
        startedAt: 1700000000000,
        iteration: 3,
        tokensUsed: 1234,
        messages: [],
        activeToolChain: [],
        streamingText: null,
        streamingThinkingText: null,
      },
    } as unknown as UiMessage;

    useChatStore.getState().hydrateFromUiMessages([modernMessage]);

    const sd: any = useChatStore.getState().messages[0]!.subagentData!;
    expect(sd.label).toBe('New Run (coder)');
    expect(sd.iteration).toBe(3);
    expect(sd.tokensUsed).toBe(1234);
    expect(sd.startedAt).toBe(1700000000000);
  });

  it('handles missing optional legacy fields gracefully', () => {
    const minimalLegacyMessage = {
      ts: 3000,
      say: 'SUBAGENT',
      text: '',
      subagentData: {
        agentId: 'a-min-1',
        // Only agentId present — everything else absent.
      },
    } as unknown as UiMessage;

    useChatStore.getState().hydrateFromUiMessages([minimalLegacyMessage]);
    const sd: any = useChatStore.getState().messages[0]!.subagentData!;
    expect(sd.agentId).toBe('a-min-1');
    expect(sd.label).toBeDefined();      // some safe default like 'Sub-agent'
    expect(sd.iteration).toBe(0);
    expect(Array.isArray(sd.messages)).toBe(true);
    expect(Array.isArray(sd.activeToolChain)).toBe(true);
  });

  it('does NOT crash extract* helpers in SubAgentView when label is set', () => {
    // Regression guard: legacy sessions used to crash because subAgent.label.match was called
    // on undefined. After hydration, .match must work.
    const legacy = {
      ts: 4000, say: 'SUBAGENT', text: '',
      subagentData: { agentId: 'a-1', description: 'Test', agentType: 'reviewer', iterations: 1 },
    } as unknown as UiMessage;
    useChatStore.getState().hydrateFromUiMessages([legacy]);
    const sd: any = useChatStore.getState().messages[0]!.subagentData!;
    // Calling .match like SubAgentView does — must not throw.
    expect(() => sd.label.match(/\(([^)]+)\)\s*$/)).not.toThrow();
  });
});

// Type-level contract test: ChatState.showApproval accepts originAgentId/originLabel.
// This is a compile-time check — the file would fail tsc if the signature is wrong.
describe('ChatState.showApproval interface signature', () => {
  it('accepts originAgentId and originLabel optional params', () => {
    const store = useChatStore.getState();
    // The following call must type-check (compile-time assertion).
    // If ChatState.showApproval's declaration is stale, tsc --noEmit will fail.
    store.showApproval(
      'edit_file',                    // toolName
      'MEDIUM',                       // riskLevel
      'Edit foo.kt',                  // description
      undefined,                       // metadata
      undefined,                       // diffContent
      undefined,                       // commandPreview
      false,                           // allowSessionApproval
      'agent-123',                     // originAgentId  ← new
      'code-reviewer',                 // originLabel    ← new
    );
    expect(true).toBe(true);  // we only care about compile-time check
  });
});
