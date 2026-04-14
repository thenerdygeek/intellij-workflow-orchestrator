import { useEffect, useRef, useState, useCallback } from 'react';
import { RichBlock } from './RichBlock';
import { kotlinBridge, isJcefEnvironment } from '@/bridge/jcef-bridge';

// ── Singleton lazy-load for KaTeX ──

type KaTeXModule = { default: { renderToString: (latex: string, options?: Record<string, unknown>) => string } };

let katexModulePromise: Promise<KaTeXModule> | null = null;
let katexCssLoaded = false;

function loadKaTeX(): Promise<KaTeXModule> {
  if (!katexModulePromise) {
    katexModulePromise = Promise.all([
      import('katex') as Promise<KaTeXModule>,
      katexCssLoaded
        ? Promise.resolve()
        : import('katex/dist/katex.min.css').then(() => {
            katexCssLoaded = true;
          }),
    ]).then(([m]) => m);
  }
  return katexModulePromise;
}

// ── MathBlock component ──

interface MathBlockProps {
  latex: string;
  displayMode?: boolean;
}

export function MathBlock({ latex, displayMode = true }: MathBlockProps) {
  const [html, setHtml] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const renderIdRef = useRef(0);

  const renderMath = useCallback(async () => {
    const currentRender = ++renderIdRef.current;
    setIsLoading(true);
    setError(null);

    try {
      const katexModule = await loadKaTeX();

      if (currentRender !== renderIdRef.current) return;

      const rendered = katexModule.default.renderToString(latex, {
        displayMode,
        throwOnError: false,
        output: 'htmlAndMathml',
      });

      if (currentRender !== renderIdRef.current) return;

      setHtml(rendered);
      setIsLoading(false);
    } catch (err) {
      if (currentRender !== renderIdRef.current) return;
      setError(err instanceof Error ? err : new Error(String(err)));
      setIsLoading(false);
    }
  }, [latex, displayMode]);

  useEffect(() => {
    void renderMath();
  }, [renderMath]);

  const handleCopyLatex = useCallback(() => {
    if (isJcefEnvironment()) {
      kotlinBridge.copyToClipboard(latex);
    } else {
      void navigator.clipboard.writeText(latex);
    }
  }, [latex]);

  // Inline mode — render as span without RichBlock wrapper
  if (!displayMode) {
    if (isLoading) {
      return (
        <span
          className="inline-block h-4 w-12 animate-pulse rounded bg-[var(--hover-overlay)]"
          aria-label="Loading math"
        />
      );
    }

    if (error) {
      return (
        <span className="text-[var(--error)] text-xs" title={error.message}>
          {latex}
        </span>
      );
    }

    return (
      <span
        ref={containerRef}
        className="inline-math"
        dangerouslySetInnerHTML={{ __html: html ?? '' }}
      />
    );
  }

  // Block mode — render inside RichBlock with Copy LaTeX button
  return (
    <RichBlock
      type="math"
      source={latex}
      isLoading={isLoading}
      error={error}
      onRetry={() => void renderMath()}
    >
      <div className="relative p-4">
        <div
          ref={containerRef}
          className="flex items-center justify-center overflow-x-auto text-[var(--fg)]"
          dangerouslySetInnerHTML={{ __html: html ?? '' }}
        />
        <button
          onClick={handleCopyLatex}
          title="Copy LaTeX"
          aria-label="Copy LaTeX source"
          className="absolute top-2 right-2 rounded px-2 py-1 text-xs text-[var(--fg-muted)] transition-colors hover:bg-[var(--hover-overlay)] hover:text-[var(--fg)]"
        >
          Copy LaTeX
        </button>
      </div>
    </RichBlock>
  );
}
