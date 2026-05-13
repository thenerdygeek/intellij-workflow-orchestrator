import { describe, it, expect, vi, beforeEach } from 'vitest';
import { scanAndSymbolLinkify } from '../util/symbol-link-scanner';
import { kotlinBridge } from '../bridge/jcef-bridge';

describe('scanAndSymbolLinkify', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('patches data-canonical and data-line onto a valid symbol anchor', async () => {
    vi.spyOn(kotlinBridge, 'resolveSymbols').mockResolvedValue([
      {
        input: 'symbol:com.example.Foo',
        canonicalPath: '/project/src/Foo.kt',
        line: 9,
        column: 0,
      },
    ]);

    const root = document.createElement('div');
    root.innerHTML = '<a href="symbol:com.example.Foo">Foo</a>';
    await scanAndSymbolLinkify(root);

    const anchor = root.querySelector('a') as HTMLAnchorElement;
    expect(anchor.getAttribute('href')).toBe('symbol:com.example.Foo');
    expect(anchor.dataset.canonical).toBe('/project/src/Foo.kt');
    expect(anchor.dataset.line).toBe('9');
    expect(anchor.title).toBe('/project/src/Foo.kt:10');
  });

  it('strips href from an unresolved symbol anchor', async () => {
    vi.spyOn(kotlinBridge, 'resolveSymbols').mockResolvedValue([]);

    const root = document.createElement('div');
    root.innerHTML = '<a href="symbol:com.example.Unknown">Unknown</a>';
    await scanAndSymbolLinkify(root);

    const anchor = root.querySelector('a') as HTMLAnchorElement;
    expect(anchor.getAttribute('href')).toBeNull();
    expect(anchor.textContent).toBe('Unknown');
  });

  it('does nothing when no symbol anchors present', async () => {
    const spy = vi.spyOn(kotlinBridge, 'resolveSymbols');

    const root = document.createElement('div');
    root.innerHTML = '<p>plain text</p>';
    await scanAndSymbolLinkify(root);

    expect(spy).not.toHaveBeenCalled();
  });

  it('deduplicates hrefs sent to resolveSymbols', async () => {
    const spy = vi.spyOn(kotlinBridge, 'resolveSymbols').mockResolvedValue([]);

    const root = document.createElement('div');
    root.innerHTML = `
      <a href="symbol:com.example.Foo">Foo</a>
      <a href="symbol:com.example.Foo">Foo again</a>
    `;
    await scanAndSymbolLinkify(root);

    expect(spy).toHaveBeenCalledWith(['symbol:com.example.Foo']);
  });

  it('ignores non-symbol anchors', async () => {
    const spy = vi.spyOn(kotlinBridge, 'resolveSymbols');

    const root = document.createElement('div');
    root.innerHTML = '<a href="src/Foo.kt:10">file link</a>';
    await scanAndSymbolLinkify(root);

    expect(spy).not.toHaveBeenCalled();
  });

  it('returns without patching when resolveSymbols times out (returns [])', async () => {
    vi.spyOn(kotlinBridge, 'resolveSymbols').mockResolvedValue([]);

    const root = document.createElement('div');
    root.innerHTML = '<a href="symbol:com.example.Foo">Foo</a>';
    await scanAndSymbolLinkify(root);

    const anchor = root.querySelector('a') as HTMLAnchorElement;
    expect(anchor.getAttribute('href')).toBeNull();
  });
});
