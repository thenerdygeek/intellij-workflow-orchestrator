import { useMemo } from 'react';
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
        srcDoc={srcdoc}
        sandbox="allow-scripts"
        title="Interactive HTML content"
        className="w-full border-0"
        style={{ height: `${height}px` }}
      />
    </RichBlock>
  );
}
