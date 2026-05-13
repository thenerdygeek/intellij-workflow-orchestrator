import { kotlinBridge, type ValidatedPath } from '../bridge/jcef-bridge';

export async function scanAndSymbolLinkify(root: HTMLElement): Promise<void> {
  const anchors = Array.from(
    root.querySelectorAll<HTMLAnchorElement>('a[href^="symbol:"]'),
  );
  if (anchors.length === 0) return;

  const uniqHrefs = Array.from(new Set(anchors.map((a) => a.getAttribute('href')!)));
  const resolved = await kotlinBridge.resolveSymbols(uniqHrefs);

  const byHref = new Map<string, ValidatedPath>(resolved.map((v) => [v.input, v]));

  for (const a of anchors) {
    const href = a.getAttribute('href')!;
    const v = byHref.get(href);
    if (v) {
      a.dataset.canonical = v.canonicalPath;
      a.dataset.line = String(v.line);
      a.dataset.column = String(v.column);
      a.title = `${v.canonicalPath}:${v.line + 1}`;
    } else {
      a.removeAttribute('href');
    }
  }
}
