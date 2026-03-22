import { useState, useEffect } from 'react';
import { createHighlighter } from 'shiki';
import { useTheme } from './useTheme';

const PRE_LOADED_LANGUAGES = [
  'kotlin', 'java', 'python', 'typescript', 'javascript',
  'json', 'yaml', 'xml', 'sql', 'bash',
  'html', 'css', 'go', 'rust', 'markdown',
] as const;

const DARK_THEME = 'vitesse-dark';
const LIGHT_THEME = 'vitesse-light';

/* eslint-disable @typescript-eslint/no-explicit-any */
let highlighterPromise: Promise<any> | null = null;
let highlighterInstance: any = null;

function getHighlighter(): Promise<any> {
  if (!highlighterPromise) {
    highlighterPromise = createHighlighter({
      themes: [DARK_THEME, LIGHT_THEME],
      langs: [...PRE_LOADED_LANGUAGES],
    }).then((h: any) => {
      highlighterInstance = h;
      return h;
    });
  }
  return highlighterPromise;
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

export async function highlight(
  code: string,
  language: string,
  isDark: boolean,
): Promise<string> {
  try {
    const highlighter = await getHighlighter();
    const theme = isDark ? DARK_THEME : LIGHT_THEME;
    const loadedLangs: string[] = highlighter.getLoadedLanguages();

    if (language && !loadedLangs.includes(language)) {
      try {
        await highlighter.loadLanguage(language as any);
      } catch {
        // Language not available — fall back to plain text
        return `<pre style="background:transparent;margin:0;padding:0;"><code>${escapeHtml(code)}</code></pre>`;
      }
    }

    const lang = language && highlighter.getLoadedLanguages().includes(language)
      ? language
      : 'text';

    return highlighter.codeToHtml(code, { lang, theme });
  } catch {
    return `<pre style="background:transparent;margin:0;padding:0;"><code>${escapeHtml(code)}</code></pre>`;
  }
}
/* eslint-enable @typescript-eslint/no-explicit-any */

export function useShiki(code: string, language: string) {
  const { isDark } = useTheme();
  const [html, setHtml] = useState<string>('');
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setIsLoading(true);

    highlight(code, language, isDark).then((result) => {
      if (!cancelled) {
        setHtml(result);
        setIsLoading(false);
      }
    });

    return () => {
      cancelled = true;
    };
  }, [code, language, isDark]);

  return { html, isLoading, highlighterInstance };
}
