import { useState, useEffect } from 'react';
import { getSharedHighlighter, isShippedLanguage, DARK_THEME, LIGHT_THEME } from '@/lib/shiki';
import { shikiCache } from '@/lib/shiki-cache';
import { useTheme } from './useTheme';

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

function plainTextFallback(code: string): string {
  return `<pre style="background:transparent;margin:0;padding:0;"><code>${escapeHtml(code)}</code></pre>`;
}

/**
 * Highlight `code` for `language`. If `language` is not in the shipped set
 * (see `SHIPPED_LANGUAGES` in `lib/shiki.ts`), falls back to plain-text rendering —
 * we no longer attempt runtime `loadLanguage` because the bundle no longer carries
 * the 314 stripped grammars.
 *
 * The shared `shikiCache` (LRU, 256 entries, keyed on hashed code + language +
 * theme) collapses repeated highlight calls — common during theme toggles,
 * scrollback re-renders, and session resume — to a Map lookup. Cache misses
 * fall through to the real shiki pipeline and store the result.
 */
export async function highlight(
  code: string,
  language: string,
  isDark: boolean,
): Promise<string> {
  try {
    if (!language || !isShippedLanguage(language)) {
      return plainTextFallback(code);
    }
    const cached = shikiCache.get(code, language, isDark);
    if (cached !== undefined) return cached;
    const highlighter = await getSharedHighlighter();
    const theme = isDark ? DARK_THEME : LIGHT_THEME;
    const html = highlighter.codeToHtml(code, { lang: language, theme });
    shikiCache.set(code, language, isDark, html);
    return html;
  } catch {
    return plainTextFallback(code);
  }
}

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

  return { html, isLoading };
}
