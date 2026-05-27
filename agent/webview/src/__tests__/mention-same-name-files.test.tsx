/**
 * Bug hunt: two DIFFERENT files that share a filename (e.g. src/a/index.ts and
 * src/b/index.ts both labelled "index.ts") must both reach the send payload.
 * Mentions were keyed by `label`, so same-label-different-path chips collapsed
 * to one mention (and could resolve to the wrong path). getMentions must key by
 * path so distinct files survive while same-path duplicates (e.g. a ticket
 * pasted + selected) still dedup.
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
  ) => {
    const trigger = document.createTextNode(triggerChar);
    editor.appendChild(trigger);
    const range = document.createRange();
    range.setStart(trigger, triggerChar.length);
    range.collapse(true);
    const sel = window.getSelection()!;
    sel.removeAllRanges();
    sel.addRange(range);
    act(() => ref.current!.insertChip(mention, triggerChar, status));
  };
  return { ref, editor, insert };
}

describe('getMentions identity is path, not label', () => {
  it('keeps two same-named files with different paths', () => {
    const { ref, insert } = renderInput();
    insert({ type: 'file', label: 'index.ts', path: 'src/a/index.ts' }, '@');
    insert({ type: 'file', label: 'index.ts', path: 'src/b/index.ts' }, '@');

    const mentions = ref.current!.getMentions();
    const paths = mentions.map(m => m.path).sort();
    expect(paths, 'both distinct files preserved').toEqual(['src/a/index.ts', 'src/b/index.ts']);
  });

  it('still dedups the same ticket inserted twice (same path)', () => {
    const { ref, insert } = renderInput();
    insert({ type: 'ticket', label: 'PROJ-1', path: 'PROJ-1' }, '#', 'pending');
    insert({ type: 'ticket', label: 'PROJ-1', path: 'PROJ-1' }, '#', 'valid');

    const mentions = ref.current!.getMentions();
    expect(mentions.filter(m => m.path === 'PROJ-1')).toHaveLength(1);
  });
});
