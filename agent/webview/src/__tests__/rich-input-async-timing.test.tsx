/**
 * Async-timing hunt: the MutationObserver that prunes mentionsRef runs as a
 * microtask, while recordHistory/captureSnapshot run synchronously inside the
 * click/insert handlers. This test pins the load-bearing invariant —
 * getMentions().length must always equal the number of visible chips — through
 * a chip insert → remove (×) → undo → redo cycle, flushing the observer between
 * steps. If this ever fails, a restored chip has no backing mention (silent
 * mention loss) or a removed chip leaves a zombie mention.
 */
import { describe, it, expect, afterEach } from 'vitest';
import { render, act, fireEvent } from '@testing-library/react';
import { createRef } from 'react';
import { RichInput, type RichInputHandle } from '../components/input/RichInput';

afterEach(() => {
  document.body.innerHTML = '';
});

const flush = () => act(async () => { await Promise.resolve(); await Promise.resolve(); });

describe('undo/redo vs async MutationObserver', () => {
  it('keeps getMentions in lockstep with visible chips through remove → undo → redo', async () => {
    const ref = createRef<RichInputHandle>();
    const { container } = render(<RichInput ref={ref} onChange={() => {}} onSend={() => {}} />);
    const editor = container.querySelector('[contenteditable="true"]') as HTMLElement;
    editor.focus();

    const chips = () => editor.querySelectorAll('[data-mention-label]').length;
    const mentions = () => ref.current!.getMentions().length;
    const assertLockstep = (label: string) =>
      expect(mentions(), `${label}: mentions vs chips`).toBe(chips());

    // Insert a chip via a seeded @ trigger.
    const trig = document.createTextNode('@');
    editor.appendChild(trig);
    const r = document.createRange();
    r.setStart(trig, 1);
    r.collapse(true);
    const sel = window.getSelection()!;
    sel.removeAllRanges();
    sel.addRange(r);
    act(() => ref.current!.insertChip({ type: 'file', label: 'index.ts', path: 'src/a/index.ts' }, '@'));
    await flush();
    expect(chips()).toBe(1);
    assertLockstep('after insert');

    // Remove via the × button (event-delegated click handler).
    const removeBtn = editor.querySelector('[data-remove]') as HTMLElement;
    act(() => fireEvent.click(removeBtn));
    await flush(); // let the MutationObserver prune mentionsRef
    expect(chips()).toBe(0);
    assertLockstep('after remove');

    // Undo (Ctrl+Z) — chip should come back WITH its mention.
    act(() => fireEvent.keyDown(editor, { key: 'z', ctrlKey: true }));
    await flush();
    assertLockstep('after undo');

    // Redo (Ctrl+Shift+Z) — back to removed.
    act(() => fireEvent.keyDown(editor, { key: 'z', ctrlKey: true, shiftKey: true }));
    await flush();
    assertLockstep('after redo');
  });

  it('tight race: remove then undo in the SAME tick (before the observer prunes)', async () => {
    const ref = createRef<RichInputHandle>();
    const { container } = render(<RichInput ref={ref} onChange={() => {}} onSend={() => {}} />);
    const editor = container.querySelector('[contenteditable="true"]') as HTMLElement;
    editor.focus();

    const trig = document.createTextNode('@');
    editor.appendChild(trig);
    const r = document.createRange();
    r.setStart(trig, 1);
    r.collapse(true);
    const sel = window.getSelection()!;
    sel.removeAllRanges();
    sel.addRange(r);
    act(() => ref.current!.insertChip({ type: 'file', label: 'beta.ts', path: 'src/beta.ts' }, '@'));
    await flush();

    // Remove and undo with NO microtask flush in between — the MutationObserver
    // callback for the removal is still queued when undo runs.
    act(() => {
      const removeBtn = editor.querySelector('[data-remove]') as HTMLElement;
      fireEvent.click(removeBtn);
      fireEvent.keyDown(editor, { key: 'z', ctrlKey: true });
    });
    await flush();

    expect(ref.current!.getMentions().length, 'mentions match chips after tight race').toBe(
      editor.querySelectorAll('[data-mention-label]').length,
    );
  });

  it('a stale removeChipById (e.g. late validation timeout) does not pollute undo history', () => {
    const ref = createRef<RichInputHandle>();
    const { container } = render(<RichInput ref={ref} onChange={() => {}} onSend={() => {}} />);
    const editor = container.querySelector('[contenteditable="true"]') as HTMLElement;
    editor.focus();

    act(() => ref.current!.setText('hello world'));
    // Simulate a stale validateTicket timeout firing for a chip that no longer
    // exists (it was cleared/restored). This must be a no-op — not a snapshot.
    act(() => ref.current!.removeChipById('chip-ghost'));

    // A single undo should return straight to the empty baseline; a phantom
    // snapshot would make the first undo a no-op (content stuck at "hello world").
    act(() => fireEvent.keyDown(editor, { key: 'z', ctrlKey: true }));
    expect(editor.textContent ?? '').toBe('');
  });
});
