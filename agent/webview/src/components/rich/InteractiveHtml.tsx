import { useEffect, useMemo, useRef, useId } from 'react';
import { RichBlock } from './RichBlock';
import { useThemeStore } from '@/stores/themeStore';

// ── Props ──

interface InteractiveHtmlProps {
  htmlContent: string;
  height?: number;
}

export function InteractiveHtml({ htmlContent, height = 400 }: InteractiveHtmlProps) {
  const cssVariables = useThemeStore((s) => s.cssVariables);
  const isDark = useThemeStore((s) => s.isDark);
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const vizId = useId();

  // Listen for postMessage events from the iframe and forward to Kotlin
  useEffect(() => {
    const handler = (e: MessageEvent) => {
      // Only accept messages from our iframe
      if (!iframeRef.current || e.source !== iframeRef.current.contentWindow) return;

      // Validate message shape — must have a string `type` field
      const data = e.data;
      if (
        data == null ||
        typeof data !== 'object' ||
        typeof data.type !== 'string' ||
        data.type.length === 0 ||
        data.type.length >= 100
      ) {
        return;
      }

      // Forward to Kotlin bridge
      window._interactiveHtmlMessage?.(JSON.stringify(data));
    };

    window.addEventListener('message', handler);
    return () => window.removeEventListener('message', handler);
  }, []);

  // Build srcdoc with injected theme CSS variables
  const srcdoc = useMemo(() => {
    const cssVarEntries = Object.entries(cssVariables)
      .map(([key, value]) => `--${key}: ${value};`)
      .join('\n      ');

    return `<!DOCTYPE html>
<html data-theme="${isDark ? 'dark' : 'light'}">
<head>
  <meta charset="utf-8">
  <style>
    :root {
      ${cssVarEntries}
      color-scheme: ${isDark ? 'dark' : 'light'};
    }
    body {
      margin: 0;
      padding: 12px;
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
      font-size: 13px;
      color: var(--fg, ${isDark ? '#d4d4d4' : '#333'});
      background: var(--bg, ${isDark ? '#1e1e1e' : '#fff'});
    }
  </style>
</head>
<body>
${htmlContent}
</body>
</html>`;
  }, [htmlContent, cssVariables, isDark]);

  return (
    <RichBlock type="interactiveHtml" source={htmlContent}>
      <iframe
        ref={iframeRef}
        data-viz-id={vizId}
        srcDoc={srcdoc}
        sandbox="allow-scripts"
        title="Interactive HTML content"
        className="w-full border-0"
        style={{ height: `${height}px` }}
      />
    </RichBlock>
  );
}

// ── Global API for Kotlin to send data into iframes ──

(window as any).sendToInteractiveHtml = (id: string, json: string) => {
  const iframe = document.querySelector(`iframe[data-viz-id="${id}"]`) as HTMLIFrameElement | null;
  if (iframe?.contentWindow) {
    try {
      iframe.contentWindow.postMessage(JSON.parse(json), '*');
    } catch { /* ignore parse errors */ }
  }
};
