import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { AttachmentManager, type AttachmentManagerSettings } from '@/components/input/AttachmentManager';

/**
 * Phase 5 followup F-P5-1 (raised in Phase 5 review, landed in Phase 6) —
 * lock in `AttachmentManager` validation, dedup, ObjectURL lifecycle, and
 * upload-pre-flight contracts so a future refactor can't silently regress.
 *
 * Notes on the jsdom environment:
 * - `URL.createObjectURL` / `URL.revokeObjectURL` are mocked here (jsdom
 *   omits the impl when no Worker is around to handle it).
 * - `crypto.subtle.digest` lands in jsdom 22+ (we pin a stub here for safety
 *   so the test runs even on older envs).
 * - `fetch` is mocked per-test.
 */

const DEFAULT_SETTINGS: AttachmentManagerSettings = {
  maxBytes: 1024,
  mimeWhitelist: ['image/png', 'image/jpeg'],
  maxPerTurn: 2,
  enabled: true,
};

let createdUrls: string[] = [];
let revokedUrls: string[] = [];
let originalCreateObjectURL: typeof URL.createObjectURL;
let originalRevokeObjectURL: typeof URL.revokeObjectURL;

function mockObjectUrls() {
  originalCreateObjectURL = URL.createObjectURL;
  originalRevokeObjectURL = URL.revokeObjectURL;
  let counter = 0;
  URL.createObjectURL = vi.fn(() => {
    const u = `blob:test-${++counter}`;
    createdUrls.push(u);
    return u;
  }) as typeof URL.createObjectURL;
  URL.revokeObjectURL = vi.fn((u: string) => {
    revokedUrls.push(u);
  }) as typeof URL.revokeObjectURL;
}

function restoreObjectUrls() {
  URL.createObjectURL = originalCreateObjectURL;
  URL.revokeObjectURL = originalRevokeObjectURL;
}

function makeFile(name: string, type: string, bytes: number, content?: Uint8Array): File {
  const data = content ?? new Uint8Array(bytes);
  return new File([data], name, { type });
}

beforeEach(() => {
  createdUrls = [];
  revokedUrls = [];
  mockObjectUrls();
  // Make crypto.subtle.digest stable in jsdom for the dedup-by-content test.
  if (!globalThis.crypto || !globalThis.crypto.subtle) {
    Object.defineProperty(globalThis, 'crypto', {
      value: {
        subtle: {
          digest: async (_alg: string, data: BufferSource) => {
            // Toy hash: sum bytes mod 256 padded to 32 bytes — collisions don't
            // matter for these tests because every test uses unique fixed bytes.
            const bytes = data instanceof ArrayBuffer
              ? new Uint8Array(data)
              : new Uint8Array((data as ArrayBufferView).buffer);
            const out = new Uint8Array(32);
            let acc = 0;
            for (const b of bytes) acc = (acc + b) & 0xff;
            out[0] = acc;
            for (let i = 1; i < 32; i++) out[i] = (acc + i) & 0xff;
            return out.buffer;
          },
        },
      },
      configurable: true,
    });
  }
});

afterEach(() => {
  restoreObjectUrls();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('AttachmentManager — validation', () => {
  it('rejects when image input is disabled', async () => {
    const toast = vi.fn();
    const onChange = vi.fn();
    const mgr = new AttachmentManager(
      { ...DEFAULT_SETTINGS, enabled: false },
      onChange,
      toast,
    );
    const result = await mgr.attachFile(makeFile('a.png', 'image/png', 100));
    expect(result).toBeNull();
    expect(toast).toHaveBeenCalledWith(expect.stringMatching(/disabled/i), 'warning');
    expect(onChange).not.toHaveBeenCalled();
    expect(mgr.list()).toHaveLength(0);
  });

  it('rejects oversize files', async () => {
    const toast = vi.fn();
    const mgr = new AttachmentManager(DEFAULT_SETTINGS, vi.fn(), toast);
    const result = await mgr.attachFile(makeFile('big.png', 'image/png', 2048));
    expect(result).toBeNull();
    expect(toast).toHaveBeenCalledWith(expect.stringMatching(/too large/i), 'warning');
    expect(mgr.list()).toHaveLength(0);
  });

  it('rejects MIME types not in the whitelist', async () => {
    const toast = vi.fn();
    const mgr = new AttachmentManager(DEFAULT_SETTINGS, vi.fn(), toast);
    const result = await mgr.attachFile(makeFile('x.gif', 'image/gif', 100));
    expect(result).toBeNull();
    expect(toast).toHaveBeenCalledWith(expect.stringMatching(/not in the allowed list/i), 'warning');
    expect(mgr.list()).toHaveLength(0);
  });

  it('rejects when at the per-turn cap', async () => {
    const toast = vi.fn();
    const mgr = new AttachmentManager({ ...DEFAULT_SETTINGS, maxPerTurn: 1 }, vi.fn(), toast);
    const ok = await mgr.attachFile(makeFile('a.png', 'image/png', 100, new Uint8Array([1, 2, 3])));
    expect(ok).not.toBeNull();
    const blocked = await mgr.attachFile(makeFile('b.png', 'image/png', 100, new Uint8Array([9, 8, 7])));
    expect(blocked).toBeNull();
    expect(toast).toHaveBeenCalledWith(expect.stringMatching(/at most/i), 'warning');
    expect(mgr.list()).toHaveLength(1);
  });

  it('accepts a valid file and computes sha256 + ObjectURL', async () => {
    const onChange = vi.fn();
    const mgr = new AttachmentManager(DEFAULT_SETTINGS, onChange, vi.fn());
    const result = await mgr.attachFile(makeFile('a.png', 'image/png', 100, new Uint8Array([1, 2, 3])));
    expect(result).not.toBeNull();
    expect(result!.sha256).toMatch(/^[0-9a-f]{64}$/);
    expect(result!.thumbnailUrl).toMatch(/^blob:/);
    expect(createdUrls).toHaveLength(1);
    expect(onChange).toHaveBeenCalledTimes(1);
  });

  it('multiple valid files within cap accumulate', async () => {
    const mgr = new AttachmentManager({ ...DEFAULT_SETTINGS, maxPerTurn: 2 }, vi.fn(), vi.fn());
    const a = await mgr.attachFile(makeFile('a.png', 'image/png', 100, new Uint8Array([1, 2, 3])));
    const b = await mgr.attachFile(makeFile('b.png', 'image/png', 100, new Uint8Array([4, 5, 6])));
    expect(a).not.toBeNull();
    expect(b).not.toBeNull();
    expect(mgr.list()).toHaveLength(2);
  });
});

describe('AttachmentManager — remove + clear', () => {
  it('remove revokes the ObjectURL for the matching attachment', async () => {
    const mgr = new AttachmentManager(DEFAULT_SETTINGS, vi.fn(), vi.fn());
    const att = await mgr.attachFile(makeFile('a.png', 'image/png', 100, new Uint8Array([1, 2, 3])));
    expect(att).not.toBeNull();
    const sha = att!.sha256;
    const url = att!.thumbnailUrl;
    mgr.remove(sha);
    expect(revokedUrls).toContain(url);
    expect(mgr.list()).toHaveLength(0);
  });

  it('remove on missing sha256 is a no-op', async () => {
    const mgr = new AttachmentManager(DEFAULT_SETTINGS, vi.fn(), vi.fn());
    mgr.remove('not-a-real-sha');
    expect(revokedUrls).toHaveLength(0);
    expect(mgr.list()).toHaveLength(0);
  });

  it('clear revokes all ObjectURLs and fires onChange', async () => {
    const onChange = vi.fn();
    const mgr = new AttachmentManager({ ...DEFAULT_SETTINGS, maxPerTurn: 2 }, onChange, vi.fn());
    await mgr.attachFile(makeFile('a.png', 'image/png', 100, new Uint8Array([1, 2, 3])));
    await mgr.attachFile(makeFile('b.png', 'image/png', 100, new Uint8Array([4, 5, 6])));
    onChange.mockClear();
    mgr.clear();
    expect(revokedUrls).toHaveLength(2);
    expect(mgr.list()).toHaveLength(0);
    expect(onChange).toHaveBeenCalledTimes(1);
  });
});

describe('AttachmentManager — uploadAll', () => {
  it('skips fetch when bridge reports the bytes already exist on disk', async () => {
    const mgr = new AttachmentManager(DEFAULT_SETTINGS, vi.fn(), vi.fn());
    const att = await mgr.attachFile(makeFile('a.png', 'image/png', 100, new Uint8Array([1, 2, 3])));
    expect(att).not.toBeNull();

    const fetchSpy = vi.fn();
    vi.stubGlobal('fetch', fetchSpy);
    Object.defineProperty(window, '_attachmentExists', {
      value: vi.fn().mockResolvedValue({ exists: true }),
      configurable: true,
      writable: true,
    });

    const result = await mgr.uploadAll();
    expect(result).toEqual([att!.sha256]);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('POSTs raw bytes with X-Image-Mime and X-Original-Filename headers when bridge says missing', async () => {
    const mgr = new AttachmentManager(DEFAULT_SETTINGS, vi.fn(), vi.fn());
    const att = await mgr.attachFile(makeFile('photo.png', 'image/png', 100, new Uint8Array([7, 8, 9])));
    expect(att).not.toBeNull();

    const fetchSpy = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({}),
    });
    vi.stubGlobal('fetch', fetchSpy);
    Object.defineProperty(window, '_attachmentExists', {
      value: vi.fn().mockResolvedValue({ exists: false }),
      configurable: true,
      writable: true,
    });

    const result = await mgr.uploadAll();
    expect(result).toEqual([att!.sha256]);
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const call = fetchSpy.mock.calls[0]!;
    expect(call[0]).toBe(`http://workflow-agent/upload/${att!.sha256}`);
    expect(call[1]?.method).toBe('POST');
    expect(call[1]?.headers).toEqual(expect.objectContaining({
      'X-Image-Mime': 'image/png',
      'X-Original-Filename': 'photo.png',
    }));
  });

  it('toasts when fetch returns non-ok status', async () => {
    const toast = vi.fn();
    const mgr = new AttachmentManager(DEFAULT_SETTINGS, vi.fn(), toast);
    const att = await mgr.attachFile(makeFile('x.png', 'image/png', 100, new Uint8Array([1, 2])));
    expect(att).not.toBeNull();

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      json: async () => ({}),
    }));
    Object.defineProperty(window, '_attachmentExists', {
      value: vi.fn().mockResolvedValue({ exists: false }),
      configurable: true,
      writable: true,
    });

    await mgr.uploadAll();
    expect(toast).toHaveBeenCalledWith(expect.stringMatching(/upload failed.*500/i), 'error');
  });

  it('toasts when fetch throws (e.g. network down)', async () => {
    const toast = vi.fn();
    const mgr = new AttachmentManager(DEFAULT_SETTINGS, vi.fn(), toast);
    const att = await mgr.attachFile(makeFile('x.png', 'image/png', 100, new Uint8Array([1, 2])));
    expect(att).not.toBeNull();

    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network down')));
    Object.defineProperty(window, '_attachmentExists', {
      value: vi.fn().mockResolvedValue({ exists: false }),
      configurable: true,
      writable: true,
    });

    await mgr.uploadAll();
    expect(toast).toHaveBeenCalledWith(expect.stringMatching(/upload threw.*network down/i), 'error');
  });
});

describe('AttachmentManager — within-turn dedup', () => {
  it('a re-attached identical file just returns the existing chip', async () => {
    const toast = vi.fn();
    const mgr = new AttachmentManager(DEFAULT_SETTINGS, vi.fn(), toast);
    const bytes = new Uint8Array([1, 2, 3]);
    const a = await mgr.attachFile(makeFile('a.png', 'image/png', 100, bytes));
    const b = await mgr.attachFile(makeFile('a-copy.png', 'image/png', 100, bytes));
    expect(a).not.toBeNull();
    expect(b).not.toBeNull();
    expect(a!.sha256).toBe(b!.sha256);
    expect(mgr.list()).toHaveLength(1); // not 2
    expect(toast).toHaveBeenCalledWith(expect.stringMatching(/already attached/i), 'info');
  });
});

describe('AttachmentManager — settings hot-update', () => {
  it('updateSettings reflects new validation rules immediately', async () => {
    const toast = vi.fn();
    const mgr = new AttachmentManager(DEFAULT_SETTINGS, vi.fn(), toast);
    // Initially enabled — file would be accepted; flip to disabled and try.
    mgr.updateSettings({ ...DEFAULT_SETTINGS, enabled: false });
    const result = await mgr.attachFile(makeFile('a.png', 'image/png', 100, new Uint8Array([1])));
    expect(result).toBeNull();
    expect(toast).toHaveBeenCalledWith(expect.stringMatching(/disabled/i), 'warning');
  });
});
