import { kotlinBridge, type ValidatedPath } from '../bridge/jcef-bridge';

/**
 * Strict regex for candidate file-path detection in rendered text.
 * - Multi-segment (at least one `/` or `\`)
 * - Ends with a known source extension
 * - Optional :line or :line:col suffix
 *
 * Keep the extension list in sync with the Kotlin PathLinkResolver spec.
 */
export const PATH_REGEX =
  /(?<![\w/\\:.])[\w.\-]+(?:[/\\][\w.\-]+)+\.(?:kt|kts|java|py|ts|tsx|js|jsx|mjs|cjs|md|yml|yaml|gradle|xml|json|sql|html|css|scss|properties|proto)(?::\d{1,7}(?::\d{1,7})?)?(?![\w])/g;

const RPC_TIMEOUT_MS = 2000;

export async function scanAndLinkify(root: HTMLElement): Promise<void> {
  const candidates = collectCandidates(root);
  if (candidates.length === 0) return;

  const uniq = Array.from(new Set(candidates));
  const validated = await withTimeout(kotlinBridge.validatePaths(uniq), RPC_TIMEOUT_MS);
  if (validated.length === 0) return;

  const byInput = new Map<string, ValidatedPath>();
  for (const v of validated) byInput.set(v.input, v);

  wrapTextNodes(root, byInput);
}

function collectCandidates(root: HTMLElement): string[] {
  const out: string[] = [];
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode(node) {
      const parent = node.parentElement;
      if (!parent) return NodeFilter.FILTER_REJECT;
      if (parent.classList.contains('file-link')) return NodeFilter.FILTER_REJECT;
      if (parent.closest('a, .file-link')) return NodeFilter.FILTER_REJECT;
      return NodeFilter.FILTER_ACCEPT;
    },
  });
  let node: Node | null;
  while ((node = walker.nextNode())) {
    const text = node.nodeValue ?? '';
    const matches = text.match(PATH_REGEX);
    if (matches) out.push(...matches);
  }
  return out;
}

function wrapTextNodes(root: HTMLElement, byInput: Map<string, ValidatedPath>): void {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode(node) {
      const parent = node.parentElement;
      if (!parent) return NodeFilter.FILTER_REJECT;
      if (parent.closest('a, .file-link')) return NodeFilter.FILTER_REJECT;
      return NodeFilter.FILTER_ACCEPT;
    },
  });
  const textNodes: Text[] = [];
  let node: Node | null;
  while ((node = walker.nextNode())) textNodes.push(node as Text);

  for (const tn of textNodes) {
    const text = tn.nodeValue ?? '';
    PATH_REGEX.lastIndex = 0;
    let match: RegExpExecArray | null;
    const replacements: { start: number; end: number; validated: ValidatedPath }[] = [];
    while ((match = PATH_REGEX.exec(text)) !== null) {
      const v = byInput.get(match[0]);
      if (v) replacements.push({ start: match.index, end: match.index + match[0].length, validated: v });
    }
    if (replacements.length === 0) continue;

    const frag = document.createDocumentFragment();
    let cursor = 0;
    for (const r of replacements) {
      if (r.start > cursor) frag.appendChild(document.createTextNode(text.slice(cursor, r.start)));
      const span = document.createElement('span');
      span.className = 'file-link';
      span.dataset.canonical = r.validated.canonicalPath;
      span.dataset.line = String(r.validated.line);
      span.dataset.column = String(r.validated.column);
      span.title = `${r.validated.canonicalPath}${r.validated.line > 0 ? ':' + (r.validated.line + 1) : ''}`;
      span.textContent = text.slice(r.start, r.end);
      frag.appendChild(span);
      cursor = r.end;
    }
    if (cursor < text.length) frag.appendChild(document.createTextNode(text.slice(cursor)));
    tn.parentNode?.replaceChild(frag, tn);
  }
}

function withTimeout<T>(p: Promise<T>, ms: number): Promise<T> {
  return new Promise((resolve) => {
    let done = false;
    const timer = setTimeout(() => {
      if (done) return;
      done = true;
      resolve([] as unknown as T);
    }, ms);
    p.then((v) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      resolve(v);
    }).catch(() => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      resolve([] as unknown as T);
    });
  });
}
