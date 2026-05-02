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

  constructor(
    settings: AttachmentManagerSettings,
    onChange: () => void,
    toast: ToastFn,
  ) {
    this.settings = settings;
    this.onChange = onChange;
    this.toast = toast;
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
    if (file.size > this.settings.maxBytes) {
      const kb = Math.round(file.size / 1024);
      const cap = Math.round(this.settings.maxBytes / 1024);
      this.toast(`Image too large: ${kb} KB exceeds ${cap} KB cap.`, 'warning');
      return null;
    }
    if (!this.settings.mimeWhitelist.includes(file.type)) {
      this.toast(`Image type "${file.type || 'unknown'}" is not in the allowed list.`, 'warning');
      return null;
    }

    let bytes: Uint8Array;
    try {
      bytes = new Uint8Array(await file.arrayBuffer());
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

    const thumbnailUrl = URL.createObjectURL(new Blob([bytes], { type: file.type }));
    const att: PendingAttachment = {
      sha256,
      mime: file.type,
      size: file.size,
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
