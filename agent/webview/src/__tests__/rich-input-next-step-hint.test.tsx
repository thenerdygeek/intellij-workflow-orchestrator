/**
 * Ghost-text "next step" hint behavior on `<RichInput>`:
 *   1. When `hint` is provided and the input is empty, the hint is exposed via
 *      `data-placeholder` (the `:empty:before` CSS uses this attribute, so the
 *      cursor stays at offset 0 and the text is non-interactive).
 *   2. Pressing Right Arrow on an empty input fires `onAcceptHint`.
 *   3. Pressing Right Arrow when the input has text leaves the cursor alone
 *      and does NOT fire `onAcceptHint`.
 *   4. With no hint, Right Arrow is a no-op for the accept handler.
 */

import { describe, it, expect, vi } from 'vitest';
import { render, fireEvent } from '@testing-library/react';
import { createRef } from 'react';
import { RichInput, type RichInputHandle } from '../components/input/RichInput';

function getEditor(container: HTMLElement): HTMLDivElement {
  const el = container.querySelector('.rich-input') as HTMLDivElement | null;
  if (!el) throw new Error('rich-input editor not found');
  return el;
}

describe('RichInput — next-step ghost-text hint', () => {
  it('renders the hint as data-placeholder when input is empty', () => {
    const { container } = render(
      <RichInput placeholder="default" hint="run the tests" onAcceptHint={() => {}} />
    );
    const editor = getEditor(container);
    expect(editor.getAttribute('data-placeholder')).toBe('run the tests');
    expect(editor.getAttribute('data-hint-active')).toBe('true');
  });

  it('falls back to placeholder when hint is null/empty', () => {
    const { container, rerender } = render(
      <RichInput placeholder="default" hint={null} />
    );
    const editor = getEditor(container);
    expect(editor.getAttribute('data-placeholder')).toBe('default');
    expect(editor.getAttribute('data-hint-active')).toBe('false');

    rerender(<RichInput placeholder="default" hint="" />);
    expect(getEditor(container).getAttribute('data-placeholder')).toBe('default');
  });

  it('Right Arrow on empty input fires onAcceptHint', () => {
    const onAcceptHint = vi.fn();
    const { container } = render(
      <RichInput hint="commit this" onAcceptHint={onAcceptHint} />
    );
    const editor = getEditor(container);
    fireEvent.keyDown(editor, { key: 'ArrowRight' });
    expect(onAcceptHint).toHaveBeenCalledTimes(1);
  });

  it('Right Arrow does not fire onAcceptHint when input has text', () => {
    const onAcceptHint = vi.fn();
    const ref = createRef<RichInputHandle>();
    const { container } = render(
      <RichInput ref={ref} hint="commit this" onAcceptHint={onAcceptHint} />
    );
    const editor = getEditor(container);
    editor.textContent = 'hello';
    fireEvent.keyDown(editor, { key: 'ArrowRight' });
    expect(onAcceptHint).not.toHaveBeenCalled();
  });

  it('Right Arrow does nothing when no hint is set', () => {
    const onAcceptHint = vi.fn();
    const { container } = render(<RichInput hint={null} onAcceptHint={onAcceptHint} />);
    const editor = getEditor(container);
    fireEvent.keyDown(editor, { key: 'ArrowRight' });
    expect(onAcceptHint).not.toHaveBeenCalled();
  });

  it('setText fills the editor with the hint text on accept', () => {
    const ref = createRef<RichInputHandle>();
    render(<RichInput ref={ref} hint="run the tests" onAcceptHint={() => {}} />);
    ref.current!.setText('run the tests');
    expect(ref.current!.getText()).toBe('run the tests');
  });
});
