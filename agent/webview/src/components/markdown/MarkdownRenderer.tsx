import { memo, useMemo } from 'react';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeRaw from 'rehype-raw';
import DOMPurify from 'dompurify';
import { CodeBlock } from '@/components/markdown/CodeBlock';

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

/* eslint-disable @typescript-eslint/no-explicit-any */
function createMarkdownComponents(isStreaming: boolean): any {
  return {
  code({ className, children, ...props }: any) {
    const isBlock = className?.startsWith('language-');
    if (isBlock) {
      const language = className?.replace('language-', '') ?? '';
      const codeString = String(children).replace(/\n$/, '');
      return <CodeBlock code={codeString} language={language} isStreaming={isStreaming} />;
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
  const sanitizedContent = useMemo(() => {
    const processedContent = isStreaming ? closeOpenFences(content) : content;
    return DOMPurify.sanitize(processedContent);
  }, [content, isStreaming]);

  const components = useMemo(() => createMarkdownComponents(isStreaming), [isStreaming]);

  return (
    <div className="markdown-body text-[13px] leading-relaxed">
      <Markdown
        remarkPlugins={[remarkGfm]}
        rehypePlugins={[rehypeRaw]}
        components={components}
      >
        {sanitizedContent}
      </Markdown>
      {isStreaming && hasOpenCodeFence(content) && (
        <div className="relative my-2 rounded-md border border-[var(--border)] bg-[var(--code-bg)] overflow-hidden p-3">
          <span className="block h-3 w-24 animate-pulse rounded bg-[var(--fg-muted)]/20" />
        </div>
      )}
    </div>
  );
});
