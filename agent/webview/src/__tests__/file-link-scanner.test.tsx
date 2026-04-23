import { describe, it, expect, vi, beforeEach } from 'vitest';
import { scanAndLinkify, PATH_REGEX } from '../util/file-link-scanner';
import { kotlinBridge } from '../bridge/jcef-bridge';

describe('PATH_REGEX', () => {
  beforeEach(() => { PATH_REGEX.lastIndex = 0; });

  it('matches slashed path with known extension', () => {
    expect('see agent/src/main/kotlin/AgentLoop.kt'.match(PATH_REGEX))
      .toEqual(['agent/src/main/kotlin/AgentLoop.kt']);
  });

  it('matches with line suffix', () => {
    expect('at src/foo.kt:42 today'.match(PATH_REGEX)).toEqual(['src/foo.kt:42']);
  });

  it('matches with line and column', () => {
    expect('src/foo.kt:42:10 broke'.match(PATH_REGEX)).toEqual(['src/foo.kt:42:10']);
  });

  it('does not match Java FQN', () => {
    expect('com.foo.Bar'.match(PATH_REGEX)).toBeNull();
  });

  it('does not match bare URL', () => {
    expect('https://example.com/p/foo.kt'.match(PATH_REGEX)).toBeNull();
  });

  it('does not match single-segment file', () => {
    expect('just foo.kt alone'.match(PATH_REGEX)).toBeNull();
  });

  it('rejects unknown extension', () => {
    expect('src/foo.xyz'.match(PATH_REGEX)).toBeNull();
  });
});

describe('scanAndLinkify', () => {
  beforeEach(() => {
    vi.spyOn(kotlinBridge, 'validatePaths').mockResolvedValue([
      { input: 'src/foo.kt:42', canonicalPath: '/canonical/src/foo.kt', line: 41, column: 0 },
    ]);
  });

  it('wraps validated matches and leaves non-validated as text', async () => {
    const root = document.createElement('div');
    root.textContent = 'edit src/foo.kt:42 and also src/bar.kt';
    await scanAndLinkify(root);

    const links = root.querySelectorAll('.file-link');
    expect(links.length).toBe(1);
    expect((links[0] as HTMLElement).dataset.canonical).toBe('/canonical/src/foo.kt');
    expect((links[0] as HTMLElement).dataset.line).toBe('41');
    expect(root.textContent).toContain('src/bar.kt');
  });

  it('does nothing when no candidates', async () => {
    const root = document.createElement('div');
    root.textContent = 'just prose with no paths';
    await scanAndLinkify(root);
    expect(root.querySelectorAll('.file-link').length).toBe(0);
  });

  it('dedupes candidates before RPC', async () => {
    const spy = vi.spyOn(kotlinBridge, 'validatePaths').mockResolvedValue([]);
    const root = document.createElement('div');
    root.textContent = 'src/foo.kt:42 then src/foo.kt:42 again';
    await scanAndLinkify(root);
    const arg = spy.mock.calls[0]?.[0];
    expect(arg).toEqual(['src/foo.kt:42']);
  });
});
