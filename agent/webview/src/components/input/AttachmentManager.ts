/**
 * Phase 5 — JS-side image-attachment state.
 *
 * Validates files client-side before any bridge round-trip (size + MIME +
 * per-turn cap), computes sha256 via SubtleCrypto, and uploads through the
 * `http://workflow-agent/upload/<sha256>` HTTP-style endpoint served by
 * Kotlin's `AttachmentUploadHandler`.
 *
 * Bridge IPC stays text-only — we only send the metadata hash through
 * `JBCefJSQuery`; multi-MB binary bytes go over the resource handler.
 *
 * Per-attachment validation outcomes carry a stable `errorCode` that mirrors
 * Kotlin's `AttachmentUploadHandler.ValidationResult` codes; the UI surfaces
 * a user-facing message via the supplied `toast` hook.
 */

export interface PendingAttachment {
  /** Hex-encoded sha256 of the bytes. */
  sha256: string;
  mime: string;
  size: number;
  originalFilename: string;
  /** Bytes held in memory until Send (or Remove). */
  bytes: Uint8Array;
  /** ObjectURL for chip preview — must be revoked on remove(). */
  thumbnailUrl: string;
  /** "image" routes to vision; "file" is read on demand by the agent. */
  kind: 'image' | 'file';
  /** Absolute session path; present only for kind === 'file'. */
  path?: string;
}

export interface AttachmentManagerSettings {
  maxBytes: number;
  mimeWhitelist: string[];
  maxPerTurn: number;
  enabled: boolean;
}

export type ToastFn = (message: string, type?: 'info' | 'warning' | 'error') => void;

/**
 * Confirmation prompt for oversize images before lossy re-encode.
 * Returns true to proceed with compression, false to cancel the entire attach.
 *
 * Falls back to `window.confirm` when not provided so the manager remains
 * usable in test/harness environments. Production wires a styled modal.
 */
export type ConfirmFn = (
  originalKB: number,
  capKB: number,
  filename: string,
) => Promise<boolean>;

declare global {
  interface Window {
    _attachmentExists?: (sha256: string) => Promise<{ exists: boolean }>;
  }
}

export class AttachmentManager {
  private attachments: PendingAttachment[] = [];
  private readonly toast: ToastFn;
  private readonly onChange: () => void;
  private settings: AttachmentManagerSettings;
  private readonly confirmCompress: ConfirmFn;

  constructor(
    settings: AttachmentManagerSettings,
    onChange: () => void,
    toast: ToastFn,
    confirmCompress?: ConfirmFn,
  ) {
    this.settings = settings;
    this.onChange = onChange;
    this.toast = toast;
    this.confirmCompress = confirmCompress ?? defaultConfirm;
  }

  /** Hot-update settings (e.g. after the Settings dialog applies). */
  updateSettings(next: AttachmentManagerSettings): void {
    this.settings = next;
  }

  /**
   * Validates a file and adds it to the pending list. Returns the new
   * attachment ref on success, or null when rejected (toast already fired).
   */
  async attachFile(file: File): Promise<PendingAttachment | null> {
    console.log('[multimodal:attach] AttachmentManager.attachFile: entry', { name: file.name, type: file.type, size: file.size }, 'currentSettings=', this.settings, 'pendingCount=', this.attachments.length);
    if (!this.settings.enabled) {
      console.warn('[multimodal:attach] AttachmentManager.attachFile: REJECTED — image input disabled in settings');
      this.toast('Image input is disabled in settings.', 'warning');
      return null;
    }
    if (this.attachments.length >= this.settings.maxPerTurn) {
      console.warn('[multimodal:attach] AttachmentManager.attachFile: REJECTED — per-turn cap reached', this.attachments.length, '>=', this.settings.maxPerTurn);
      this.toast(`At most ${this.settings.maxPerTurn} image(s) per turn.`, 'warning');
      return null;
    }
    if (!this.settings.mimeWhitelist.includes(file.type)) {
      console.warn('[multimodal:attach] AttachmentManager.attachFile: REJECTED — MIME', file.type, 'not in whitelist', this.settings.mimeWhitelist);
      this.toast(`Image type "${file.type || 'unknown'}" is not in the allowed list.`, 'warning');
      return null;
    }

    // Oversize → ask the user before lossy re-encode. Cancel = abort the whole
    // attach (no upload, no chip). Confirm = compress to JPEG-quality-0.85
    // (with progressive resize) until under cap, then proceed with the result.
    let workingFile: File = file;
    let workingMime: string = file.type;
    if (file.size > this.settings.maxBytes) {
      const originalKB = Math.round(file.size / 1024);
      const capKB = Math.round(this.settings.maxBytes / 1024);
      console.log('[multimodal:attach] AttachmentManager.attachFile: oversize — prompting compress', { originalKB, capKB });
      const proceed = await this.confirmCompress(originalKB, capKB, file.name);
      console.log('[multimodal:attach] AttachmentManager.attachFile: compress prompt resolved with proceed=', proceed);
      if (!proceed) {
        return null;
      }
      try {
        const compressed = await compressToJpegUnderCap(file, this.settings.maxBytes);
        if (compressed.size > this.settings.maxBytes) {
          this.toast(
            `Could not compress "${file.name}" under ${capKB} KB cap (best effort: ${Math.round(compressed.size / 1024)} KB).`,
            'error',
          );
          return null;
        }
        workingFile = compressed;
        workingMime = 'image/jpeg';
        this.toast(
          `Compressed "${file.name}" from ${originalKB} KB to ${Math.round(compressed.size / 1024)} KB.`,
          'info',
        );
      } catch (e) {
        this.toast(`Could not compress image: ${(e as Error).message}`, 'error');
        return null;
      }
    }

    let bytes: Uint8Array;
    try {
      bytes = new Uint8Array(await workingFile.arrayBuffer());
      console.log('[multimodal:attach] AttachmentManager.attachFile: read', bytes.byteLength, 'bytes from file');
    } catch (e) {
      console.error('[multimodal:attach] AttachmentManager.attachFile: arrayBuffer threw', e);
      this.toast(`Could not read image bytes: ${(e as Error).message}`, 'error');
      return null;
    }

    let sha256: string;
    try {
      const subtleAvailable = !!((globalThis as any).crypto && (globalThis as any).crypto.subtle);
      sha256 = await AttachmentManager.sha256Hex(bytes);
      console.log('[multimodal:attach] AttachmentManager.attachFile: sha256=', sha256.slice(0, 12) + '… (path=', subtleAvailable ? 'subtle' : 'pure-js', ')');
    } catch (e) {
      console.error('[multimodal:attach] AttachmentManager.attachFile: sha256 threw — origin=', typeof window !== 'undefined' ? window.location.origin : 'n/a', 'isSecureContext=', typeof window !== 'undefined' ? (window as any).isSecureContext : 'n/a', 'err=', e);
      this.toast(`Could not hash image: ${(e as Error).message}`, 'error');
      return null;
    }

    // Within-attachments dedup: if the user pastes the same bytes twice in
    // one turn, just bump the existing chip — don't show two identical chips.
    const existing = this.attachments.find(a => a.sha256 === sha256);
    if (existing) {
      console.log('[multimodal:attach] AttachmentManager.attachFile: deduped — sha256 already pending');
      this.toast('That image is already attached.', 'info');
      return existing;
    }

    const thumbnailUrl = URL.createObjectURL(new Blob([bytes], { type: workingMime }));
    const att: PendingAttachment = {
      sha256,
      mime: workingMime,
      size: bytes.byteLength,
      originalFilename: file.name,
      bytes,
      thumbnailUrl,
      kind: 'image',
    };
    this.attachments.push(att);
    this.onChange();
    console.log('[multimodal:attach] AttachmentManager.attachFile: SUCCESS — added chip, pendingCount=', this.attachments.length);
    return att;
  }

  /**
   * Adds a chip for a file already stored on the JVM side (picker/drop). No
   * byte handling — the bytes live on disk; this only records metadata so the
   * chip renders and the send payload can carry it. Deduped by sha256.
   */
  addExternalChip(meta: {
    sha256: string; mime: string; size: number; originalFilename: string;
    kind: 'image' | 'file'; path?: string;
  }): void {
    if (this.attachments.some(a => a.sha256 === meta.sha256)) return;
    this.attachments.push({
      sha256: meta.sha256,
      mime: meta.mime,
      size: meta.size,
      originalFilename: meta.originalFilename,
      bytes: new Uint8Array(0),
      thumbnailUrl: meta.kind === 'image' ? `http://workflow-agent/attachments/${meta.sha256}` : '',
      kind: meta.kind,
      path: meta.path,
    });
    this.onChange();
  }

  /** Remove an attachment from the pending list and revoke its preview URL. */
  remove(sha256: string): void {
    const idx = this.attachments.findIndex(a => a.sha256 === sha256);
    if (idx >= 0) {
      URL.revokeObjectURL(this.attachments[idx]!.thumbnailUrl);
      this.attachments.splice(idx, 1);
      this.onChange();
    }
  }

  /** Returns a defensive copy — callers should not mutate the result. */
  list(): PendingAttachment[] {
    return [...this.attachments];
  }

  /**
   * Empties the pending list and revokes all preview URLs. Called on Send
   * (after a successful uploadAll) and on the chat reset path.
   */
  clear(): void {
    for (const a of this.attachments) URL.revokeObjectURL(a.thumbnailUrl);
    this.attachments = [];
    this.onChange();
  }

  /**
   * Uploads any not-yet-stored bytes via the `workflow-agent/upload/<sha256>`
   * endpoint. Returns the list of sha256s in the same order as `list()`.
   *
   * Pre-flight: asks Kotlin via `_attachmentExists` whether the bytes already
   * live in the active session's `attachments/` dir (within-session dedup).
   * If yes, skips the multi-MB upload entirely.
   */
  async uploadAll(): Promise<string[]> {
    const results: string[] = [];
    for (const att of this.attachments) {
      const existsBridge = window._attachmentExists;
      let needUpload = true;
      if (existsBridge) {
        try {
          const r = await existsBridge(att.sha256);
          if (r && r.exists) needUpload = false;
        } catch {
          // Bridge unavailable — fall back to upload.
        }
      }
      if (needUpload) {
        try {
          const resp = await fetch(`http://workflow-agent/upload/${att.sha256}`, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/octet-stream',
              'X-Image-Mime': att.mime,
              'X-Original-Filename': att.originalFilename,
            },
            body: att.bytes,
          });
          if (!resp.ok) {
            this.toast(`Image upload failed: HTTP ${resp.status}`, 'error');
            // Continue collecting the others; caller decides what to do with a
            // partial result.
          } else {
            const body = await resp.json().catch(() => ({}));
            if (body && body.error) {
              this.toast(`Image upload rejected: ${body.error}`, 'error');
            }
          }
        } catch (e) {
          this.toast(`Image upload threw: ${(e as Error).message}`, 'error');
        }
      }
      results.push(att.sha256);
    }
    return results;
  }

  /**
   * Hex-encoded sha256 of the bytes (lowercase).
   *
   * Why not crypto.subtle? The plugin's webview is served from
   * `http://workflow-agent`, which is NOT a "secure context" (only https,
   * localhost, and file: qualify). JCEF's Chromium therefore returns
   * `crypto.subtle === undefined` and SubtleCrypto is unusable. We fall
   * back to a pure-JS implementation so attach works on every platform.
   * Server-side, `AttachmentUploadHandler` recomputes sha256 with Java
   * `MessageDigest` and that result is authoritative.
   */
  static async sha256Hex(bytes: Uint8Array): Promise<string> {
    const cryptoObj: Crypto | undefined = (globalThis as any).crypto;
    if (cryptoObj && cryptoObj.subtle) {
      const buf = await cryptoObj.subtle.digest('SHA-256', bytes);
      return Array.from(new Uint8Array(buf))
        .map(b => b.toString(16).padStart(2, '0'))
        .join('');
    }
    return sha256HexPureJs(bytes);
  }
}

// Pure-JS SHA-256 (FIPS 180-4) — used when SubtleCrypto is unavailable
// (insecure context). Self-contained, no dependencies. Synchronous.
function sha256HexPureJs(bytes: Uint8Array): string {
  // Initial hash values (first 32 bits of fractional parts of square roots of first 8 primes).
  const H = new Uint32Array([
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
    0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
  ]);
  // Round constants (first 32 bits of fractional parts of cube roots of first 64 primes).
  const K = new Uint32Array([
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
  ]);

  const msgLen = bytes.length;
  const bitLen = msgLen * 8;
  // Pad: append 0x80, then zeros, so total length ≡ 56 (mod 64), then 8-byte big-endian length.
  const padded = new Uint8Array(((msgLen + 9 + 63) >>> 6) << 6);
  padded.set(bytes);
  padded[msgLen] = 0x80;
  // Write 64-bit big-endian length (high 32 bits are 0 for any practical browser-side image).
  const view = new DataView(padded.buffer);
  view.setUint32(padded.length - 4, bitLen >>> 0, false);
  view.setUint32(padded.length - 8, Math.floor(bitLen / 0x100000000) >>> 0, false);

  const W = new Uint32Array(64);
  for (let chunk = 0; chunk < padded.length; chunk += 64) {
    for (let i = 0; i < 16; i++) W[i] = view.getUint32(chunk + i * 4, false);
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(W[i - 15]!, 7) ^ rotr(W[i - 15]!, 18) ^ (W[i - 15]! >>> 3);
      const s1 = rotr(W[i - 2]!, 17) ^ rotr(W[i - 2]!, 19) ^ (W[i - 2]! >>> 10);
      W[i] = (W[i - 16]! + s0 + W[i - 7]! + s1) >>> 0;
    }
    let a = H[0]!, b = H[1]!, c = H[2]!, d = H[3]!, e = H[4]!, f = H[5]!, g = H[6]!, h = H[7]!;
    for (let i = 0; i < 64; i++) {
      const S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
      const ch = (e & f) ^ (~e & g);
      const t1 = (h + S1 + ch + K[i]! + W[i]!) >>> 0;
      const S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
      const mj = (a & b) ^ (a & c) ^ (b & c);
      const t2 = (S0 + mj) >>> 0;
      h = g; g = f; f = e; e = (d + t1) >>> 0;
      d = c; c = b; b = a; a = (t1 + t2) >>> 0;
    }
    H[0] = (H[0]! + a) >>> 0; H[1] = (H[1]! + b) >>> 0; H[2] = (H[2]! + c) >>> 0; H[3] = (H[3]! + d) >>> 0;
    H[4] = (H[4]! + e) >>> 0; H[5] = (H[5]! + f) >>> 0; H[6] = (H[6]! + g) >>> 0; H[7] = (H[7]! + h) >>> 0;
  }
  let hex = '';
  for (let i = 0; i < 8; i++) hex += H[i]!.toString(16).padStart(8, '0');
  return hex;
}

function rotr(x: number, n: number): number {
  return ((x >>> n) | (x << (32 - n))) >>> 0;
}

/**
 * Default confirmation when the caller doesn't supply one. Production wires a
 * styled modal; this is a fallback for tests/harness/dev.
 */
async function defaultConfirm(originalKB: number, capKB: number, filename: string): Promise<boolean> {
  if (typeof window === 'undefined' || typeof window.confirm !== 'function') return false;
  return window.confirm(
    `"${filename}" is ${originalKB} KB, which exceeds the ${capKB} KB image cap.\n\n` +
      `Compress it to JPEG so it fits? Compression is lossy — fine details may be reduced.\n\n` +
      `OK = compress and attach. Cancel = skip this image (no upload).`,
  );
}

/**
 * Best-effort lossy re-encode to JPEG until the result is under `capBytes`.
 *
 * Strategy: progressively lower quality first, then progressively halve
 * dimensions until the encoded result fits or we've exhausted the budget.
 * Returns the smallest result obtained; the caller compares against the cap
 * and surfaces an error if even the smallest pass is too large (e.g. an
 * already-tiny image with absurd metadata bloat — extremely rare).
 */
async function compressToJpegUnderCap(file: File, capBytes: number): Promise<File> {
  const bitmap = await createImageBitmap(file);
  let { width, height } = bitmap;
  const qualities = [0.85, 0.75, 0.65, 0.55, 0.45];
  let best: Blob | null = null;

  // Round 1: quality sweep at full resolution.
  for (const q of qualities) {
    const blob = await encodeJpeg(bitmap, width, height, q);
    if (!best || blob.size < best.size) best = blob;
    if (blob.size <= capBytes) {
      bitmap.close?.();
      return new File([blob], replaceExt(file.name, '.jpg'), { type: 'image/jpeg' });
    }
  }

  // Round 2: halve dimensions repeatedly at quality 0.75 until under cap or
  // dimensions get unreasonably small (≤ 256 px on the long side).
  while (Math.max(width, height) > 256) {
    width = Math.max(1, Math.round(width / 2));
    height = Math.max(1, Math.round(height / 2));
    const blob = await encodeJpeg(bitmap, width, height, 0.75);
    if (!best || blob.size < best.size) best = blob;
    if (blob.size <= capBytes) {
      bitmap.close?.();
      return new File([blob], replaceExt(file.name, '.jpg'), { type: 'image/jpeg' });
    }
  }

  bitmap.close?.();
  if (!best) throw new Error('compression produced no output');
  return new File([best], replaceExt(file.name, '.jpg'), { type: 'image/jpeg' });
}

async function encodeJpeg(
  bitmap: ImageBitmap,
  width: number,
  height: number,
  quality: number,
): Promise<Blob> {
  const canvas =
    typeof OffscreenCanvas !== 'undefined'
      ? new OffscreenCanvas(width, height)
      : Object.assign(document.createElement('canvas'), { width, height });
  const ctx = (canvas as any).getContext('2d');
  if (!ctx) throw new Error('2d canvas context unavailable');
  ctx.drawImage(bitmap, 0, 0, width, height);
  if ('convertToBlob' in canvas) {
    return await (canvas as OffscreenCanvas).convertToBlob({ type: 'image/jpeg', quality });
  }
  // HTMLCanvasElement fallback
  return await new Promise<Blob>((resolve, reject) => {
    (canvas as HTMLCanvasElement).toBlob(
      b => (b ? resolve(b) : reject(new Error('toBlob returned null'))),
      'image/jpeg',
      quality,
    );
  });
}

function replaceExt(name: string, newExt: string): string {
  const dot = name.lastIndexOf('.');
  return dot > 0 ? name.slice(0, dot) + newExt : name + newExt;
}
