import { memo, useMemo } from 'react';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeRaw from 'rehype-raw';
import { visit } from 'unist-util-visit';
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

function hasOpenCodeFence(text: string): boolean {
  const fencePattern = /^```/gm;
  const matches = text.match(fencePattern);
  if (!matches) return false;
  return matches.length % 2 !== 0;
}

function closeOpenFences(text: string): string {
  if (hasOpenCodeFence(text)) {
    return text + '\n```';
  }
  return text;
}

/**
 * Unicode character class pattern for box-drawing, block elements, and
 * common ASCII-art connector characters that signal monospace layout.
 *
 * Ranges covered:
 *   U+2500–U+257F  Box Drawing
 *   U+2580–U+259F  Block Elements
 *   U+2190–U+21FF  Arrows (─→ ──▶ etc.)
 *   U+25A0–U+25FF  Geometric Shapes (▲▼◆●○ etc.)
 *   U+2580–U+259F  Block elements (█░▒▓)
 *
 * A line is considered "ASCII art" if it contains 2+ of these characters
 * (avoids false positives on single arrows in prose like "A → B").
 */
const ASCII_ART_CHAR_RE = /[\u2500-\u257F\u2580-\u259F\u2190-\u21FF\u25A0-\u25FF]/g;

function isAsciiArtLine(line: string): boolean {
  const matches = line.match(ASCII_ART_CHAR_RE);
  return matches !== null && matches.length >= 2;
}

/**
 * Pre-process markdown to wrap unfenced ASCII art in <pre> tags.
 *
 * LLMs frequently emit box-drawing diagrams, trees, and ASCII charts as
 * plain text (no code fences). Markdown collapses these into `<p>` tags
 * with a proportional font, destroying all alignment.
 *
 * This function scans for runs of consecutive lines containing box-drawing
 * characters that are NOT already inside a code fence, and wraps them in
 * raw `<pre class="ascii-art">` tags. Since rehypeRaw is enabled, these
 * render as monospace preformatted text that blends naturally into the
 * message — no CodeBlock chrome (no header, no copy/apply buttons).
 */
function autoFenceAsciiArt(text: string): string {
  const lines = text.split('\n');
  const result: string[] = [];
  let inFence = false;
  let inHtmlPre = false;
  let artBuffer: string[] = [];

  const flushArtBuffer = () => {
    if (artBuffer.length > 0) {
      // Wrap each line in a div with overflow:hidden — the xterm.js technique.
      // FiraCode Nerd Font's box-drawing glyphs extend beyond the line box
      // (intentionally, for terminal seamless rendering). In CSS, this causes
      // overlap between adjacent rows on Retina displays. Per-row clipping
      // prevents the bleed while keeping the glyph edges flush.
      result.push('<pre class="ascii-art">');
      for (const artLine of artBuffer) {
        // Escape < and > in content to prevent HTML injection, but preserve the text
        const safe = artLine.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
        result.push(`<div class="ascii-row">${safe}</div>`);
      }
      result.push('</pre>');
      artBuffer = [];
    }
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]!;

    // Track code fence state — don't touch content already inside fences
    if (line.trimStart().startsWith('```')) {
      flushArtBuffer();
      inFence = !inFence;
      result.push(line);
      continue;
    }

    // Track raw <pre> blocks we emitted — don't re-process them
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
      // Non-art line — flush any buffered art, then emit normally
      flushArtBuffer();
      result.push(line);
    }
  }

  // Flush any trailing art block
  flushArtBuffer();

  return result.join('\n');
}

/**
 * Remark plugin to preserve code fence meta strings through the AST.
 * react-markdown doesn't pass meta by default; this plugin attaches it
 * as `data.meta` on the code node so it survives through to rehype.
 */
function remarkCodeMeta() {
  return (tree: any) => {
    visit(tree, 'code', (node: any) => {
      if (node.meta) {
        node.data = node.data || {};
        node.data.meta = node.meta;
        // Also set hProperties so it appears on the HTML element
        node.data.hProperties = node.data.hProperties || {};
        node.data.hProperties['data-meta'] = node.meta;
      }
    });
  };
}

/* eslint-disable @typescript-eslint/no-explicit-any */
function createMarkdownComponents(isStreaming: boolean): any {
  return {
  code({ className, children, node, ...props }: any) {
    // Detect block code: react-markdown renders fenced code as <pre><code>,
    // with className="language-xxx" when a language is specified. A bare ```
    // fence (no language) still renders as <pre><code> but without className.
    // We detect block by checking for language- prefix OR parent <pre> tag.
    const hasLanguage = className?.startsWith('language-');
    const isBlock = hasLanguage || node?.tagName === 'code' && node?.parent?.tagName === 'pre'
      || (typeof children === 'string' && children.includes('\n'));
    if (isBlock) {
      const language = hasLanguage ? (className?.replace('language-', '') ?? '') : '';
      const codeString = String(children).replace(/\n$/, '');
      // Extract meta string from the AST node (set by remarkCodeMeta plugin)
      const meta: string | undefined = node?.properties?.['data-meta'] ?? node?.data?.meta ?? props['data-meta'];

      // Route special code fence languages to rich components
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

      // Default: Shiki CodeBlock (language defaults to 'code' for bare fences)
      return <CodeBlock code={codeString} language={language || 'code'} isStreaming={isStreaming} meta={meta} />;
    }
    return (
      <code
        className="rounded bg-[var(--code-bg)] px-1 py-0.5 font-[var(--font-mono,'JetBrains_Mono',monospace)] text-[12px]"
        {...props}
      >
        {children}
      </code>
    );
  },

  // Override <pre> to prevent double-wrapping: react-markdown renders fenced code
  // as <pre><code>…</code></pre>, but our code() component already returns a
  // fully-wrapped CodeBlock/RichBlock. Rendering a bare <pre> avoids nesting.
  // However, preserve <pre class="ascii-art"> blocks from the auto-fencer.
  pre({ children, className, ...props }: any) {
    if (className === 'ascii-art') {
      return (
        <pre className="ascii-art" {...props}>
          {children}
        </pre>
      );
    }
    return <>{children}</>;
  },

  a({ href, children, ...props }: any) {
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
  },

  table({ children, ...props }: any) {
    return (
      <div className="my-2 overflow-x-auto rounded border border-[var(--border)]">
        <table className="w-full text-[12px]" {...props}>
          {children}
        </table>
      </div>
    );
  },

  th({ children, ...props }: any) {
    return (
      <th
        className="border-b border-[var(--border)] bg-[var(--toolbar-bg)] px-3 py-1.5 text-left text-[11px] font-semibold text-[var(--fg-secondary)]"
        {...props}
      >
        {children}
      </th>
    );
  },

  td({ children, ...props }: any) {
    return (
      <td
        className="border-b border-[var(--divider-subtle)] px-3 py-1.5"
        {...props}
      >
        {children}
      </td>
    );
  },

  blockquote({ children, ...props }: any) {
    return (
      <blockquote
        className="my-2 border-l-2 border-[var(--accent,#6366f1)] pl-3 text-[var(--fg-secondary)] italic"
        {...props}
      >
        {children}
      </blockquote>
    );
  },

  hr(props: any) {
    return (
      <hr
        className="my-3 border-[var(--divider-subtle)]"
        {...props}
      />
    );
  },

  ul({ children, ...props }: any) {
    return (
      <ul className="my-1 ml-4 list-disc space-y-0.5" {...props}>
        {children}
      </ul>
    );
  },

  ol({ children, ...props }: any) {
    return (
      <ol className="my-1 ml-4 list-decimal space-y-0.5" {...props}>
        {children}
      </ol>
    );
  },

  p({ children, ...props }: any) {
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
  },
};
}
/* eslint-enable @typescript-eslint/no-explicit-any */

export const MarkdownRenderer = memo(function MarkdownRenderer({
  content,
  isStreaming = false,
}: MarkdownRendererProps) {
  const processedContent = useMemo(() => {
    let text = autoFenceAsciiArt(content);
    if (isStreaming) text = closeOpenFences(text);
    return text;
  }, [content, isStreaming]);

  const components = useMemo(() => createMarkdownComponents(isStreaming), [isStreaming]);

  return (
    <div className="markdown-body text-[13px] leading-relaxed">
      <Markdown
        remarkPlugins={[remarkGfm, remarkCodeMeta]}
        rehypePlugins={[rehypeRaw]}
        components={components}
      >
        {processedContent}
      </Markdown>
      {isStreaming && hasOpenCodeFence(content) && (
        <div className="relative my-2 rounded-md border border-[var(--border)] bg-[var(--code-bg)] overflow-hidden p-3">
          <span className="block h-3 w-24 animate-pulse rounded bg-[var(--fg-muted)]/20" />
        </div>
      )}
    </div>
  );
});
