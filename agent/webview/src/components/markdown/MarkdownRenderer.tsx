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
    const isBlock = className?.startsWith('language-');
    if (isBlock) {
      const language = className?.replace('language-', '') ?? '';
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
        case 'html-interactive':
        case 'visualization':
        case 'viz':
          return <InteractiveHtml htmlContent={codeString} />;
      }

      // Default: Shiki CodeBlock
      return <CodeBlock code={codeString} language={language} isStreaming={isStreaming} meta={meta} />;
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
    return isStreaming ? closeOpenFences(content) : content;
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
