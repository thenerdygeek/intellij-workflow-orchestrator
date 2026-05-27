/**
 * Regression for the double-mention bug (#3): two chips can share a label —
 * e.g. a pasted pending chip plus a dropdown-selected valid chip for the same
 * ticket. getMentions() must return that ticket only ONCE, or the send payload
 * carries duplicate mentions.
 */
import { describe, it, expect, afterEach } from 'vitest';
import { render, act } from '@testing-library/react';
import { createRef } from 'react';
import { RichInput, type RichInputHandle } from '../components/input/RichInput';
import type { ChipStatus } from '../components/input/RichInput';

afterEach(() => {
  document.body.innerHTML = '';
});

describe('getMentions deduplication', () => {
  it('returns a single mention when two chips share the same label', () => {
    const ref = createRef<RichInputHandle>();
    const { container } = render(<RichInput ref={ref} onChange={() => {}} onSend={() => {}} />);
    const editor = container.querySelector('[contenteditable="true"]') as HTMLElement;
    editor.focus();

    const insertWithTrigger = (status: ChipStatus) => {
      // Seed a `#` trigger char and put the caret right after it so insertChip
      // can find and replace it (mirrors the real change-handler flow).
      const triggerNode = document.createTextNode('#');
      editor.appendChild(triggerNode);
      const range = document.createRange();
      range.setStart(triggerNode, 1);
      range.collapse(true);
      const sel = window.getSelection()!;
      sel.removeAllRanges();
      sel.addRange(range);
      act(() => {
        ref.current!.insertChip({ type: 'ticket', label: 'PROJ-123', path: 'PROJ-123' }, '#', status);
      });
    };

    insertWithTrigger('pending'); // simulates the pasted chip
    insertWithTrigger('valid');   // simulates the dropdown-selected chip

    // Two chips are present in the DOM…
    expect(editor.querySelectorAll('[data-mention-label="PROJ-123"]').length).toBe(2);
    // …but getMentions dedups by label.
    const mentions = ref.current!.getMentions();
    expect(mentions.filter(m => m.label === 'PROJ-123')).toHaveLength(1);
  });
});
