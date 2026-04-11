import { memo, useMemo } from 'react';
import { Streamdown, useIsCodeFenceIncomplete } from 'streamdown';
import remarkGfm from 'remark-gfm';
import rehypeRaw from 'rehype-raw';
import { visit, SKIP } from 'unist-util-visit';
import { CodeBlock } from '@/components/markdown/CodeBlock';
import { MermaidDiagram } from '@/components/rich/MermaidDiagram';
import { ChartView } from '@/components/rich/ChartView';
import { FlowDiagram } from '@/components/rich/FlowDiagram';
import { MathBlock } from '@/components/rich/MathBlock';
import { DiffHtml } from '@/components/rich/DiffHtml';
import { AnsiOutput } from '@/components/rich/AnsiOutput';
import { InteractiveHtml } from '@/components/rich/InteractiveHtml';
import { DataTable } from '@/components/rich/DataTable';
import { CollapsibleOutput } from '@/components/rich/CollapsibleOutput';
import { ProgressView } from '@/components/rich/ProgressView';
import { TimelineView } from '@/components/rich/TimelineView';
import { ImageView } from '@/components/rich/ImageView';
import { ArtifactRenderer } from '@/components/rich/ArtifactRenderer';

interface MarkdownRendererProps {
  content: string;
  isStreaming?: boolean;
}

/* ─────────────────────────────────────────────────────────────────────────────
 * ASCII-art preprocessor (kept from the previous implementation).
 * LLMs frequently emit box-drawing diagrams as plain text; without this pass
 * markdown would collapse them into proportional-font <p> tags and destroy
 * the alignment.
 * ──────────────────────────────────────────────────────────────────────────── */

const ASCII_ART_CHAR_RE = /[\u2500-\u257F\u2580-\u259F]/g;

function isAsciiArtLine(line: string): boolean {
  const matches = line.match(ASCII_ART_CHAR_RE);
  return matches !== null && matches.length >= 3;
}

function autoFenceAsciiArt(text: string): string {
  const lines = text.split('\n');
  const result: string[] = [];
  let inFence = false;
  let inHtmlPre = false;
  let artBuffer: string[] = [];

  const flushArtBuffer = () => {
    if (artBuffer.length > 0) {
      result.push('<pre class="ascii-art">');
      for (const artLine of artBuffer) {
        const safe = artLine.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
        result.push(`<div class="ascii-row">${safe}</div>`);
      }
      result.push('</pre>');
      artBuffer = [];
    }
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]!;
    if (line.trimStart().startsWith('```')) {
      flushArtBuffer();
      inFence = !inFence;
      result.push(line);
      continue;
    }
    if (line.includes('<pre class="ascii-art">')) {
      inHtmlPre = true;
      result.push(line);
      continue;
    }
    if (line.includes('</pre>') && inHtmlPre) {
      inHtmlPre = false;
      result.push(line);
      continue;
    }
    if (inFence || inHtmlPre) {
      result.push(line);
      continue;
    }
    if (isAsciiArtLine(line)) {
      artBuffer.push(line);
    } else {
      flushArtBuffer();
      result.push(line);
    }
  }
  flushArtBuffer();
  return result.join('\n');
}

/* ─────────────────────────────────────────────────────────────────────────────
 * remark plugin to preserve code fence meta strings (e.g. highlight={1,3-5})
 * through the AST so the CodeBlock can apply line decorations.
 * ──────────────────────────────────────────────────────────────────────────── */
function remarkCodeMeta() {
  return (tree: any) => {
    visit(tree, 'code', (node: any) => {
      if (node.meta) {
        node.data = node.data || {};
        node.data.meta = node.meta;
        node.data.hProperties = node.data.hProperties || {};
        node.data.hProperties['data-meta'] = node.meta;
      }
    });
  };
}

/* ─────────────────────────────────────────────────────────────────────────────
 * rehypeWordFade: wrap each word in `<span class="sd-word">` so CSS can fade
 * new words in as they stream. Whitespace between words stays as plain text
 * so copy/paste retains natural spacing.
 *
 * Skipped subtrees: `<code>`, `<pre>`, and any span that's already an
 * `sd-word` (the plugin is idempotent if Streamdown ever re-runs it on the
 * same tree). Skipping code preserves syntax-highlighting integrity and
 * avoids animating code that's already visually noisy.
 *
 * Animation mechanics: CSS `animation` fires once on element mount, so only
 * NEWLY mounted word spans fade in — existing spans (words already in the
 * DOM from a previous token) don't re-animate. React reconciliation handles
 * append-only streaming correctly because the word spans' positions are
 * stable as new words are added at the end of a block.
 * ──────────────────────────────────────────────────────────────────────────── */
function rehypeWordFade() {
  return (tree: any) => {
    visit(tree, (node: any, index: number | undefined, parent: any) => {
      if (node.type === 'element') {
        if (node.tagName === 'code' || node.tagName === 'pre') return SKIP;
        if (node.tagName === 'span' && hasClass(node, 'sd-word')) return SKIP;
        return;
      }
      if (node.type !== 'text') return;
      if (index === undefined || parent == null) return;

      const text = node.value as string;
      if (!text || !text.trim()) return;

      // Split on whitespace boundaries, keeping the whitespace so we can
      // reinsert it as plain text nodes between the word spans.
      const tokens = text.split(/(\s+)/).filter(t => t.length > 0);
      const newChildren: any[] = tokens.map(token =>
        /^\s+$/.test(token)
          ? { type: 'text', value: token }
          : {
              type: 'element',
              tagName: 'span',
              properties: { className: ['sd-word'] },
              children: [{ type: 'text', value: token }],
            }
      );

      parent.children.splice(index, 1, ...newChildren);
      // SKIP so we don't descend into the just-inserted nodes (which would
      // re-match the text-node branch and loop). The index advance keeps
      // traversal moving past the insertions.
      return [SKIP, index + newChildren.length];
    });
  };
}

function hasClass(node: any, cls: string): boolean {
  const c = node.properties?.className;
  if (Array.isArray(c)) return c.includes(cls);
  if (typeof c === 'string') return c.split(/\s+/).includes(cls);
  return false;
}

/* ─────────────────────────────────────────────────────────────────────────────
 * Module-scope plugin arrays and component map.
 *
 * MUST stay at module scope so Streamdown's per-block React.memo doesn't
 * defeat itself on every render. Streamdown 2.x CHANGELOG documents this
 * exact bug mode with inline literals for linkSafety — same trap applies
 * to any inline `components` / `remarkPlugins` / `rehypePlugins` prop.
 * ──────────────────────────────────────────────────────────────────────────── */

const REMARK_PLUGINS = [remarkGfm, remarkCodeMeta] as const;
const REHYPE_PLUGINS = [rehypeRaw, rehypeWordFade] as const;

/* eslint-disable @typescript-eslint/no-explicit-any */

/**
 * CustomPre: the `pre` override. Streamdown wraps fenced code in <pre><code>.
 * We return a Fragment to unwrap the <pre> so our custom `code` override
 * gets to render either CodeBlock (Shiki) or the streaming-code-plain
 * fallback, based on useIsCodeFenceIncomplete() for the currently-last block.
 *
 * The one exception is the ASCII-art <pre class="ascii-art"> emitted by
 * autoFenceAsciiArt — pass those through unchanged.
 */
function CustomPre({ children, className, ...props }: any) {
  // rehype may hand us `className` as either a string or an array of classes
  // (HAST spec uses array form; some adapters stringify, some don't).
  // Accept both shapes so the ASCII-art passthrough stays reliable across
  // Streamdown / react-markdown / unified adapter versions.
  const classes = Array.isArray(className)
    ? className
    : (className ?? '').split(/\s+/).filter(Boolean);
  if (classes.includes('ascii-art')) {
    return (
      <pre className="ascii-art" {...props}>
        {children}
      </pre>
    );
  }
  return <>{children}</>;
}

/**
 * CodeNode: the `code` override.
 *
 * If we're inside an incomplete code fence (the last block of a streaming
 * message, fence not yet closed), skip Shiki entirely and render plain
 * monospace via .streaming-code-plain. Shiki is ~7x slower than Prism and
 * ~44x slower than highlight.js; running it on every token for an unclosed
 * fence wrecks the streaming feel. This is the pattern from Vercel's
 * Streamdown docs and assistant-ui.
 */
function CodeNode({ className, children, node, ...props }: any) {
  // eslint-disable-next-line react-hooks/rules-of-hooks
  const isIncomplete = useIsCodeFenceIncomplete();

  // Fenced code blocks always get `className="language-<lang>"` (or
  // `language-` for bare fences) from the markdown parser, so that is the
  // only reliable "is this a block?" signal. Removed the old
  // `node.parent?.tagName === 'pre'` check — HAST doesn't carry parent
  // back-pointers by default and the check was dead in Streamdown's
  // adapter. The newline fallback stays as defense-in-depth for plugins
  // that inject code without a language class.
  const hasLanguage = className?.startsWith('language-');
  const isBlock =
    hasLanguage || (typeof children === 'string' && children.includes('\n'));

  if (isBlock) {
    const language = hasLanguage ? (className?.replace('language-', '') ?? '') : '';
    const codeString = String(children).replace(/\n$/, '');
    // Defense-in-depth across HAST adapters: remarkCodeMeta writes
    // `data.hProperties['data-meta']`, which Streamdown's HAST→JSX adapter
    // normalizes to `node.properties['data-meta']` OR `props['data-meta']`
    // depending on version. Keep both paths so `highlight={…}` meta survives
    // a library bump. Do not remove without re-verifying line highlights.
    const meta: string | undefined =
      node?.properties?.['data-meta'] ?? node?.properties?.dataMeta ?? props['data-meta'];

    // Streaming-aware fallback for the currently-open fence.
    if (isIncomplete) {
      return <pre className="streaming-code-plain">{codeString}</pre>;
    }

    // Route special languages to rich components (unchanged from before).
    switch (language) {
      case 'mermaid':
        return <MermaidDiagram source={codeString} />;
      case 'chart':
        return <ChartView source={codeString} />;
      case 'flow':
        return <FlowDiagram source={codeString} />;
      case 'math':
        return <MathBlock latex={codeString} displayMode={true} />;
      case 'diff':
      case 'patch':
        return <DiffHtml diffSource={codeString} />;
      case 'ansi':
        return <AnsiOutput text={codeString} />;
      case 'table':
        return <DataTable tableSource={codeString} />;
      case 'output':
        return <CollapsibleOutput outputSource={codeString} />;
      case 'progress':
        return <ProgressView progressSource={codeString} />;
      case 'timeline':
        return <TimelineView timelineSource={codeString} />;
      case 'image':
        return <ImageView imageSource={codeString} />;
      case 'react':
      case 'artifact':
        return <ArtifactRenderer source={codeString} />;
      case 'html-interactive':
      case 'visualization':
      case 'viz':
        return <InteractiveHtml htmlContent={codeString} />;
    }

    return <CodeBlock code={codeString} language={language || 'code'} isStreaming={false} meta={meta} />;
  }

  return (
    <code
      className="rounded bg-[var(--code-bg)] px-1 py-0.5 font-[var(--font-mono,'JetBrains_Mono',monospace)] text-[12px]"
      {...props}
    >
      {children}
    </code>
  );
}

function AnchorNode({ href, children, ...props }: any) {
  return (
    <a
      href={href}
      className="text-[var(--link)] underline decoration-[var(--link)]/30 hover:decoration-[var(--link)]"
      onClick={(e: React.MouseEvent) => {
        e.preventDefault();
        if (href) {
          (window as any)._navigateToFile?.(href);
        }
      }}
      {...props}
    >
      {children}
    </a>
  );
}

function TableNode({ children, ...props }: any) {
  return (
    <div className="my-2 overflow-x-auto rounded border border-[var(--border)]">
      <table className="w-full text-[12px]" {...props}>
        {children}
      </table>
    </div>
  );
}

function ThNode({ children, ...props }: any) {
  return (
    <th
      className="border-b border-[var(--border)] bg-[var(--toolbar-bg)] px-3 py-1.5 text-left text-[11px] font-semibold text-[var(--fg-secondary)]"
      {...props}
    >
      {children}
    </th>
  );
}

function TdNode({ children, ...props }: any) {
  return (
    <td className="border-b border-[var(--divider-subtle)] px-3 py-1.5" {...props}>
      {children}
    </td>
  );
}

function BlockquoteNode({ children, ...props }: any) {
  return (
    <blockquote
      className="my-2 border-l-2 border-[var(--accent,#6366f1)] pl-3 text-[var(--fg-secondary)] italic"
      {...props}
    >
      {children}
    </blockquote>
  );
}

function HrNode(props: any) {
  return <hr className="my-3 border-[var(--divider-subtle)]" {...props} />;
}

function UlNode({ children, ...props }: any) {
  return (
    <ul className="my-1 ml-4 list-disc space-y-0.5" {...props}>
      {children}
    </ul>
  );
}

function OlNode({ children, ...props }: any) {
  return (
    <ol className="my-1 ml-4 list-decimal space-y-0.5" {...props}>
      {children}
    </ol>
  );
}

function PNode({ children, ...props }: any) {
  if (typeof children === 'string' && children.includes('$')) {
    const parts = children.split(/(\$[^$]+\$)/g);
    return (
      <p className="my-1.5" {...props}>
        {parts.map((part: string, i: number) => {
          if (part.startsWith('$') && part.endsWith('$') && part.length > 2) {
            return <MathBlock key={i} latex={part.slice(1, -1)} displayMode={false} />;
          }
          return <span key={i}>{part}</span>;
        })}
      </p>
    );
  }
  return (
    <p className="my-1.5" {...props}>
      {children}
    </p>
  );
}

const COMPONENTS = {
  code: CodeNode,
  pre: CustomPre,
  a: AnchorNode,
  table: TableNode,
  th: ThNode,
  td: TdNode,
  blockquote: BlockquoteNode,
  hr: HrNode,
  ul: UlNode,
  ol: OlNode,
  p: PNode,
} as const;
/* eslint-enable @typescript-eslint/no-explicit-any */

export const MarkdownRenderer = memo(function MarkdownRenderer({
  content,
  isStreaming = false,
}: MarkdownRendererProps) {
  const processedContent = useMemo(() => autoFenceAsciiArt(content), [content]);

  return (
    <div className="markdown-body text-[13px] leading-relaxed">
      <Streamdown
        mode={isStreaming ? 'streaming' : 'static'}
        isAnimating={isStreaming}
        caret={isStreaming ? 'block' : undefined}
        parseIncompleteMarkdown
        components={COMPONENTS as any}
        remarkPlugins={REMARK_PLUGINS as unknown as any[]}
        rehypePlugins={REHYPE_PLUGINS as unknown as any[]}
      >
        {processedContent}
      </Streamdown>
    </div>
  );
});
