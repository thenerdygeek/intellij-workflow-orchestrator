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
    if (!this.settings.enabled) {
      this.toast('Image input is disabled in settings.', 'warning');
      return null;
    }
    if (this.attachments.length >= this.settings.maxPerTurn) {
      this.toast(`At most ${this.settings.maxPerTurn} image(s) per turn.`, 'warning');
      return null;
    }
    if (!this.settings.mimeWhitelist.includes(file.type)) {
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
      const proceed = await this.confirmCompress(originalKB, capKB, file.name);
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
    } catch (e) {
      this.toast(`Could not read image bytes: ${(e as Error).message}`, 'error');
      return null;
    }

    let sha256: string;
    try {
      sha256 = await AttachmentManager.sha256Hex(bytes);
    } catch (e) {
      this.toast(`sha256 unavailable in this browser: ${(e as Error).message}`, 'error');
      return null;
    }

    // Within-attachments dedup: if the user pastes the same bytes twice in
    // one turn, just bump the existing chip — don't show two identical chips.
    const existing = this.attachments.find(a => a.sha256 === sha256);
    if (existing) {
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
    };
    this.attachments.push(att);
    this.onChange();
    return att;
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

  /** Hex-encoded sha256 of the bytes (lowercase). */
  static async sha256Hex(bytes: Uint8Array): Promise<string> {
    const cryptoObj: Crypto | undefined = (globalThis as any).crypto;
    if (!cryptoObj || !cryptoObj.subtle) {
      throw new Error('SubtleCrypto unavailable (insecure context?)');
    }
    const buf = await cryptoObj.subtle.digest('SHA-256', bytes);
    return Array.from(new Uint8Array(buf))
      .map(b => b.toString(16).padStart(2, '0'))
      .join('');
  }
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
