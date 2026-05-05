import type { Plugin } from 'unified';
import type { Root, Blockquote, Paragraph, Text } from 'mdast';
import { visit } from 'unist-util-visit';

/**
 * Recognised admonition label set. Custom labels (any uppercase string in
 * `[!LABEL]`) are also accepted at runtime — this list is just the canonical
 * set we style explicitly. Anything outside it falls back to a generic style.
 *
 * The renderer picks colour + icon by `data-admonition-label`; CSS uses an
 * attribute selector with a fallback `[data-admonition-label]` rule so any
 * label gets at least the generic chrome.
 */
export const KNOWN_ADMONITION_LABELS = new Set([
  'NOTE',
  'TIP',
  'IMPORTANT',
  'WARNING',
  'CAUTION',
  'REVIEW REQUIRED',
  'ASSUMPTION',
  'RISK',
]);

/**
 * Match the admonition marker as the first text node of a blockquote's first
 * paragraph. Allows whitespace before/after the bracket and any uppercase
 * label content (with spaces) — so `[!REVIEW REQUIRED]` works as well as
 * `[!IMPORTANT]`.
 */
const MARKER = /^\s*\[!([A-Z][A-Z0-9 _-]*[A-Z0-9])\]\s*$/m;

/**
 * Remark plugin: turn GitHub-style alert blockquotes into HTML `<div>`
 * elements with `class="plan-admonition"` and `data-admonition-label`.
 *
 * Input markdown:
 *
 *     > [!REVIEW REQUIRED]
 *     > Please confirm the column type before approving.
 *
 * The plugin replaces the `Blockquote` mdast node in-place by attaching
 * `data.hName` / `data.hProperties` (a unified-ecosystem convention) so
 * `react-markdown` renders a `<div>` instead of a `<blockquote>`. The
 * marker line itself is stripped from the body so the rendered text is
 * just the user-visible content.
 *
 * Why a custom plugin instead of a dep:
 * - `remark-gfm` does not handle GitHub alerts.
 * - `remark-github-blockquote-alert` only allows the 5 native labels.
 * - This file is ~50 lines and already supports custom labels (per
 *   `writing-plans` skill: `[!REVIEW REQUIRED]`, `[!ASSUMPTION]`, etc.).
 */
export const remarkPlanAdmonition: Plugin<[], Root> = () => {
  return (tree) => {
    visit(tree, 'blockquote', (node: Blockquote) => {
      // First child must be a paragraph; first child of that paragraph must
      // be a Text node starting with the marker. We deliberately don't peer
      // through bold/italic/etc. — keeps the syntax unambiguous.
      const firstChild = node.children[0];
      if (!firstChild || firstChild.type !== 'paragraph') return;
      const para = firstChild as Paragraph;
      const firstText = para.children[0];
      if (!firstText || firstText.type !== 'text') return;
      const text = firstText as Text;

      const lines = text.value.split('\n');
      const headerLine = lines[0] ?? '';
      const m = headerLine.match(MARKER);
      if (!m) return;

      const rawLabel = m[1]!.trim().replace(/\s+/g, ' ').toUpperCase();
      // Strip the marker line from the body. If anything remains in this
      // text node, keep it as the new value; otherwise drop the text node.
      const remaining = lines.slice(1).join('\n');
      if (remaining.length > 0) {
        text.value = remaining.replace(/^\n+/, '');
      } else {
        para.children.shift();
        // If the paragraph is now empty, drop it from the blockquote so we
        // don't render an empty <p>.
        if (para.children.length === 0) {
          node.children.shift();
        }
      }

      // Hand-off to react-markdown via mdast-util-to-hast convention.
      // (`data.hName` overrides the HTML element; `data.hProperties` becomes
      // attributes on that element. Sanitiser must allow them — we extend
      // the schema in PlanDocumentViewer.)
      const data = (node as Blockquote & {
        data?: { hName?: string; hProperties?: Record<string, unknown> };
      }).data ?? {};
      data.hName = 'div';
      // Hast property names are camelCase even for data-attrs — that's what
      // rehype-sanitize matches against. They serialise back to kebab-case
      // in the rendered HTML (data-admonition-label="...").
      data.hProperties = {
        ...(data.hProperties ?? {}),
        className: ['plan-admonition'],
        dataAdmonitionLabel: rawLabel,
        dataAdmonitionKnown: KNOWN_ADMONITION_LABELS.has(rawLabel) ? 'true' : 'false',
      };
      (node as Blockquote & { data?: object }).data = data;
    });
  };
};
