// Single-pass heuristic token highlighting for plain-text terminal output.
// Applied only when no ANSI escape codes are present — ANSI coloring takes precedence.

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

// Combined alternation: first matching branch wins at each position.
// Order matters — more specific patterns (BUILD FAILED) before generic ones (FAILED).
const TOKEN_RE =
  /(?<build_err>BUILD\s+(?:FAILED|FAILURE))|(?<build_ok>BUILD\s+SUCCESSFUL?)|(?<err>\b(?:ERROR|FATAL|SEVERE)\b)|(?<warn>\bWARN(?:ING)?\b)|(?<ok>\b(?:PASSED|SUCCESSFUL?)\b)|(?<info>\b(?:INFO|DEBUG|TRACE)\b)|(?<fail>\b(?:FAILED|FAILURE|SKIPPED)\b)|(?<path>[./\w-]+\.(?:kt|java|ts|tsx|js|jsx|py|go|rs|swift|cpp|c|h):\d+(?::\d+)?)|(?<url>https?:\/\/[^\s]+)/gi;

const COLOR: Record<string, string> = {
  build_err: 'var(--error)',
  build_ok:  'var(--success)',
  err:       'var(--error)',
  warn:      'var(--warn, #e6a100)',
  ok:        'var(--success)',
  info:      'var(--fg-muted)',
  fail:      'var(--error)',
  path:      'var(--link, #589df6)',
  url:       'var(--link, #589df6)',
};

export function highlightPlainText(text: string): string {
  const escaped = escapeHtml(text);
  return escaped.replace(TOKEN_RE, (match, ...args) => {
    const groups = args[args.length - 1] as Record<string, string | undefined>;
    const key = Object.keys(groups).find(k => groups[k] !== undefined);
    if (!key) return match;
    const color = COLOR[key];
    return color ? `<span style="color:${color}">${match}</span>` : match;
  });
}
