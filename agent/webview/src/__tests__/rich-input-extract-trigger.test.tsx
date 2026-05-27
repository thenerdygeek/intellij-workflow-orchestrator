/**
 * Regressions for two RichInput edge cases found by adversarial review:
 *  #14 — a chip on a non-first (block-wrapped) line must keep its @/#// prefix
 *        in getText() and must NOT leak the chip's × button text.
 *  #17 — a `/` typed immediately after a chip must still open the skill trigger
 *        (the zero-width space insertChip leaves at the caret previously broke
 *        the slash regex's `(?:^|\s)` boundary).
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, act, fireEvent } from '@testing-library/react';
import { createRef } from 'react';
import { RichInput, type RichInputHandle } from '../components/input/RichInput';

afterEach(() => {
  document.body.innerHTML = '';
});

describe('#14 extractText with chips inside block lines', () => {
  it('keeps the chip prefix and drops the × button text on a non-first line', () => {
    const ref = createRef<RichInputHandle>();
    const { container } = render(<RichInput ref={ref} onChange={() => {}} onSend={() => {}} />);
    const editor = container.querySelector('[contenteditable="true"]') as HTMLElement;
    // line 1 plain; line 2 (a contentEditable <div>) contains a ticket chip.
    editor.innerHTML =
      '<div>line one</div>' +
      '<div>see <span data-mention-label="PROJ-9" data-mention-type="ticket" data-chip-status="valid">' +
      '<span>PROJ-9</span><button data-remove aria-label="Remove PROJ-9">×</button></span> ok</div>';

    const text = ref.current!.getText();
    expect(text).toContain('line one');
    expect(text, 'chip prefix preserved').toContain('#PROJ-9');
    expect(text, 'remove button glyph not leaked').not.toContain('×');
    expect(text).toContain('ok');
  });
});

describe('#17 slash trigger immediately after a chip', () => {
  it('opens the / (skill) trigger despite the caret zero-width space', () => {
    const ref = createRef<RichInputHandle>();
    const onChange = vi.fn();
    const { container } = render(<RichInput ref={ref} onChange={onChange} onSend={() => {}} />);
    const editor = container.querySelector('[contenteditable="true"]') as HTMLElement;
    editor.focus();

    // Insert a chip the way the change handler would (seed an "@" trigger).
    const trig = document.createTextNode('@');
    editor.appendChild(trig);
    const r1 = document.createRange();
    r1.setStart(trig, 1);
    r1.collapse(true);
    const sel = window.getSelection()!;
    sel.removeAllRanges();
    sel.addRange(r1);
    act(() => ref.current!.insertChip({ type: 'file', label: 'x.ts', path: 'src/x.ts' }, '@'));

    // insertChip leaves a trailing zero-width-space text node at the caret.
    // Simulate typing "/" into it.
    const last = editor.childNodes[editor.childNodes.length - 1] as Text;
    last.textContent = (last.textContent ?? '') + '/';
    const r2 = document.createRange();
    r2.setStart(last, last.textContent.length);
    r2.collapse(true);
    sel.removeAllRanges();
    sel.addRange(r2);

    onChange.mockClear();
    fireEvent.input(editor);

    const triggerTypes = onChange.mock.calls.map(c => c[1]?.type);
    expect(triggerTypes, 'slash trigger fired after the chip').toContain('/');
  });
});
