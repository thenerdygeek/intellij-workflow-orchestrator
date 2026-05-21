import { visit, SKIP } from 'unist-util-visit';
import type { Root, Text, Link, Parent } from 'mdast';

// Canonical Jira pattern — mirrors core/util/TicketKeyExtractor.kt
// Matches issue keys like AFTER8TE-912 or WORK-1234.
const JIRA_RE = /\b[A-Z][A-Z0-9]+-\d+\b/g;

// Conservative file-path pattern. Requires a known source-file extension and
// allows an optional `:line` or `:line-range` suffix. Underscores are allowed
// inside path segments. The trailing extension list intentionally excludes
// generic suffixes like `txt` and `log` that produce too many false positives.
const FILE_RE =
  /(?<![\w./-])([\w./-]+\.(?:kt|java|ts|tsx|js|jsx|md|yml|yaml|gradle|kts|py|html|css|json|xml|properties))(?::(\d+)(?:-(\d+))?)?(?![\w./-])/g;

interface Match {
  index: number;
  length: number;
  href: string;
  text: string;
}

function collectMatches(text: string): Match[] {
  const out: Match[] = [];
  // Jira keys
  for (const m of text.matchAll(JIRA_RE)) {
    if (m.index == null) continue;
    out.push({
      index: m.index,
      length: m[0].length,
      href: `jira:${m[0]}`,
      text: m[0],
    });
  }
  // File paths (with optional :line suffix)
  for (const m of text.matchAll(FILE_RE)) {
    if (m.index == null) continue;
    out.push({
      index: m.index,
      length: m[0].length,
      href: `file:${m[0]}`,
      text: m[0],
    });
  }
  // Sort by index; on overlap prefer the longer match.
  out.sort((a, b) => a.index - b.index || b.length - a.length);
  // Drop overlapping matches.
  const dedup: Match[] = [];
  let cursor = -1;
  for (const m of out) {
    if (m.index < cursor) continue;
    dedup.push(m);
    cursor = m.index + m.length;
  }
  return dedup;
}

function splitTextNode(node: Text): Array<Text | Link> | null {
  const matches = collectMatches(node.value);
  if (matches.length === 0) return null;

  const parts: Array<Text | Link> = [];
  let cursor = 0;
  for (const m of matches) {
    if (m.index > cursor) {
      parts.push({ type: 'text', value: node.value.slice(cursor, m.index) });
    }
    parts.push({
      type: 'link',
      url: m.href,
      title: null,
      children: [{ type: 'text', value: m.text }],
    });
    cursor = m.index + m.length;
  }
  if (cursor < node.value.length) {
    parts.push({ type: 'text', value: node.value.slice(cursor) });
  }
  return parts;
}

// Node types whose descendant text nodes must NOT be linkified.
const SKIP_PARENT_TYPES = new Set<string>([
  'code',
  'inlineCode',
  'link',
  'linkReference',
]);

export function remarkChatLinkify() {
  return (tree: Root): void => {
    visit(
      tree,
      'text',
      (node: Text, index: number | undefined, parent: Parent | undefined) => {
        if (parent == null || index == null) return;
        if (SKIP_PARENT_TYPES.has(parent.type)) return;
        const replacement = splitTextNode(node);
        if (!replacement) return;
        // Splice the replacement nodes into the parent's children in place.
        (parent.children as Array<Text | Link>).splice(index, 1, ...replacement);
        // Skip the freshly inserted nodes (we don't want to recurse into the
        // new `link` children — their text is the friendly label and is not
        // subject to a second linkify pass).
        return [SKIP, index + replacement.length];
      },
    );
  };
}
