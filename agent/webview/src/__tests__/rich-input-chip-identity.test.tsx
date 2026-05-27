/**
 * Bug #10 — chip operations must be keyed by a UNIQUE chip id, not by label.
 *
 * Chips were addressed by `[data-mention-label="X"]`. Labels are not unique:
 *   - a file literally named "PROJ-1" and Jira ticket "PROJ-1" share a label;
 *   - the same ticket pasted twice produces two chips with the same label.
 * Async ticket validation (`updateChipStatus` / `removeChipById`, fired up to 5s
 * later) then corrupts/removes the WRONG chip(s).
 *
 * Root fix: every chip gets a `data-chip-id` at insert time, and insert/update/
 * remove/prune all key by that id. `insertChip` returns the id so the async
 * validation flow can target exactly the chip it created.
 */
import { describe, it, expect, afterEach } from 'vitest';
import { render, act } from '@testing-library/react';
import { createRef } from 'react';
import { RichInput, type RichInputHandle, type ChipStatus } from '../components/input/RichInput';

afterEach(() => {
  document.body.innerHTML = '';
});

function renderInput() {
  const ref = createRef<RichInputHandle>();
  const { container } = render(<RichInput ref={ref} onChange={() => {}} onSend={() => {}} />);
  const editor = container.querySelector('[contenteditable="true"]') as HTMLElement;
  editor.focus();
  const insert = (
    mention: { type: 'file' | 'ticket'; label: string; path: string },
    triggerChar: string,
    status: ChipStatus = 'default',
  ): string => {
    const trigger = document.createTextNode(triggerChar);
    editor.appendChild(trigger);
    const range = document.createRange();
    range.setStart(trigger, triggerChar.length);
    range.collapse(true);
    const sel = window.getSelection()!;
    sel.removeAllRanges();
    sel.addRange(range);
    let id = '';
    act(() => { id = ref.current!.insertChip(mention, triggerChar, status); });
    return id;
  };
  return { ref, editor, insert };
}

describe('Bug #10 — chip identity is a unique id, not the label', () => {
  it('insertChip returns a unique id per chip', () => {
    const { insert } = renderInput();
    const a = insert({ type: 'ticket', label: 'PROJ-1', path: 'PROJ-1' }, '#', 'pending');
    const b = insert({ type: 'ticket', label: 'PROJ-1', path: 'PROJ-1' }, '#', 'pending');
    expect(a).toBeTruthy();
    expect(b).toBeTruthy();
    expect(a).not.toBe(b);
  });

  it('removeChipById removes only the targeted chip, not a same-label file chip', () => {
    const { ref, editor, insert } = renderInput();
    const fileId = insert({ type: 'file', label: 'PROJ-1', path: 'src/PROJ-1.ts' }, '@');
    const ticketId = insert({ type: 'ticket', label: 'PROJ-1', path: 'PROJ-1' }, '#', 'pending');
    expect(fileId).not.toBe(ticketId);

    // Ticket validation failed → remove the ticket chip.
    act(() => ref.current!.removeChipById(ticketId));

    const remaining = editor.querySelectorAll('[data-chip-id]');
    expect(remaining).toHaveLength(1);
    expect((remaining[0] as HTMLElement).dataset.mentionType).toBe('file');
    // The file mention must survive in the send payload.
    expect(ref.current!.getMentions().map(m => m.path)).toEqual(['src/PROJ-1.ts']);
  });

  it('removeChipById on one of two identical pasted tickets keeps the other', () => {
    const { ref, editor, insert } = renderInput();
    const first = insert({ type: 'ticket', label: 'PROJ-2', path: 'PROJ-2' }, '#', 'pending');
    insert({ type: 'ticket', label: 'PROJ-2', path: 'PROJ-2' }, '#', 'pending');

    act(() => ref.current!.removeChipById(first));

    expect(editor.querySelectorAll('[data-chip-id]')).toHaveLength(1);
  });

  it('updateChipStatus targets only the chip with the given id', () => {
    const { ref, editor, insert } = renderInput();
    const fileId = insert({ type: 'file', label: 'PROJ-3', path: 'src/PROJ-3.ts' }, '@');
    const ticketId = insert({ type: 'ticket', label: 'PROJ-3', path: 'PROJ-3' }, '#', 'pending');

    act(() => ref.current!.updateChipStatus(ticketId, 'invalid'));

    const fileChip = editor.querySelector(`[data-chip-id="${fileId}"]`) as HTMLElement;
    const ticketChip = editor.querySelector(`[data-chip-id="${ticketId}"]`) as HTMLElement;
    expect(ticketChip.dataset.chipStatus).toBe('invalid');
    // File chip is untouched — still its insert-time default status.
    expect(fileChip.dataset.chipStatus).toBe('default');
  });

  it('MutationObserver prunes the mention for the removed chip only (× button)', async () => {
    const { ref, editor, insert } = renderInput();
    insert({ type: 'ticket', label: 'DUP', path: 'DUP' }, '#', 'pending');
    insert({ type: 'ticket', label: 'DUP', path: 'DUP' }, '#', 'pending');
    // Two chips, both label "DUP". getMentions dedups same-path → 1 entity.
    expect(ref.current!.getMentions()).toHaveLength(1);

    // Click the × on the first chip.
    const firstRemove = editor.querySelector('[data-remove]') as HTMLElement;
    act(() => firstRemove.click());
    // Let the MutationObserver flush.
    await act(async () => { await Promise.resolve(); });

    expect(editor.querySelectorAll('[data-chip-id]')).toHaveLength(1);
    expect(ref.current!.getMentions()).toHaveLength(1);
  });
});
