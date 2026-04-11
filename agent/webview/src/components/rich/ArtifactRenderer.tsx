import { useEffect, useRef, useState, useCallback, useMemo } from 'react';
import { RichBlock } from './RichBlock';
import { useThemeStore } from '@/stores/themeStore';
import { kotlinBridge } from '@/bridge/jcef-bridge';

// ── Constants ──

/** Bridge actions the sandbox is allowed to invoke */
const ALLOWED_BRIDGE_ACTIONS = new Set(['navigateToFile']);

/** Sandbox HTML is served from the webview dist root */
const SANDBOX_URL = new URL('/artifact-sandbox.html', window.location.origin).href;

// ── Props ──

interface ArtifactRendererProps {
  source: string;
  title?: string;
  /**
   * Correlation id for the async render round-trip. Forwarded to the iframe
   * in the `render` postMessage, echoed back in `rendered`/`error` postbacks,
   * and reported to Kotlin via `kotlinBridge.reportArtifactResult(...)` so
   * the suspended `render_artifact` tool call resumes with a structured result.
   *
   * Optional for legacy callers that render artifacts without a backing tool
   * call (inline ```artifact``` markdown fences, dev showcase) — when absent
   * the component renders locally but does not phone home to Kotlin.
   */
  renderId?: string;
}

// ── Component ──

export function ArtifactRenderer({ source, title, renderId }: ArtifactRendererProps) {
  const isDark = useThemeStore((s) => s.isDark);
  const cssVariables = useThemeStore((s) => s.cssVariables);

  const iframeRef = useRef<HTMLIFrameElement>(null);
  const readyRef = useRef(false);
  const pendingRenderRef = useRef<string | null>(null);
  // Guard against double-reporting (iframe may fire both 'error' and 'rendered'
  // in edge cases, or we retry then the original pending postback arrives late).
  // Kotlin side is already idempotent (unknown renderId is a no-op), but
  // we also suppress local state churn.
  const reportedRef = useRef(false);

  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  const [iframeHeight, setIframeHeight] = useState(200);

  // ── Theme message ──

  const themeMessage = useMemo(() => ({
    type: 'theme' as const,
    isDark,
    colors: { ...cssVariables },
    projectName: '',
  }), [isDark, cssVariables]);

  // ── Send helpers ──

  const postToSandbox = useCallback((msg: unknown) => {
    iframeRef.current?.contentWindow?.postMessage(msg, '*');
  }, []);

  const sendRender = useCallback((src: string) => {
    if (readyRef.current) {
      postToSandbox({ type: 'render', source: src, scope: {}, renderId });
    } else {
      pendingRenderRef.current = src;
    }
  }, [postToSandbox, renderId]);

  // Forward the sandbox's render outcome to Kotlin exactly once.
  // Kotlin's ArtifactResultRegistry is keyed by renderId and tolerates
  // late / duplicate reports, but we also guard here to avoid racing our
  // own state updates on retries. No-op when renderId is absent (inline
  // markdown fence / dev showcase have no suspended tool call to resolve).
  const reportToKotlin = useCallback(
    (payload: Omit<Parameters<typeof kotlinBridge.reportArtifactResult>[0], 'renderId'>) => {
      if (!renderId) return;
      if (reportedRef.current) return;
      reportedRef.current = true;
      kotlinBridge.reportArtifactResult({ renderId, ...payload });
    },
    [renderId],
  );

  // Guard against stale iframe messages when the component instance is reused
  // with a new renderId (React strict-mode double invoke, future prop-update
  // paths, user-driven retry with a new id). The sandbox stamps every outbound
  // rendered/error with its currentRenderId; we drop anything that doesn't
  // match the current props. Normalizes absent/null/undefined to null.
  const isForCurrentRender = useCallback(
    (incomingRenderId: unknown): boolean => {
      const fromSandbox = typeof incomingRenderId === 'string' ? incomingRenderId : null;
      const expected = renderId ?? null;
      return fromSandbox === expected;
    },
    [renderId],
  );

  // ── postMessage listener ──

  useEffect(() => {
    const handler = (e: MessageEvent) => {
      // Only accept messages from our iframe
      if (!iframeRef.current || e.source !== iframeRef.current.contentWindow) return;

      const data = e.data;
      if (data == null || typeof data !== 'object' || typeof data.type !== 'string') return;

      switch (data.type) {
        case 'ready':
          readyRef.current = true;
          // Send theme first, then render
          postToSandbox(themeMessage);
          if (pendingRenderRef.current != null) {
            postToSandbox({ type: 'render', source: pendingRenderRef.current, scope: {}, renderId });
            pendingRenderRef.current = null;
          } else {
            postToSandbox({ type: 'render', source, scope: {}, renderId });
          }
          break;

        case 'rendered': {
          // Reject stale outcomes from a previous render. Happens when the
          // component instance is reused (source/renderId prop change) while
          // the iframe is still processing the old render — the late postback
          // must not be attributed to the new renderId. Kotlin side is
          // idempotent on unknown renderId, but misattribution would corrupt
          // the self-repair loop by resolving the wrong deferred.
          if (!isForCurrentRender(data.renderId)) break;
          setIsLoading(false);
          setError(null);
          if (typeof data.height === 'number' && data.height > 0) {
            setIframeHeight(data.height);
          }
          // Report success back to Kotlin so RenderArtifactTool's suspended
          // coroutine resumes with Success (LLM sees "rendered at Npx height").
          reportToKotlin({
            status: 'success',
            heightPx: typeof data.height === 'number' ? data.height : undefined,
          });
          break;
        }

        case 'error': {
          // See 'rendered' above — same stale-outcome guard.
          if (!isForCurrentRender(data.renderId)) break;
          setIsLoading(false);
          setError(new Error(
            `[${data.phase ?? 'unknown'}] ${data.message ?? 'Artifact render failed'}`,
          ));
          // Report failure. The sandbox enriches payloads with missingSymbols
          // (parsed from ReferenceError messages) when available, so the
          // tool-result message handed to the LLM is actionable.
          const missing = Array.isArray(data.missingSymbols)
            ? (data.missingSymbols as unknown[]).filter(
                (s): s is string => typeof s === 'string' && s.length > 0,
              )
            : undefined;
          reportToKotlin({
            status: 'error',
            phase: typeof data.phase === 'string' ? data.phase : 'runtime',
            message: typeof data.message === 'string' ? data.message : 'Artifact render failed',
            missingSymbols: missing,
            line: typeof data.line === 'number' ? data.line : undefined,
          });
          break;
        }

        case 'bridge': {
          const action = data.action as string | undefined;
          if (action && ALLOWED_BRIDGE_ACTIONS.has(action)) {
            const args = Array.isArray(data.args) ? data.args : [];
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            const bridgeFn = (kotlinBridge as any)[action];
            if (typeof bridgeFn === 'function') {
              bridgeFn(...args);
            }
          }
          break;
        }

        case 'console':
          if (data.level === 'error') {
            const args = Array.isArray(data.args) ? data.args : [data.message ?? ''];
            console.warn('[Artifact]', ...args);
          }
          break;
      }
    };

    window.addEventListener('message', handler);
    return () => window.removeEventListener('message', handler);
  }, [postToSandbox, themeMessage]);

  // ── Re-render on source change ──

  useEffect(() => {
    setIsLoading(true);
    setError(null);
    // Each distinct render (new source or new renderId) gets a fresh
    // report slot. Without this, a user-triggered retry would see the
    // first report flagged and silently drop the new outcome.
    reportedRef.current = false;
    sendRender(source);
  }, [source, renderId, sendRender]);

  // ── Re-send theme on theme change ──

  useEffect(() => {
    if (readyRef.current) {
      postToSandbox(themeMessage);
    }
  }, [themeMessage, postToSandbox]);

  // ── Retry handler ──

  const handleRetry = useCallback(() => {
    setIsLoading(true);
    setError(null);
    readyRef.current = false;
    pendingRenderRef.current = null;
    // User-driven retry — allow a fresh report for this same renderId.
    // Note: this is a UI-only retry (iframe reload), the Kotlin-side
    // tool call is long since resumed. The retry is purely cosmetic.
    reportedRef.current = true; // Suppress reports from stale iframe sessions
    // Force iframe reload by updating src
    if (iframeRef.current) {
      iframeRef.current.src = SANDBOX_URL;
    }
  }, []);

  // ── Render ──

  return (
    <RichBlock
      type="artifact"
      source={source}
      isLoading={false}
      error={error}
      onRetry={handleRetry}
    >
      {title && (
        <div className="border-b border-[var(--border)] px-3 py-1.5 text-xs font-medium text-[var(--fg-secondary)]">
          {title}
        </div>
      )}
      <div className="relative" style={{ minHeight: isLoading ? 120 : undefined }}>
        {/* Loading overlay — shown on top of the iframe while sandbox initializes */}
        {isLoading && (
          <div className="absolute inset-0 z-10 flex flex-col items-center justify-center gap-2 bg-[var(--code-bg)]">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="animate-spin" style={{ color: 'var(--fg-muted)' }}>
              <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83" />
            </svg>
            <span className="text-xs text-[var(--fg-muted)]">Loading interactive content…</span>
          </div>
        )}
        {/* Iframe ALWAYS in DOM so sandbox can initialize and send 'ready' */}
        <iframe
          ref={iframeRef}
          src={SANDBOX_URL}
          sandbox="allow-scripts"
          title="Interactive artifact"
          className="w-full border-0"
          style={{
            height: `${iframeHeight}px`,
            transition: 'height 200ms ease-out',
            background: 'transparent',
            opacity: isLoading ? 0 : 1,
          }}
        />
      </div>
    </RichBlock>
  );
}
