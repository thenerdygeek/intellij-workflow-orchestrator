/**
 * XSS hardening tests for PlanDocumentViewer.
 *
 * T1 from the security hardening plan:
 * - Five threat-model assertions on the body render path (showLineNumbers=false)
 * - Five threat-model assertions on the comment-thread render path (showLineNumbers=true)
 * - Four behavioral-preservation assertions (option B vs A regression guards)
 *
 * These tests MUST FAIL before rehype-raw → rehype-sanitize replacement,
 * and MUST PASS after.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { render, cleanup } from '@testing-library/react';
import { PlanDocumentViewer } from '@/components/plan/PlanDocumentViewer';

describe('PlanDocumentViewer — XSS hardening (T1 threat model)', () => {
  beforeEach(() => {
    cleanup();
    // Reset any accidental global mutations from XSS payloads
    delete (window as any).__pwned;
  });

  // ── Body render path (showLineNumbers=false) ────────────────────────────

  describe('body render path (showLineNumbers=false)', () => {
    it('strips <script> tags from plan markdown', () => {
      const { container } = render(
        <PlanDocumentViewer
          markdown={"Hello <script>window.__pwned=1</script> world"}
          showLineNumbers={false}
        />,
      );

      expect(container.querySelector('script')).toBeNull();
      expect((window as any).__pwned).toBeUndefined();
    });

    it('strips or removes onerror attribute from img tags', () => {
      const { container } = render(
        <PlanDocumentViewer
          markdown={'<img src=x onerror="window.__pwned=1">'}
          showLineNumbers={false}
        />,
      );

      const img = container.querySelector('img');
      if (img !== null) {
        expect(img.getAttribute('onerror')).toBeNull();
      }
      expect((window as any).__pwned).toBeUndefined();
    });

    it('sanitizes javascript: href on anchor tags', () => {
      const { container } = render(
        <PlanDocumentViewer
          markdown={'<a href="javascript:alert(1)">click</a>'}
          showLineNumbers={false}
        />,
      );

      const anchor = container.querySelector('a');
      if (anchor !== null) {
        const href = anchor.getAttribute('href');
        expect(href).not.toBe('javascript:alert(1)');
        if (href !== null) {
          expect(href.toLowerCase().startsWith('javascript:')).toBe(false);
        }
      }
    });

    it('strips <iframe> elements entirely', () => {
      const { container } = render(
        <PlanDocumentViewer
          markdown={'<iframe src="https://evil.com"></iframe>'}
          showLineNumbers={false}
        />,
      );

      expect(container.querySelector('iframe')).toBeNull();
    });

    it('strips <script> inside <svg>', () => {
      const { container } = render(
        <PlanDocumentViewer
          markdown={'<svg><script>alert(1)</script></svg>'}
          showLineNumbers={false}
        />,
      );

      expect(container.querySelector('script')).toBeNull();
    });
  });

  // ── Comment-thread render path (showLineNumbers=true, interactive mode) ──

  describe('comment-thread render path (showLineNumbers=true)', () => {
    it('strips <script> tags in interactive mode', () => {
      const { container } = render(
        <PlanDocumentViewer
          markdown={"Hello <script>window.__pwned=1</script> world"}
          showLineNumbers={true}
        />,
      );

      expect(container.querySelector('script')).toBeNull();
      expect((window as any).__pwned).toBeUndefined();
    });

    it('strips or removes onerror attribute from img tags in interactive mode', () => {
      const { container } = render(
        <PlanDocumentViewer
          markdown={'<img src=x onerror="window.__pwned=1">'}
          showLineNumbers={true}
        />,
      );

      const img = container.querySelector('img');
      if (img !== null) {
        expect(img.getAttribute('onerror')).toBeNull();
      }
      expect((window as any).__pwned).toBeUndefined();
    });

    it('sanitizes javascript: href in interactive mode', () => {
      const { container } = render(
        <PlanDocumentViewer
          markdown={'<a href="javascript:alert(1)">click</a>'}
          showLineNumbers={true}
        />,
      );

      const anchor = container.querySelector('a');
      if (anchor !== null) {
        const href = anchor.getAttribute('href');
        expect(href).not.toBe('javascript:alert(1)');
        if (href !== null) {
          expect(href.toLowerCase().startsWith('javascript:')).toBe(false);
        }
      }
    });

    it('strips <iframe> elements in interactive mode', () => {
      const { container } = render(
        <PlanDocumentViewer
          markdown={'<iframe src="https://evil.com"></iframe>'}
          showLineNumbers={true}
        />,
      );

      expect(container.querySelector('iframe')).toBeNull();
    });

    it('strips <script> inside <svg> in interactive mode', () => {
      const { container } = render(
        <PlanDocumentViewer
          markdown={'<svg><script>alert(1)</script></svg>'}
          showLineNumbers={true}
        />,
      );

      expect(container.querySelector('script')).toBeNull();
    });
  });

  // ── Behavioral-preservation assertions (option B vs A regression guards) ──

  it('preserves <details> and <summary> elements in plan documents', () => {
    const { container } = render(
      <PlanDocumentViewer
        markdown={'<details><summary>Expand plan details</summary>Plan content here</details>'}
        showLineNumbers={false}
      />,
    );

    expect(container.querySelector('details')).not.toBeNull();
    expect(container.querySelector('summary')).not.toBeNull();
  });

  it('preserves <kbd> elements in plan documents', () => {
    const { container } = render(
      <PlanDocumentViewer
        markdown={'Press <kbd>Ctrl+Z</kbd> to undo'}
        showLineNumbers={false}
      />,
    );

    expect(container.querySelector('kbd')).not.toBeNull();
  });

  it('preserves <sub> elements in plan documents', () => {
    const { container } = render(
      <PlanDocumentViewer
        markdown={'H<sub>2</sub>O'}
        showLineNumbers={false}
      />,
    );

    expect(container.querySelector('sub')).not.toBeNull();
  });

  it('renders GFM task list checkboxes in plan documents', () => {
    const { container } = render(
      <PlanDocumentViewer
        markdown={'- [ ] todo item\n- [x] done item'}
        showLineNumbers={false}
      />,
    );

    // PlanDocumentViewer uses a custom input handler that renders .task-checkbox spans.
    const checkboxInput = container.querySelector('input[type="checkbox"]');
    const taskCheckboxSpan = container.querySelector('.task-checkbox');
    const hasTaskList = checkboxInput !== null || taskCheckboxSpan !== null;
    expect(hasTaskList, 'GFM task list items should render with a checkbox indicator').toBe(true);
  });
});
