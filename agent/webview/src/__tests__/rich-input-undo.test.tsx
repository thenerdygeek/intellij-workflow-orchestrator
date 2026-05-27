/**
 * Custom undo/redo history for `<RichInput>`.
 *
 * RichInput is a `contentEditable` div whose content is changed by a mix of user typing
 * (via the browser) AND direct DOM mutation (chip insert/remove, paste, setText). Direct DOM
 * mutation desyncs the browser's native contentEditable undo stack, so Ctrl+Z is unreliable.
 * RichInput therefore owns its own history (the same approach Lexical/ProseMirror/Slate take).
 *
 * Behavior pinned here:
 *  - Ctrl+Z reverts the last committed change; Ctrl+Shift+Z and Ctrl+Y redo it.
 *  - Chip insertion is its own discrete undo step (does not fold into adjacent typing).
 *  - Rapid typing coalesces into one undo step; a pause begins a new one.
 *  - Ctrl+Z with nothing to undo is swallowed (preventDefault) so it doesn't bubble to the IDE.
 *  - clear() (post-submit) resets history so Ctrl+Z can't resurrect a sent message.
 */

import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, fireEvent } from '@testing-library/react';
import { createRef } from 'react';
import { RichInput, type RichInputHandle } from '../components/input/RichInput';

function getEditor(container: HTMLElement): HTMLDivElement {
  const el = container.querySelector('.rich-input') as HTMLDivElement | null;
  if (!el) throw new Error('rich-input editor not found');
  return el;
}

function ctrlZ(editor: HTMLElement, opts: { shift?: boolean } = {}) {
  fireEvent.keyDown(editor, { key: 'z', ctrlKey: true, shiftKey: !!opts.shift });
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('RichInput — undo/redo history', () => {
  it('Ctrl+Z reverts the last committed change', () => {
    const ref = createRef<RichInputHandle>();
    const { container } = render(<RichInput ref={ref} />);
    const editor = getEditor(container);

    ref.current!.setText('hello world');
    expect(ref.current!.getText()).toBe('hello world');

    ctrlZ(editor);
    expect(ref.current!.getText()).toBe('');
  });

  it('Ctrl+Shift+Z and Ctrl+Y redo an undone change', () => {
    const ref = createRef<RichInputHandle>();
    const { container } = render(<RichInput ref={ref} />);
    const editor = getEditor(container);

    ref.current!.setText('draft message');
    ctrlZ(editor);
    expect(ref.current!.getText()).toBe('');

    ctrlZ(editor, { shift: true });
    expect(ref.current!.getText()).toBe('draft message');

    ctrlZ(editor);
    expect(ref.current!.getText()).toBe('');
    fireEvent.keyDown(editor, { key: 'y', ctrlKey: true });
    expect(ref.current!.getText()).toBe('draft message');
  });

  it('Ctrl+Z with nothing to undo is swallowed (preventDefault) and a no-op', () => {
    const ref = createRef<RichInputHandle>();
    const { container } = render(<RichInput ref={ref} />);
    const editor = getEditor(container);

    const ev = new KeyboardEvent('keydown', { key: 'z', ctrlKey: true, bubbles: true, cancelable: true });
    editor.dispatchEvent(ev);

    expect(ev.defaultPrevented).toBe(true);
    expect(ref.current!.getText()).toBe('');
  });

  it('a chip insertion is its own undo step (Ctrl+Z removes the chip, keeps prior text)', () => {
    const ref = createRef<RichInputHandle>();
    const { container } = render(<RichInput ref={ref} />);
    const editor = getEditor(container);

    // user types '@'
    editor.textContent = '@';
    fireEvent.input(editor);

    // place caret after the '@'
    const textNode = editor.firstChild as Text;
    const range = document.createRange();
    range.setStart(textNode, 1);
    range.collapse(true);
    const sel = window.getSelection()!;
    sel.removeAllRanges();
    sel.addRange(range);

    ref.current!.insertChip({ type: 'file', label: 'App.tsx', path: 'src/App.tsx' }, '@');
    expect(ref.current!.getMentions().length).toBe(1);

    ctrlZ(editor);
    expect(ref.current!.getMentions().length).toBe(0);
    expect(ref.current!.getText()).toBe('@');
  });

  it('rapid typing coalesces into one undo step; a pause begins a new one', () => {
    let now = 1000;
    vi.spyOn(Date, 'now').mockImplementation(() => now);

    const ref = createRef<RichInputHandle>();
    const { container } = render(<RichInput ref={ref} />);
    const editor = getEditor(container);

    // burst 1: 'a' then 'ab' within the coalesce window
    editor.textContent = 'a'; fireEvent.input(editor);
    now += 100;
    editor.textContent = 'ab'; fireEvent.input(editor);

    // pause beyond the window, then burst 2
    now += 1000;
    editor.textContent = 'abc'; fireEvent.input(editor);

    ctrlZ(editor);
    expect(ref.current!.getText()).toBe('ab'); // burst 2 undone as one step

    ctrlZ(editor);
    expect(ref.current!.getText()).toBe('');   // burst 1 undone as one step
  });

  it('clear() resets history so Ctrl+Z cannot restore a sent message', () => {
    const ref = createRef<RichInputHandle>();
    const { container } = render(<RichInput ref={ref} />);
    const editor = getEditor(container);

    ref.current!.setText('sent message');
    ref.current!.clear();
    expect(ref.current!.getText()).toBe('');

    ctrlZ(editor);
    expect(ref.current!.getText()).toBe('');
  });
});
