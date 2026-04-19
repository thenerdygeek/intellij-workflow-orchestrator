/**
 * Real-DOM scenario tests that simulate the actual bugs a user hits:
 *
 * Scenario 1 — Dropdown-select path:
 *   • User types `#`, selects a ticket from the dropdown
 *   • EXPECT: chip inserted with status='valid' AND glow animation fires
 *
 * Scenario 2 — Type-and-space path:
 *   • User types `#PROJ-123 ` (space terminates trigger)
 *   • Auto-chip inserted as 'pending'
 *   • validateTicket callback resolves valid
 *   • EXPECT: chip transitions to valid AND glow fires via updateChipStatus
 *
 * Scenario 3 — First-message bubble rendering:
 *   • startSession called with mentions
 *   • EXPECT: firstMessage in store carries the mentions (not dropped)
 *
 * Scenario 4 — getMentions after dropdown select:
 *   • After a dropdown-selected chip is in the DOM
 *   • EXPECT: getMentions() returns the mention (not empty)
 *   • This is the pipeline that feeds sendMessageWithMentions
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, act } from '@testing-library/react';
import { createRef } from 'react';
import { RichInput, type RichInputHandle } from '../components/input/RichInput';

// ─── Scenario 1 + 2: glow animation on valid chip ──────────────────────────

describe('Ticket chip glow', () => {
  let container: HTMLElement;

  beforeEach(() => {
    // Clear any prior style tags so the glow keyframe injection state is reset-ish.
    // (The module caches glowStyleInjected; harmless between runs.)
  });

  afterEach(() => {
    // Clean up lingering chips between tests
    document.body.innerHTML = '';
  });

  it('Scenario 1 — dropdown select: insertChip with status=valid triggers glow', () => {
    const ref = createRef<RichInputHandle>();
    const { container: c } = render(
      <RichInput ref={ref} onChange={() => {}} onSend={() => {}} />
    );
    container = c;

    // Focus the contenteditable and seed a `#` trigger character the way the real
    // RichInput change handler would have, so insertChip can find it.
    const editor = container.querySelector('[contenteditable="true"]') as HTMLElement;
    editor.focus();
    editor.textContent = '#';

    // Place the cursor after the `#`
    const range = document.createRange();
    const textNode = editor.firstChild!;
    range.setStart(textNode, 1);
    range.setEnd(textNode, 1);
    const sel = window.getSelection()!;
    sel.removeAllRanges();
    sel.addRange(range);

    // Insert chip as 'valid' (exactly what handleTicketSelect does)
    act(() => {
      ref.current!.insertChip(
        { type: 'ticket', label: 'PROJ-100', path: 'PROJ-100' },
        '#',
        'valid'
      );
    });

    const chip = container.querySelector<HTMLElement>('[data-mention-label="PROJ-100"]');
    expect(chip).not.toBeNull();
    expect(chip!.dataset.chipStatus).toBe('valid');

    // The glow animation must be assigned on insert (Bug B fix)
    expect(chip!.style.animation).toContain('chip-success-glow');
  });

  it('Scenario 1 — dropdown select with @file mention: no glow (non-ticket chips don\'t glow)', () => {
    const ref = createRef<RichInputHandle>();
    const { container: c } = render(
      <RichInput ref={ref} onChange={() => {}} onSend={() => {}} />
    );

    const editor = c.querySelector('[contenteditable="true"]') as HTMLElement;
    editor.focus();
    editor.textContent = '@';
    const range = document.createRange();
    range.setStart(editor.firstChild!, 1);
    range.setEnd(editor.firstChild!, 1);
    window.getSelection()!.removeAllRanges();
    window.getSelection()!.addRange(range);

    act(() => {
      ref.current!.insertChip(
        { type: 'file', label: 'App.tsx', path: 'src/App.tsx' },
        '@',
        'valid' // status is ignored for non-ticket chips
      );
    });

    const chip = c.querySelector<HTMLElement>('[data-mention-label="App.tsx"]');
    expect(chip).not.toBeNull();
    // File chips should NOT glow — only tickets do
    expect(chip!.style.animation).toBe('');
  });

  it('Scenario 2 — type-and-space: updateChipStatus transitions pending→valid and glows', () => {
    const ref = createRef<RichInputHandle>();
    const { container: c } = render(
      <RichInput ref={ref} onChange={() => {}} onSend={() => {}} />
    );

    const editor = c.querySelector('[contenteditable="true"]') as HTMLElement;
    editor.focus();
    editor.textContent = '#';
    const range = document.createRange();
    range.setStart(editor.firstChild!, 1);
    range.setEnd(editor.firstChild!, 1);
    window.getSelection()!.removeAllRanges();
    window.getSelection()!.addRange(range);

    // Step 1: auto-chip inserted as pending (what handleRichInputChange does)
    act(() => {
      ref.current!.insertChip(
        { type: 'ticket', label: 'PROJ-200', path: 'PROJ-200' },
        '#',
        'pending'
      );
    });

    const chip = c.querySelector<HTMLElement>('[data-mention-label="PROJ-200"]');
    expect(chip!.dataset.chipStatus).toBe('pending');
    // Pending chips should NOT glow — only valid ones do
    expect(chip!.style.animation).toBe('');

    // Step 2: validation returns valid → updateChipStatus triggers glow
    act(() => {
      ref.current!.updateChipStatus('PROJ-200', 'valid', 'PROJ-200: Fix login');
    });

    expect(chip!.dataset.chipStatus).toBe('valid');
    expect(chip!.style.animation).toContain('chip-success-glow');
    expect(chip!.title).toBe('PROJ-200: Fix login');
  });
});

// ─── Scenario 4: getMentions after dropdown select ──────────────────────────

describe('getMentions after insertChip', () => {
  afterEach(() => { document.body.innerHTML = ''; });

  it('Scenario 4 — dropdown-selected ticket flows through getMentions()', () => {
    const ref = createRef<RichInputHandle>();
    const { container: c } = render(
      <RichInput ref={ref} onChange={() => {}} onSend={() => {}} />
    );

    const editor = c.querySelector('[contenteditable="true"]') as HTMLElement;
    editor.focus();
    editor.textContent = '#';
    const range = document.createRange();
    range.setStart(editor.firstChild!, 1);
    range.setEnd(editor.firstChild!, 1);
    window.getSelection()!.removeAllRanges();
    window.getSelection()!.addRange(range);

    act(() => {
      ref.current!.insertChip(
        { type: 'ticket', label: 'PROJ-300', path: 'PROJ-300', icon: 'In Progress' },
        '#',
        'valid'
      );
    });

    // This is exactly what handleSend calls:
    const mentions = ref.current!.getMentions();
    expect(mentions).toHaveLength(1);
    expect(mentions[0]).toMatchObject({
      type: 'ticket',
      label: 'PROJ-300',
      path: 'PROJ-300',
    });

    // getText() returns text with # prefix embedded for chips (so LLM sees it)
    const text = ref.current!.getText();
    expect(text).toContain('#PROJ-300');
  });

  it('Scenario 4 — invalid chip is excluded from getMentions', () => {
    const ref = createRef<RichInputHandle>();
    const { container: c } = render(
      <RichInput ref={ref} onChange={() => {}} onSend={() => {}} />
    );

    const editor = c.querySelector('[contenteditable="true"]') as HTMLElement;
    editor.focus();
    editor.textContent = '#';
    const range = document.createRange();
    range.setStart(editor.firstChild!, 1);
    range.setEnd(editor.firstChild!, 1);
    window.getSelection()!.removeAllRanges();
    window.getSelection()!.addRange(range);

    act(() => {
      ref.current!.insertChip(
        { type: 'ticket', label: 'BAD-1', path: 'BAD-1' },
        '#',
        'pending'
      );
    });

    act(() => {
      ref.current!.updateChipStatus('BAD-1', 'invalid');
    });

    // Invalid ticket → chip stays as strikethrough text, NOT returned as a mention
    const mentions = ref.current!.getMentions();
    expect(mentions).toHaveLength(0);
  });
});

// ─── Scenario 3: store.startSession attaches mentions to firstMessage ───────

describe('chatStore.startSession preserves mentions', () => {
  it('Scenario 3 — first message with mentions renders via firstMessage.mentions (Bug A)', async () => {
    // Dynamic import so the store is fresh per test
    vi.resetModules();
    const { useChatStore } = await import('../stores/chatStore');

    const mention = { type: 'ticket' as const, label: 'PROJ-400', path: 'PROJ-400' };
    useChatStore.getState().startSession('#PROJ-400', [mention]);

    const messages = useChatStore.getState().messages;
    expect(messages).toHaveLength(1);

    const first = messages[0]!;
    expect(first.say).toBe('USER_MESSAGE');
    expect(first.text).toBe('#PROJ-400');
    // Bug A: this field was missing before the fix — resulted in literal "#PROJ-400" in bubble
    expect(first.mentions).toEqual([mention]);
  });

  it('Scenario 3 — first message with no mentions omits the field (no regression)', async () => {
    vi.resetModules();
    const { useChatStore } = await import('../stores/chatStore');

    useChatStore.getState().startSession('plain question');

    const messages = useChatStore.getState().messages;
    expect(messages[0]!.text).toBe('plain question');
    expect(messages[0]!.mentions).toBeUndefined();
  });

  it('Scenario 3 — empty mentions array omits the field (same semantics as no mentions)', async () => {
    vi.resetModules();
    const { useChatStore } = await import('../stores/chatStore');

    useChatStore.getState().startSession('plain question', []);

    const messages = useChatStore.getState().messages;
    expect(messages[0]!.mentions).toBeUndefined();
  });
});

// ─── Combined scenario: full pipeline ───────────────────────────────────────

describe('Full dropdown-select → send pipeline', () => {
  afterEach(() => { document.body.innerHTML = ''; });

  it('chip is valid, glows, and mention flows into getMentions for sendMessageWithMentions', () => {
    const ref = createRef<RichInputHandle>();
    const { container: c } = render(
      <RichInput ref={ref} onChange={() => {}} onSend={() => {}} />
    );

    const editor = c.querySelector('[contenteditable="true"]') as HTMLElement;
    editor.focus();
    editor.textContent = '#';
    const range = document.createRange();
    range.setStart(editor.firstChild!, 1);
    range.setEnd(editor.firstChild!, 1);
    window.getSelection()!.removeAllRanges();
    window.getSelection()!.addRange(range);

    // Step 1: user selects PROJ-500 from dropdown
    act(() => {
      ref.current!.insertChip(
        { type: 'ticket', label: 'PROJ-500', path: 'PROJ-500', icon: 'In Progress' },
        '#',
        'valid'
      );
    });

    // Step 2: verify glow fired
    const chip = c.querySelector<HTMLElement>('[data-mention-label="PROJ-500"]');
    expect(chip!.style.animation).toContain('chip-success-glow');
    expect(chip!.dataset.chipStatus).toBe('valid');

    // Step 3: user presses send — handleSend collects text + mentions
    const text = ref.current!.getText();
    const mentions = ref.current!.getMentions();

    // This is the routing rule in chatStore.sendMessage that chooses
    // sendMessageWithMentions vs plain sendMessage
    expect(mentions.length > 0).toBe(true);

    // The mention has the exact shape Kotlin expects (label/path, not name/value)
    expect(mentions[0]).toEqual({
      type: 'ticket',
      label: 'PROJ-500',
      path: 'PROJ-500',
      icon: 'In Progress',
    });

    // getText preserves the #LABEL marker so splitContentWithMentions
    // in the user bubble can locate and render a chip
    expect(text).toContain('#PROJ-500');
  });
});
