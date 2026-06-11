import { createHighlighterCore, type HighlighterCore } from 'shiki/core';
import { createOnigurumaEngine } from 'shiki/engine/oniguruma';

/**
 * Shipped languages — explicit import list for Shiki tree-shaking.
 *
 * Shiki's default `import { createHighlighter } from 'shiki'` side-effect-imports the entire
 * 347-language registry (~10 MB uncompressed in agent.jar). Switching to `shiki/core` plus
 * explicit dynamic imports lets Vite tree-shake the rest.
 *
 * Code-splitting: this module is only reached via a dynamic `import()` at the call sites
 * (e.g. `CodeBlock.tsx`), so Vite/Rollup emits shiki and its language grammars as separate
 * async chunks, keeping the heavy payloads off the startup-critical path. Do NOT add a
 * `manualChunks` rule for 'shiki' in vite.config.ts — that would collapse these lazy chunks
 * back into a single chunk and force it into the main entry's modulepreload list.
 *
 * Selection criteria: languages users actually paste into the agent chat or that the agent
 * itself emits in tool output. Anything outside this set renders as plain unhighlighted text.
 *
 * To add a language: append the import to `langs` below, add the Shiki ID to `SHIPPED_LANGUAGES`,
 * and rerun `npm run build` in this directory. To remove one, do the inverse.
 *
 * Note: PlantUML (`.puml`) is intentionally absent — Shiki ships no PlantUML grammar.
 */
export const SHIPPED_LANGUAGES = [
  // Originally pre-loaded
  'kotlin', 'java', 'python', 'typescript', 'javascript',
  'json', 'yaml', 'xml', 'sql', 'bash',
  'html', 'css', 'go', 'rust', 'markdown',
  // Docs / config / env
  'asciidoc', 'properties', 'dotenv',
  // Spring Boot / build
  'groovy', 'toml', 'ini',
  // DevOps / infra
  'docker', 'hcl', 'nginx',
  // SDK / native / .NET / iOS
  'csharp', 'swift', 'c', 'cpp',
  // Test automation
  'gherkin', 'ruby',
  // Misc tool output
  'shellscript', 'diff', 'regex',
] as const;

export const DARK_THEME = 'vitesse-dark';
export const LIGHT_THEME = 'vitesse-light';

let highlighterPromise: Promise<HighlighterCore> | null = null;

export function getSharedHighlighter(): Promise<HighlighterCore> {
  if (!highlighterPromise) {
    highlighterPromise = createHighlighterCore({
      themes: [
        import('@shikijs/themes/vitesse-dark'),
        import('@shikijs/themes/vitesse-light'),
      ],
      langs: [
        import('@shikijs/langs/kotlin'),
        import('@shikijs/langs/java'),
        import('@shikijs/langs/python'),
        import('@shikijs/langs/typescript'),
        import('@shikijs/langs/javascript'),
        import('@shikijs/langs/json'),
        import('@shikijs/langs/yaml'),
        import('@shikijs/langs/xml'),
        import('@shikijs/langs/sql'),
        import('@shikijs/langs/bash'),
        import('@shikijs/langs/html'),
        import('@shikijs/langs/css'),
        import('@shikijs/langs/go'),
        import('@shikijs/langs/rust'),
        import('@shikijs/langs/markdown'),
        import('@shikijs/langs/asciidoc'),
        import('@shikijs/langs/properties'),
        import('@shikijs/langs/dotenv'),
        import('@shikijs/langs/groovy'),
        import('@shikijs/langs/toml'),
        import('@shikijs/langs/ini'),
        import('@shikijs/langs/docker'),
        import('@shikijs/langs/hcl'),
        import('@shikijs/langs/nginx'),
        import('@shikijs/langs/csharp'),
        import('@shikijs/langs/swift'),
        import('@shikijs/langs/c'),
        import('@shikijs/langs/cpp'),
        import('@shikijs/langs/gherkin'),
        import('@shikijs/langs/ruby'),
        import('@shikijs/langs/shellscript'),
        import('@shikijs/langs/diff'),
        import('@shikijs/langs/regex'),
      ],
      engine: createOnigurumaEngine(import('shiki/wasm')),
    });
  }
  return highlighterPromise;
}

export function isShippedLanguage(lang: string): boolean {
  return (SHIPPED_LANGUAGES as readonly string[]).includes(lang);
}
