/**
 * `remarkPlanAdmonition` correctness — drives the same react-markdown +
 * remark-gfm + rehype-sanitize pipeline as the production plan viewer so
 * we test what the user actually sees, not what the AST looks like.
 *
 * Covers:
 *   - 5 native GitHub labels render with `data-admonition-label` and
 *     `data-admonition-known="true"`.
 *   - Custom-label `[!REVIEW REQUIRED]` carries the multi-word label
 *     through verbatim and is marked known.
 *   - An off-list label (`[!ROLLBACK PLAN]`) is marked `known="false"`.
 *   - Body markdown still renders (bold, links, code).
 *   - The marker line is stripped from the visible body.
 *   - A blockquote without a marker is left as a normal `<blockquote>`.
 *   - Sanitization preserves the `data-admonition-*` attributes (regression
 *     guard — the default schema would strip them otherwise).
 */
import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeSanitize, { defaultSchema } from 'rehype-sanitize';
import { remarkPlanAdmonition } from '../components/plan/remarkPlanAdmonition';

// Match the schema the production viewer uses.
const SCHEMA = {
  ...defaultSchema,
  tagNames: [...(defaultSchema.tagNames ?? []), 'div'],
  attributes: {
    ...defaultSchema.attributes,
    div: [
      ...((defaultSchema.attributes?.div as unknown[]) ?? []),
      'className',
      'dataAdmonitionLabel',
      'dataAdmonitionKnown',
    ],
  },
};

function renderMd(md: string) {
  return render(
    <Markdown
      remarkPlugins={[remarkGfm, remarkPlanAdmonition]}
      rehypePlugins={[[rehypeSanitize, SCHEMA]]}
    >
      {md}
    </Markdown>
  );
}

describe('remarkPlanAdmonition', () => {
  for (const label of ['NOTE', 'TIP', 'IMPORTANT', 'WARNING', 'CAUTION']) {
    it(`renders [!${label}] as plan-admonition with known=true`, () => {
      const { container } = renderMd(`> [!${label}]\n> Body line.\n`);
      const el = container.querySelector('.plan-admonition');
      expect(el).not.toBeNull();
      expect(el!.getAttribute('data-admonition-label')).toBe(label);
      expect(el!.getAttribute('data-admonition-known')).toBe('true');
      expect(el!.textContent).toContain('Body line.');
      // Marker line MUST be stripped from the visible body
      expect(el!.textContent).not.toContain(`[!${label}]`);
    });
  }

  it('preserves multi-word [!REVIEW REQUIRED] label', () => {
    const { container } = renderMd(
      '> [!REVIEW REQUIRED]\n> Confirm column type before proceeding.\n'
    );
    const el = container.querySelector('.plan-admonition');
    expect(el).not.toBeNull();
    expect(el!.getAttribute('data-admonition-label')).toBe('REVIEW REQUIRED');
    expect(el!.getAttribute('data-admonition-known')).toBe('true');
  });

  it('marks an off-list label known=false', () => {
    const { container } = renderMd('> [!ROLLBACK PLAN]\n> dump and replay.\n');
    const el = container.querySelector('.plan-admonition');
    expect(el).not.toBeNull();
    expect(el!.getAttribute('data-admonition-label')).toBe('ROLLBACK PLAN');
    expect(el!.getAttribute('data-admonition-known')).toBe('false');
  });

  it('renders inline markdown inside the body', () => {
    const { container } = renderMd(
      '> [!IMPORTANT]\n> See **AuthService** in `core/auth.kt`.\n'
    );
    const el = container.querySelector('.plan-admonition');
    expect(el!.querySelector('strong')?.textContent).toBe('AuthService');
    expect(el!.querySelector('code')?.textContent).toBe('core/auth.kt');
  });

  it('leaves a marker-less blockquote as <blockquote>', () => {
    const { container } = renderMd('> just a quote\n');
    expect(container.querySelector('.plan-admonition')).toBeNull();
    expect(container.querySelector('blockquote')).not.toBeNull();
  });

  it('passes data-admonition-* attributes through rehype-sanitize', () => {
    // Regression guard: defaultSchema strips unknown data-* attrs unless
    // explicitly allowlisted. The schema in PlanDocumentViewer adds them.
    const { container } = renderMd('> [!RISK]\n> sharp edge here.\n');
    const el = container.querySelector('.plan-admonition');
    expect(el!.hasAttribute('data-admonition-label')).toBe(true);
    expect(el!.hasAttribute('data-admonition-known')).toBe(true);
  });

  it('lowercase [!note] is NOT recognised (must be uppercase)', () => {
    const { container } = renderMd('> [!note]\n> body\n');
    expect(container.querySelector('.plan-admonition')).toBeNull();
  });
});
