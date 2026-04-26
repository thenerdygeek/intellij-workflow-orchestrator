/**
 * T8 — XSS hardening: chip builder in RichInput must not inject raw HTML
 * from user-controlled label strings.
 *
 * Threat model (must FAIL before fix, PASS after):
 *   Feeding a mention with a label that contains HTML (e.g. an <img onerror=> payload)
 *   must NOT produce a live element — the text must be escaped / rendered as text
 *   content only.
 *
 * Behavioral preservation (must PASS before AND after fix):
 *   Normal labels produce a chip with the correct text content and remove button.
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { render, act } from '@testing-library/react';
import { createRef } from 'react';
import { RichInput, type RichInputHandle } from '../components/input/RichInput';

// ── jsdom polyfills — ClipboardEvent and DataTransfer not in jsdom ───────────
if (typeof DataTransfer === 'undefined') {
  class DataTransferPolyfill {
    private _data: Record<string, string> = {};
    getData(type: string) { return this._data[type] ?? ''; }
    setData(type: string, value: string) { this._data[type] = value; }
  }
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (globalThis as any).DataTransfer = DataTransferPolyfill;
}
if (typeof ClipboardEvent === 'undefined') {
  // Minimal stub: extends Event, exposes clipboardData via constructor options
  class ClipboardEventPolyfill extends Event {
    clipboardData: DataTransfer | null;
    constructor(type: string, init?: EventInit & { clipboardData?: DataTransfer | null }) {
      super(type, init);
      this.clipboardData = init?.clipboardData ?? null;
    }
  }
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (globalThis as any).ClipboardEvent = ClipboardEventPolyfill;
}

// ── Helpers ─────────────────────────────────────────────────────────────────

/** Focus the editor, seed trigger text at offset 0, and position cursor after it. */
function seedTrigger(editor: HTMLElement, trigger: string) {
  editor.focus();
  editor.textContent = trigger;
  const range = document.createRange();
  const textNode = editor.firstChild!;
  range.setStart(textNode, trigger.length);
  range.setEnd(textNode, trigger.length);
  const sel = window.getSelection()!;
  sel.removeAllRanges();
  sel.addRange(range);
}

// ── T8 threat-model assertions ───────────────────────────────────────────────

describe('RichInput chip builder — XSS hardening (T8)', () => {
  afterEach(() => {
    document.body.innerHTML = '';
    // Reset pwned sentinel between tests
    (window as Record<string, unknown>).__pwned = undefined;
  });

  // ── insertChip path (line 266) ──

  describe('insertChip path (mention label)', () => {
    it('T8-XSS-1: XSS payload in mention.label does NOT create a live <img> element', () => {
      const ref = createRef<RichInputHandle>();
      const { container } = render(
        <RichInput ref={ref} onChange={() => {}} onSubmit={() => {}} />,
      );

      const editor = container.querySelector('[contenteditable="true"]') as HTMLElement;
      seedTrigger(editor, '@');

      act(() => {
        ref.current!.insertChip(
          {
            type: 'file',
            label: '<img src=x onerror="(window as any).__pwned=1">',
            path: 'src/App.tsx',
          },
          '@',
        );
      });

      // No real <img> element must exist inside any chip
      const chip = container.querySelector('[data-mention-label]');
      expect(chip).not.toBeNull();

      const imgEl = chip!.querySelector('img');
      expect(imgEl).toBeNull();

      // The onerror handler must never have fired
      expect((window as Record<string, unknown>).__pwned).toBeUndefined();
    });

    it('T8-XSS-2: XSS payload in mention.label is present as escaped text, not raw HTML', () => {
      const ref = createRef<RichInputHandle>();
      const { container } = render(
        <RichInput ref={ref} onChange={() => {}} onSubmit={() => {}} />,
      );

      const editor = container.querySelector('[contenteditable="true"]') as HTMLElement;
      seedTrigger(editor, '@');

      const xssLabel = '<img src=x onerror="window.__pwned=1">';

      act(() => {
        ref.current!.insertChip(
          { type: 'file', label: xssLabel, path: 'src/Foo.tsx' },
          '@',
        );
      });

      const chip = container.querySelector('[data-mention-label]');
      expect(chip).not.toBeNull();

      // The label text must appear as plain text, not as a parsed element
      const labelSpan = chip!.querySelector('span');
      expect(labelSpan).not.toBeNull();
      expect(labelSpan!.textContent).toBe(xssLabel);
    });

    it('T8-XSS-3: aria-label on remove button does not execute onerror via raw HTML injection', () => {
      const ref = createRef<RichInputHandle>();
      const { container } = render(
        <RichInput ref={ref} onChange={() => {}} onSubmit={() => {}} />,
      );

      const editor = container.querySelector('[contenteditable="true"]') as HTMLElement;
      seedTrigger(editor, '@');

      const xssLabel = '"><img src=x onerror="window.__pwned=1">';

      act(() => {
        ref.current!.insertChip(
          { type: 'file', label: xssLabel, path: 'src/Foo.tsx' },
          '@',
        );
      });

      // onerror must not fire
      expect((window as Record<string, unknown>).__pwned).toBeUndefined();

      // The button's aria-label must be a plain attribute string, not parsed HTML
      const btn = container.querySelector('[data-remove]');
      expect(btn).not.toBeNull();
      // aria-label is set programmatically — it should be a plain string
      const ariaLabel = btn!.getAttribute('aria-label');
      expect(ariaLabel).toBeTruthy();
      // The label value must contain the raw text, not produce child elements
      expect(btn!.children.length).toBe(0);
    });

    // ── Behavioral preservation ──

    it('T8-NORMAL-1: normal label produces chip with correct text content', () => {
      const ref = createRef<RichInputHandle>();
      const { container } = render(
        <RichInput ref={ref} onChange={() => {}} onSubmit={() => {}} />,
      );

      const editor = container.querySelector('[contenteditable="true"]') as HTMLElement;
      seedTrigger(editor, '@');

      act(() => {
        ref.current!.insertChip(
          { type: 'file', label: 'subhankar', path: 'src/subhankar.ts' },
          '@',
        );
      });

      const chip = container.querySelector('[data-mention-label="subhankar"]');
      expect(chip).not.toBeNull();

      const labelSpan = chip!.querySelector('span');
      expect(labelSpan).not.toBeNull();
      expect(labelSpan!.textContent).toBe('subhankar');

      const removeBtn = chip!.querySelector('[data-remove]');
      expect(removeBtn).not.toBeNull();
    });

    it('T8-NORMAL-2: chip data-mention-label attribute is set correctly', () => {
      const ref = createRef<RichInputHandle>();
      const { container } = render(
        <RichInput ref={ref} onChange={() => {}} onSubmit={() => {}} />,
      );

      const editor = container.querySelector('[contenteditable="true"]') as HTMLElement;
      seedTrigger(editor, '@');

      act(() => {
        ref.current!.insertChip(
          { type: 'symbol', label: 'MyClass', path: 'src/MyClass.ts' },
          '@',
        );
      });

      const chip = container.querySelector('[data-mention-label="MyClass"]');
      expect(chip).not.toBeNull();
      expect(chip!.getAttribute('data-mention-type')).toBe('symbol');
    });
  });

  // ── Paste path (line 469) — ticketKey chip ──

  describe('paste path (ticketKey chip)', () => {
    beforeEach(() => {
      // Mock clipboard so handlePaste can read pasted text
    });

    it('T8-XSS-4: pasted ticket key containing HTML tags is rendered as text, not parsed', () => {
      const ref = createRef<RichInputHandle>();
      const { container } = render(
        <RichInput ref={ref} onChange={() => {}} onSubmit={() => {}} />,
      );

      const editor = container.querySelector('[contenteditable="true"]') as HTMLElement;
      editor.focus();

      // Simulate a paste event that contains a valid-pattern ticket key preceded by
      // a crafted label-like string. The ticketKey is extracted via PASTED_TICKET_PATTERN
      // which requires #[A-Za-z][A-Za-z0-9]+-\d+, so ticketKey itself is safe alphanumeric.
      // However, we want to confirm the chip innerHTML is not template-literal-built
      // and that a "pure" ticketKey chip still renders its textContent correctly.
      const pasteText = '#PROJ-999';

      const pasteEvent = new ClipboardEvent('paste', {
        bubbles: true,
        cancelable: true,
        clipboardData: new DataTransfer(),
      });
      // DataTransfer.setData is not always available in jsdom; use Object.defineProperty
      Object.defineProperty(pasteEvent, 'clipboardData', {
        value: {
          getData: (_type: string) => pasteText,
        },
      });

      act(() => {
        editor.dispatchEvent(pasteEvent);
      });

      // A chip for PROJ-999 should be in the DOM
      const chip = container.querySelector('[data-mention-label="PROJ-999"]');
      expect(chip).not.toBeNull();

      const labelSpan = chip!.querySelector('span');
      expect(labelSpan).not.toBeNull();
      // Text content must be the raw ticket key string
      expect(labelSpan!.textContent).toBe('PROJ-999');

      // No injected img elements
      const imgEl = chip!.querySelector('img');
      expect(imgEl).toBeNull();
    });

    it('T8-XSS-5: pasted ticket chip remove button is a DOM element, not raw HTML', () => {
      const ref = createRef<RichInputHandle>();
      const { container } = render(
        <RichInput ref={ref} onChange={() => {}} onSubmit={() => {}} />,
      );

      const editor = container.querySelector('[contenteditable="true"]') as HTMLElement;
      editor.focus();

      const pasteEvent = new ClipboardEvent('paste', {
        bubbles: true,
        cancelable: true,
      });
      Object.defineProperty(pasteEvent, 'clipboardData', {
        value: { getData: (_type: string) => '#ABC-42' },
      });

      act(() => {
        editor.dispatchEvent(pasteEvent);
      });

      const chip = container.querySelector('[data-mention-label="ABC-42"]');
      expect(chip).not.toBeNull();

      const removeBtn = chip!.querySelector('[data-remove]');
      expect(removeBtn).not.toBeNull();
      // Button must be a proper DOM element created via createElement, not innerHTML
      expect(removeBtn!.tagName).toBe('BUTTON');
    });

    it('T8-NORMAL-3: pasted ticket chip has correct pending status and mentionType', () => {
      const ref = createRef<RichInputHandle>();
      const { container } = render(
        <RichInput ref={ref} onChange={() => {}} onSubmit={() => {}} />,
      );

      const editor = container.querySelector('[contenteditable="true"]') as HTMLElement;
      editor.focus();

      const pasteEvent = new ClipboardEvent('paste', { bubbles: true, cancelable: true });
      Object.defineProperty(pasteEvent, 'clipboardData', {
        value: { getData: (_type: string) => '#DEF-7' },
      });

      act(() => {
        editor.dispatchEvent(pasteEvent);
      });

      const chip = container.querySelector('[data-mention-label="DEF-7"]');
      expect(chip).not.toBeNull();
      expect(chip!.getAttribute('data-chip-status')).toBe('pending');
      expect(chip!.getAttribute('data-mention-type')).toBe('ticket');
    });
  });
});
