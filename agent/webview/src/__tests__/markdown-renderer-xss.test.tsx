/**
 * XSS hardening tests for MarkdownRenderer.
 *
 * T1 from the security hardening plan:
 * - Five threat-model assertions: script injection, onerror handler, javascript: href,
 *   iframe, and SVG-embedded script must all be neutralized.
 * - Four behavioral-preservation assertions: <details>/<summary>, <kbd>, <sub>, and
 *   GFM task-list checkboxes must survive sanitization (option B vs A regression guards).
 *
 * These tests MUST FAIL before rehype-raw → rehype-sanitize replacement,
 * and MUST PASS after.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { render, cleanup } from '@testing-library/react';
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer';

describe('MarkdownRenderer — XSS hardening (T1 threat model)', () => {
  beforeEach(() => {
    cleanup();
    // Reset any accidental global mutations from XSS payloads
    delete (window as any).__pwned;
  });

  // ── Threat-model assertions ──────────────────────────────────────────────

  it('strips <script> tags from LLM-controlled markdown (no execution, no element)', () => {
    const { container } = render(
      <MarkdownRenderer content="Hello <script>window.__pwned=1</script> world" />,
    );

    // The script element must not exist in the DOM.
    expect(container.querySelector('script')).toBeNull();

    // The payload must not have executed.
    expect((window as any).__pwned).toBeUndefined();
  });

  it('strips or removes onerror attribute from img tags', () => {
    const { container } = render(
      <MarkdownRenderer content={'<img src=x onerror="window.__pwned=1">'} />,
    );

    // Either no img exists (stripped entirely) or it exists without onerror.
    const img = container.querySelector('img');
    if (img !== null) {
      expect(img.getAttribute('onerror')).toBeNull();
    }

    // The payload must not have executed.
    expect((window as any).__pwned).toBeUndefined();
  });

  it('sanitizes javascript: href on anchor tags', () => {
    const { container } = render(
      <MarkdownRenderer content={'<a href="javascript:alert(1)">click</a>'} />,
    );

    // Anchor may render (it's in the default schema) but href must be sanitized.
    const anchor = container.querySelector('a');
    if (anchor !== null) {
      const href = anchor.getAttribute('href');
      // After sanitization, javascript: URLs are either removed or replaced.
      expect(href).not.toBe('javascript:alert(1)');
      if (href !== null) {
        expect(href.toLowerCase().startsWith('javascript:')).toBe(false);
      }
    }
  });

  it('strips <iframe> elements entirely', () => {
    const { container } = render(
      <MarkdownRenderer content={'<iframe src="https://evil.com"></iframe>'} />,
    );

    expect(container.querySelector('iframe')).toBeNull();
  });

  it('strips <script> inside <svg> (SVG-embedded script attack)', () => {
    const { container } = render(
      <MarkdownRenderer content={'<svg><script>alert(1)</script></svg>'} />,
    );

    // The <script> inside SVG must be stripped regardless of whether <svg> itself renders.
    expect(container.querySelector('script')).toBeNull();
  });

  // ── Behavioral-preservation assertions (option B vs A regression guards) ──

  it('preserves <details> and <summary> elements (collapsible LLM responses)', () => {
    const { container } = render(
      <MarkdownRenderer
        content={'<details><summary>Click to expand</summary>Hidden content here</details>'}
      />,
    );

    expect(container.querySelector('details')).not.toBeNull();
    expect(container.querySelector('summary')).not.toBeNull();
  });

  it('preserves <kbd> elements (keyboard shortcut hints in agent responses)', () => {
    const { container } = render(
      <MarkdownRenderer content={'Press <kbd>Ctrl+C</kbd> to copy'} />,
    );

    expect(container.querySelector('kbd')).not.toBeNull();
  });

  it('preserves <sub> elements (chemical formulas like H2O)', () => {
    const { container } = render(
      <MarkdownRenderer content={'H<sub>2</sub>O'} />,
    );

    expect(container.querySelector('sub')).not.toBeNull();
  });

  it('renders GFM task list checkboxes (remark-gfm <input type="checkbox" disabled>)', () => {
    const { container } = render(
      <MarkdownRenderer
        content={'- [ ] todo item\n- [x] done item'}
      />,
    );

    // remark-gfm generates <input type="checkbox" disabled> for task lists.
    // rehype-sanitize default schema allows input[type=checkbox][disabled].
    // The custom PlanDocumentViewer component may also render .task-checkbox spans —
    // but MarkdownRenderer uses remark-gfm directly, so we check for the input.
    const checkbox = container.querySelector('input[type="checkbox"]');
    // Also acceptable: the checkbox may be rendered as a span with class task-checkbox
    // by a custom component, but it must not be outright missing.
    const taskCheckboxSpan = container.querySelector('.task-checkbox');
    const hasTaskList = checkbox !== null || taskCheckboxSpan !== null;
    expect(hasTaskList, 'GFM task list items should render with a checkbox indicator').toBe(true);
  });
});
