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
}

// ── Component ──

export function ArtifactRenderer({ source, title }: ArtifactRendererProps) {
  const isDark = useThemeStore((s) => s.isDark);
  const cssVariables = useThemeStore((s) => s.cssVariables);

  const iframeRef = useRef<HTMLIFrameElement>(null);
  const readyRef = useRef(false);
  const pendingRenderRef = useRef<string | null>(null);

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
      postToSandbox({ type: 'render', source: src, scope: {} });
    } else {
      pendingRenderRef.current = src;
    }
  }, [postToSandbox]);

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
            postToSandbox({ type: 'render', source: pendingRenderRef.current, scope: {} });
            pendingRenderRef.current = null;
          } else {
            postToSandbox({ type: 'render', source, scope: {} });
          }
          break;

        case 'rendered':
          setIsLoading(false);
          setError(null);
          if (typeof data.height === 'number' && data.height > 0) {
            setIframeHeight(data.height);
          }
          break;

        case 'error':
          setIsLoading(false);
          setError(new Error(
            `[${data.phase ?? 'unknown'}] ${data.message ?? 'Artifact render failed'}`,
          ));
          break;

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
    sendRender(source);
  }, [source, sendRender]);

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
