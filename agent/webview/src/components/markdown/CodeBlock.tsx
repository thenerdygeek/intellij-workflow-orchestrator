import { memo, useState, useCallback, useMemo } from 'react';
import { useShiki } from '@/hooks/useShiki';
import { CopyButton } from '@/components/ui/copy-button';

export interface CodeMeta {
  highlights: Set<number>;
  annotations: Map<number, string>;
}

export function parseCodeMeta(meta: string | undefined | null): CodeMeta {
  const highlights = new Set<number>();
  const annotations = new Map<number, string>();
  if (!meta) return { highlights, annotations };

  // Parse highlight={3,5-7}
  const hlMatch = meta.match(/highlight=\{([^}]+)\}/);
  if (hlMatch && hlMatch[1]) {
    hlMatch[1].split(',').forEach(part => {
      const range = part.trim().split('-');
      if (range.length === 2) {
        const start = parseInt(range[0] ?? '0', 10);
        const end = parseInt(range[1] ?? '0', 10);
        for (let i = start; i <= end; i++) highlights.add(i);
      } else {
        const n = parseInt(range[0] ?? '0', 10);
        if (!isNaN(n)) highlights.add(n);
      }
    });
  }

  // Parse annotation={3:"Bug here",7:"This fixes it"}
  const annMatch = meta.match(/annotation=\{([^}]+)\}/);
  if (annMatch && annMatch[1]) {
    const pairs = annMatch[1].matchAll(/(\d+):"([^"]+)"/g);
    for (const m of pairs) {
      annotations.set(parseInt(m[1] ?? '0', 10), m[2] ?? '');
    }
  }

  return { highlights, annotations };
}

/**
 * Post-process Shiki HTML to inject line highlights and annotation markers.
 * Shiki outputs `<span class="line">...</span>` for each line inside `<pre><code>`.
 */
function applyLineDecorations(html: string, meta: CodeMeta): string {
  if (meta.highlights.size === 0 && meta.annotations.size === 0) return html;

  let lineNumber = 0;
  return html.replace(/<span class="line">/g, (match) => {
    lineNumber++;
    const isHighlighted = meta.highlights.has(lineNumber);
    const annotation = meta.annotations.get(lineNumber);

    let replacement = match;
    if (isHighlighted) {
      replacement = '<span class="line code-line-highlight">';
    }

    // For annotations, we inject a marker span AFTER the line opening tag.
    // We use a data attribute so the annotation tooltip can be rendered via CSS.
    if (annotation) {
      const escapedAnnotation = annotation
        .replace(/&/g, '&amp;')
        .replace(/"/g, '&quot;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
      replacement = (isHighlighted ? '<span class="line code-line-highlight code-line-annotated">' : '<span class="line code-line-annotated">');
      // The annotation icon is appended after the line content ends.
      // We'll handle this differently — inject before the closing </span> of the line.
      // For now, just mark with the class + data attribute; we handle annotation icons separately.
      replacement = replacement.replace('>', ` data-annotation="${escapedAnnotation}">`);
    }

    return replacement;
  });
}

/**
 * Inject annotation icons at the end of annotated lines.
 * We find `</span>` closings for `.code-line-annotated` spans and insert before them.
 */
function injectAnnotationIcons(html: string): string {
  // Replace annotated line spans: find the data-annotation on .code-line-annotated,
  // then inject an icon span before the line's closing </span>.
  // Strategy: split on annotated line markers and rebuild.
  return html.replace(
    /(<span class="line[^"]*code-line-annotated[^"]*" data-annotation="([^"]*)"[^>]*>)([\s\S]*?)(<\/span>(?=\s*(?:<span class="line"|<\/code>)))/g,
    (_, open, annotation, content, close) => {
      const icon = `<span class="code-annotation-icon" title="${annotation}"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display:inline;vertical-align:middle;margin-left:8px;opacity:0.6;color:var(--accent-edit,#6366f1)"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg><span class="code-annotation-tooltip">${annotation}</span></span>`;
      return open + content + icon + close;
    }
  );
}

interface CodeBlockProps {
  code: string;
  language: string;
  isStreaming?: boolean;
  meta?: string | null;
}

export const CodeBlock = memo(function CodeBlock({
  code,
  language,
  isStreaming = false,
  meta,
}: CodeBlockProps) {
  const { html: rawHtml, isLoading } = useShiki(code, language);
  const [showLineNumbers, setShowLineNumbers] = useState(false);

  const codeMeta = useMemo(() => parseCodeMeta(meta), [meta]);
  const html = useMemo(() => {
    if (!rawHtml || (codeMeta.highlights.size === 0 && codeMeta.annotations.size === 0)) {
      return rawHtml;
    }
    const decorated = applyLineDecorations(rawHtml, codeMeta);
    return injectAnnotationIcons(decorated);
  }, [rawHtml, codeMeta]);

  const handleApply = useCallback(() => {
    // Send apply action to IDE via bridge
    /* eslint-disable @typescript-eslint/no-explicit-any */
    (window as any)._applyCode?.(code, language);
    /* eslint-enable @typescript-eslint/no-explicit-any */
  }, [code, language]);

  const lines = code.split('\n');

  // Streaming skeleton for open code fences
  if (isStreaming && !code.trim()) {
    return (
      <div className="relative my-2 rounded-md border border-[var(--border)] bg-[var(--code-bg)] overflow-hidden">
        <div className="p-3 space-y-2">
          <span className="block h-3 w-3/4 animate-pulse rounded bg-[var(--fg-muted)]/20" />
          <span className="block h-3 w-1/2 animate-pulse rounded bg-[var(--fg-muted)]/20" />
          <span className="block h-3 w-2/3 animate-pulse rounded bg-[var(--fg-muted)]/20" />
        </div>
      </div>
    );
  }

  return (
    <div className="relative my-2 rounded-md border border-[var(--border)] bg-[var(--code-bg)] overflow-hidden">
      {/* Header */}
      <div className="flex items-center justify-between border-b border-[var(--border)] px-3 py-1">
        <span className="text-[10px] font-medium uppercase text-[var(--fg-muted)]">
          {language || 'code'}
        </span>
        <div className="flex items-center gap-1">
          {/* Line numbers toggle */}
          <button
            onClick={() => setShowLineNumbers(!showLineNumbers)}
            className="rounded p-1 text-[var(--fg-muted)] hover:bg-[var(--hover-bg)] hover:text-[var(--fg)]"
            title="Toggle line numbers"
            aria-label="Toggle line numbers"
          >
            <span className="text-[10px] font-mono font-bold">#</span>
          </button>

          {/* Copy button */}
          <CopyButton text={code} label="Copy code" />

          {/* Apply button */}
          <button
            onClick={handleApply}
            className="rounded p-1 text-[var(--fg-muted)] hover:bg-[var(--hover-bg)] hover:text-[var(--fg)]"
            title="Apply code"
            aria-label="Apply code"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M12 5v14" />
              <path d="m19 12-7 7-7-7" />
            </svg>
          </button>
        </div>
      </div>

      {/* Code content */}
      {isLoading ? (
        <div className="p-3 space-y-2">
          <span className="block h-3 w-3/4 animate-pulse rounded bg-[var(--fg-muted)]/20" />
          <span className="block h-3 w-1/2 animate-pulse rounded bg-[var(--fg-muted)]/20" />
        </div>
      ) : (
        <div className="flex overflow-x-auto">
          {showLineNumbers && (
            <div className="flex-shrink-0 select-none border-r border-[var(--border)] bg-[var(--code-bg)] px-2 py-3 text-right">
              {lines.map((_, i) => (
                <div
                  key={i}
                  className="font-[var(--font-mono,'JetBrains_Mono',monospace)] text-[11px] leading-[1.6] text-[var(--fg-muted)]/40"
                >
                  {i + 1}
                </div>
              ))}
            </div>
          )}
          <div
            className="min-w-0 flex-1 overflow-x-auto p-3 [&_pre]:!m-0 [&_pre]:!bg-transparent [&_pre]:!p-0 [&_code]:font-[var(--font-mono,'JetBrains_Mono',monospace)] [&_code]:text-[12px] [&_code]:leading-[1.6]"
            dangerouslySetInnerHTML={{ __html: html }}
          />
        </div>
      )}
    </div>
  );
});
