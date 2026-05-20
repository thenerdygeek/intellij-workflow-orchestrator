/**
 * LRU cache for Shiki's `codeToHtml` output, keyed on (hashedCode, language,
 * isDark). Multi-hour sessions repeatedly highlight the same blocks — e.g.,
 * during theme toggles, scrollback re-renders, or session resume — and each
 * call is an O(code-length) tokenizer pass. Caching the HTML output collapses
 * the cost to a Map lookup.
 *
 * Cache key is `(language | isDark | code.length | fnv1a(code))`. Collisions
 * are theoretically possible — two different code strings with the same length,
 * language, theme AND 32-bit FNV-1a hash would serve the wrong highlighted
 * HTML. The probability is vanishingly low and the failure mode is cosmetic
 * (wrong colors), not a crash. Acceptable tradeoff for a 32-bit hash key.
 *
 * Created as part of Task 2.4 of the 2026-05-20 frontend perf audit.
 */
export class ShikiLruCache {
  private readonly max: number;
  // Insertion order in the Map IS the LRU order (most recently inserted /
  // re-inserted is last). On `get` of a hit we delete-and-reinsert to bump
  // recency. The Map's iteration-order guarantee is part of the ECMAScript
  // spec, so this works in every browser/Node we target.
  private readonly map = new Map<string, string>();

  constructor(max = 256) {
    this.max = max;
  }

  private key(code: string, language: string, isDark: boolean): string {
    // 32-bit FNV-1a — fast, low-collision for typical code-block inputs.
    // See class KDoc for the honest collision-tradeoff discussion.
    let h = 0x811c9dc5;
    for (let i = 0; i < code.length; i++) {
      h ^= code.charCodeAt(i);
      h = Math.imul(h, 0x01000193);
    }
    return `${language}|${isDark ? 1 : 0}|${code.length}|${(h >>> 0).toString(16)}`;
  }

  get(code: string, language: string, isDark: boolean): string | undefined {
    const k = this.key(code, language, isDark);
    const v = this.map.get(k);
    if (v !== undefined) {
      // LRU: re-insert to move to most-recent end of the iteration order.
      this.map.delete(k);
      this.map.set(k, v);
    }
    return v;
  }

  set(code: string, language: string, isDark: boolean, html: string): void {
    const k = this.key(code, language, isDark);
    if (this.map.has(k)) this.map.delete(k);
    this.map.set(k, html);
    while (this.map.size > this.max) {
      const oldest = this.map.keys().next().value;
      if (oldest === undefined) break;
      this.map.delete(oldest);
    }
  }
}

// Module-singleton — one cache shared by all `useShiki` instances.
export const shikiCache = new ShikiLruCache(256);
