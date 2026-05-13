import { useState, useEffect, useRef } from 'react';
import { UsageIndicator } from '@/components/input/UsageIndicator';
import { ModelPickerRow } from '@/components/input/InputBar';
import { ChipPreview } from '@/components/input/ChipPreview';
import { AttachmentManager, type PendingAttachment } from '@/components/input/AttachmentManager';
import { ThinkingView } from '@/components/agent/ThinkingView';

/**
 * Playwright harness — renders the three Phase 7 / Phase 5 UI components in
 * isolation against the mock bridge. Each section has data-testid hooks so
 * Playwright can target it without depending on layout.
 *
 * Sections:
 *   §1 UsageIndicator — driven by `window.__harness.setContextUsage(used, max)`
 *   §2 ModelPickerRow — six fixed model variants exercising every capability
 *      icon + the no-capability + the deprecated paths
 *   §3 ChipPreview — synthetic attachments with an in-DOM remove handler
 */

const MODEL_VARIANTS: { label: string; model: any }[] = [
  {
    label: 'Anthropic — full caps',
    model: {
      id: 'anthropic::sonnet-4',
      name: 'Sonnet 4',
      provider: 'anthropic',
      thinking: false,
      contextWindow: { maxInputTokens: 132_000, maxUserInputTokens: 18_000 },
      capabilities: ['vision', 'tools', 'reasoning'],
      status: 'stable',
    },
  },
  {
    label: 'Anthropic — thinking variant',
    model: {
      id: 'anthropic::sonnet-4-thinking',
      name: 'Sonnet 4 (thinking)',
      provider: 'anthropic',
      thinking: true,
      contextWindow: { maxInputTokens: 93_000 },
      capabilities: ['vision', 'tools', 'reasoning'],
      status: 'stable',
    },
  },
  {
    label: 'OpenAI — vision + tools, no per-message cap',
    model: {
      id: 'openai::gpt-4o',
      name: 'GPT-4o',
      provider: 'openai',
      contextWindow: { maxInputTokens: 200_000 },
      capabilities: ['vision', 'tools'],
      status: 'stable',
    },
  },
  {
    label: 'Google — tools only, deprecated',
    model: {
      id: 'google::gemini-1.5-flash',
      name: 'Gemini 1.5 Flash',
      provider: 'google',
      contextWindow: { maxInputTokens: 1_000_000, maxUserInputTokens: 30_000 },
      capabilities: ['tools'],
      status: 'deprecated',
    },
  },
  {
    label: 'No capabilities — bare model',
    model: {
      id: 'misc::bare',
      name: 'Bare Model',
      provider: 'misc',
      contextWindow: { maxInputTokens: 8_000 },
      capabilities: [],
      status: 'stable',
    },
  },
  {
    label: 'Legacy payload — no enrichments at all',
    model: {
      id: 'legacy::no-fields',
      name: 'Legacy',
      provider: 'anthropic',
    },
  },
];

function syntheticAttachments(): PendingAttachment[] {
  // 1×1 transparent PNG data URLs so the chips render visually without
  // hitting the network. Two attachments to verify the row layout.
  const tinyPng =
    'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABAQMAAAAl21bKAAAAA1BMVEX///+nxBvIAAAAAXRSTlMAQObYZgAAAApJREFUCNdjYAAAAAIAAeIhvDMAAAAASUVORK5CYII=';
  return [
    {
      sha256: 'aaa1111111111111111111111111111111111111111111111111111111111111',
      mime: 'image/png',
      size: 92,
      originalFilename: 'red-square.png',
      bytes: new Uint8Array(),
      thumbnailUrl: tinyPng,
    },
    {
      sha256: 'bbb2222222222222222222222222222222222222222222222222222222222222',
      mime: 'image/jpeg',
      size: 18432,
      originalFilename: 'screenshot-2026-05-03.jpg',
      bytes: new Uint8Array(),
      thumbnailUrl: tinyPng,
    },
  ];
}

// ── §5 ThinkingView lifecycle simulation ──────────────────────────────────────
// Mirrors the exact ChatFooter → ChatView two-instance lifecycle that causes the
// duration label bug. 'streaming' renders ThinkingView(isStreaming=true) from
// one render branch; 'finalized' renders ThinkingView(isStreaming=false) from a
// DIFFERENT branch (different key → fresh mount, elapsed resets to 0).
type ThinkingPhase = 'idle' | 'streaming' | 'finalized';

function ThinkingBugSection() {
  const [phase, setPhase] = useState<ThinkingPhase>('idle');
  const [wallClockMs, setWallClockMs] = useState(0);
  const [finalizedAtMs, setFinalizedAtMs] = useState(0);
  const streamStartRef = useRef<number>(0);
  const CONTENT = 'Considering the problem from multiple angles...\n\nStep 1: Analyze the request.\nStep 2: Break it into sub-tasks.\nStep 3: Synthesize an answer.';

  // Wall-clock ticker during streaming so we can show the true elapsed time
  useEffect(() => {
    if (phase !== 'streaming') return;
    streamStartRef.current = Date.now();
    setWallClockMs(0);
    const id = setInterval(() => setWallClockMs(Date.now() - streamStartRef.current), 100);
    return () => clearInterval(id);
  }, [phase]);

  const startAndFinalize = (durationMs: number) => {
    setPhase('streaming');
    setWallClockMs(0);
    setTimeout(() => {
      const actual = Date.now() - streamStartRef.current;
      setFinalizedAtMs(actual);
      setPhase('finalized');
    }, durationMs);
  };

  return (
    <section data-testid="section-thinking-bug" className="space-y-3">
      <h2 className="text-sm font-semibold">
        §5 ThinkingView duration label bug (two-instance lifecycle)
      </h2>
      <p className="text-[11px]" style={{ color: 'var(--fg-muted, #9ca3af)' }}>
        Simulates the ChatFooter→ChatView handoff: streaming instance unmounts,
        finalized instance mounts fresh (elapsed=0 → always shows &quot;&lt;1s&quot;).
      </p>

      <div className="flex gap-2 flex-wrap">
        {(['idle', 'finalized'].includes(phase)) && (
          <>
            <button
              data-testid="stream-2s"
              onClick={() => startAndFinalize(2000)}
              className="px-3 py-1.5 text-xs rounded border"
              style={{ borderColor: 'var(--border, #2c2f33)' }}
            >
              Stream 2 s then finalize
            </button>
            <button
              data-testid="stream-4s"
              onClick={() => startAndFinalize(4000)}
              className="px-3 py-1.5 text-xs rounded border"
              style={{ borderColor: 'var(--border, #2c2f33)' }}
            >
              Stream 4 s then finalize
            </button>
          </>
        )}
        {phase !== 'idle' && (
          <button
            data-testid="reset-thinking"
            onClick={() => { setPhase('idle'); setWallClockMs(0); }}
            className="px-3 py-1.5 text-xs rounded border"
            style={{ borderColor: 'var(--border, #2c2f33)' }}
          >
            Reset
          </button>
        )}
      </div>

      {/* Status row */}
      {phase !== 'idle' && (
        <div className="text-[11px] font-mono" style={{ color: 'var(--fg-muted, #9ca3af)' }}>
          {phase === 'streaming' && (
            <span data-testid="wall-clock">
              ⏱ Wall-clock streaming: {(wallClockMs / 1000).toFixed(1)}s
            </span>
          )}
          {phase === 'finalized' && (
            <span data-testid="finalized-duration">
              ✓ Stream lasted {(finalizedAtMs / 1000).toFixed(1)}s — label should say
              &quot;Thought for {Math.round(finalizedAtMs / 1000)}s&quot; but shows:
            </span>
          )}
        </div>
      )}

      {/* Branch A — ChatFooter equivalent: ThinkingView(isStreaming=true) */}
      {phase === 'streaming' && (
        <div
          data-testid="thinking-streaming-branch"
          className="border rounded p-2"
          style={{ borderColor: 'var(--border, #2c2f33)' }}
        >
          <div className="text-[10px] uppercase tracking-wide mb-1" style={{ color: 'var(--fg-muted)' }}>
            Branch A — ChatFooter (isStreaming=true, timer running)
          </div>
          <ThinkingView key="thinking-branch-a" content={CONTENT} isStreaming={true} />
        </div>
      )}

      {/* Branch B — ChatView equivalent: fresh ThinkingView(isStreaming=false) */}
      {phase === 'finalized' && (
        <div
          data-testid="thinking-finalized-branch"
          className="border rounded p-2"
          style={{ borderColor: 'var(--border, #2c2f33)' }}
        >
          <div className="text-[10px] uppercase tracking-wide mb-1" style={{ color: 'var(--fg-muted)' }}>
            Branch B — ChatView (isStreaming=false, durationMs stamped → correct label)
          </div>
          <ThinkingView key="thinking-branch-b" content={CONTENT} isStreaming={false} durationMs={finalizedAtMs} />
        </div>
      )}
    </section>
  );
}

export function HarnessApp() {
  // Section-level controls
  const [usedK, setUsedK] = useState(0);
  const [maxK, setMaxK] = useState(132);
  const [chips, setChips] = useState<PendingAttachment[]>(syntheticAttachments());
  const [enableImageInput, setEnableImageInput] = useState(true);

  // §4 — oversize compression flow state
  const [compressLog, setCompressLog] = useState<string[]>([]);
  const [pendingPrompt, setPendingPrompt] = useState<{
    originalKB: number;
    capKB: number;
    filename: string;
    resolve: (proceed: boolean) => void;
  } | null>(null);

  // Sync slider values to the mock bridge so UsageIndicator's polling sees them.
  useEffect(() => {
    (window as any).__harness?.setContextUsage(usedK * 1000, maxK * 1000);
  }, [usedK, maxK]);

  useEffect(() => {
    (window as any).__harness?.setImageSettings({ enabled: enableImageInput });
    (window as any).__harness?.pushImageSettingsToWebview();
  }, [enableImageInput]);

  return (
    <div className="min-h-screen p-6 space-y-8" data-testid="harness-root">
      <header className="border-b pb-3" style={{ borderColor: 'var(--border, #2c2f33)' }}>
        <h1 className="text-lg font-semibold">Multimodal-agent UI harness</h1>
        <p className="text-xs" style={{ color: 'var(--fg-muted, #9ca3af)' }}>
          Mock-bridge mode. Use <code>window.__harness</code> from DevTools or
          Playwright to drive state.
        </p>
      </header>

      {/* ─────────── §1 UsageIndicator ─────────── */}
      <section data-testid="section-usage-indicator" className="space-y-3">
        <h2 className="text-sm font-semibold">§1 UsageIndicator (Phase 7 Task 7.2)</h2>
        <div className="flex items-center gap-4 text-xs">
          <label>
            used: <span data-testid="used-value">{usedK}K</span>
            <input
              type="range"
              min={0}
              max={200}
              value={usedK}
              onChange={e => setUsedK(Number(e.target.value))}
              data-testid="used-slider"
              className="ml-2 align-middle"
            />
          </label>
          <label>
            max: <span data-testid="max-value">{maxK}K</span>
            <input
              type="range"
              min={1}
              max={200}
              value={maxK}
              onChange={e => setMaxK(Number(e.target.value))}
              data-testid="max-slider"
              className="ml-2 align-middle"
            />
          </label>
        </div>
        <div
          className="border rounded p-2 inline-block"
          style={{ borderColor: 'var(--border, #2c2f33)', minWidth: 280 }}
        >
          <UsageIndicator />
        </div>
        <p className="text-[10px]" style={{ color: 'var(--fg-muted, #9ca3af)' }}>
          Color: gray &lt;50%, amber 50-80%, red &gt;80%. Component polls every 1s.
        </p>
      </section>

      {/* ─────────── §2 ModelPickerRow ─────────── */}
      <section data-testid="section-model-picker" className="space-y-3">
        <h2 className="text-sm font-semibold">§2 ModelPickerRow (Phase 7 Task 7.1)</h2>
        <div
          className="grid gap-3"
          style={{ gridTemplateColumns: 'repeat(2, minmax(280px, 1fr))' }}
        >
          {MODEL_VARIANTS.map(({ label, model }) => (
            <div
              key={model.id}
              data-testid={`picker-row-${model.id}`}
              className="border rounded p-3"
              style={{ borderColor: 'var(--border, #2c2f33)' }}
            >
              <div
                className="text-[10px] uppercase tracking-wide mb-2"
                style={{ color: 'var(--fg-muted, #9ca3af)' }}
              >
                {label}
              </div>
              <ModelPickerRow model={model} />
            </div>
          ))}
        </div>
      </section>

      {/* ─────────── §3 ChipPreview ─────────── */}
      <section data-testid="section-chip-preview" className="space-y-3">
        <h2 className="text-sm font-semibold">§3 ChipPreview (Phase 5 Task 5.6)</h2>
        <div className="flex items-center gap-3 text-xs">
          <button
            data-testid="reset-chips"
            onClick={() => setChips(syntheticAttachments())}
            className="px-2 py-1 rounded border"
            style={{ borderColor: 'var(--border, #2c2f33)' }}
          >
            Reset chips
          </button>
          <label className="flex items-center gap-1.5">
            <input
              type="checkbox"
              checked={enableImageInput}
              onChange={e => setEnableImageInput(e.target.checked)}
              data-testid="enable-image-input"
            />
            enableImageInput (drives Settings push)
          </label>
        </div>
        <div
          className="border rounded p-1 inline-block"
          style={{ borderColor: 'var(--border, #2c2f33)', minWidth: 280 }}
        >
          <ChipPreview
            attachments={chips}
            onRemove={sha => setChips(prev => prev.filter(a => a.sha256 !== sha))}
          />
          {chips.length === 0 && (
            <div className="px-3 py-4 text-[11px]" style={{ color: 'var(--fg-muted, #9ca3af)' }}>
              (no attachments)
            </div>
          )}
        </div>
        <p className="text-[10px]" style={{ color: 'var(--fg-muted, #9ca3af)' }}>
          Hover a chip to reveal the × button. Click × to remove.
        </p>
      </section>

      {/* ─────────── §4 Oversize compression flow ─────────── */}
      <section data-testid="section-compress" className="space-y-3">
        <h2 className="text-sm font-semibold">§4 Oversize image compression (v1.1)</h2>
        <p className="text-[11px]" style={{ color: 'var(--fg-muted, #9ca3af)' }}>
          Triggers <code>AttachmentManager.attachFile</code> with a synthetic
          file 2× the cap. The harness wires a real <code>confirmCompress</code>
          callback that pops the modal below — Cancel rejects without upload,
          Compress proceeds (and may fail in jsdom because{' '}
          <code>OffscreenCanvas</code> isn't available).
        </p>
        <button
          data-testid="trigger-oversize-attach"
          className="px-3 py-1.5 text-xs rounded border"
          style={{ borderColor: 'var(--border, #2c2f33)' }}
          onClick={async () => {
            const settings = {
              maxBytes: 1024,
              mimeWhitelist: ['image/png', 'image/jpeg'],
              maxPerTurn: 2,
              enabled: true,
            };
            const log = (s: string) => setCompressLog(prev => [...prev, s]);
            const mgr = new AttachmentManager(
              settings,
              () => {},
              (msg, type) => log(`[${type ?? 'info'}] ${msg}`),
              (originalKB, capKB, filename) => {
                log(`prompt: ${filename} ${originalKB}KB > ${capKB}KB cap`);
                return new Promise<boolean>(resolve => {
                  setPendingPrompt({ originalKB, capKB, filename, resolve });
                });
              },
            );
            const oversize = new File(
              [new Uint8Array(2048)],
              'huge-screenshot.png',
              { type: 'image/png' },
            );
            const result = await mgr.attachFile(oversize);
            log(`attachFile returned: ${result === null ? 'null (rejected)' : 'attachment'}`);
          }}
        >
          Trigger oversize attach
        </button>
        <div
          data-testid="compress-log"
          className="border rounded p-2 text-[11px] font-mono whitespace-pre"
          style={{ borderColor: 'var(--border, #2c2f33)', minHeight: 80 }}
        >
          {compressLog.length === 0 ? '(no events yet)' : compressLog.join('\n')}
        </div>
        {pendingPrompt && (
          <div
            role="dialog"
            aria-modal="true"
            aria-labelledby="harness-compress-title"
            data-testid="harness-compress-modal"
            className="fixed inset-0 z-50 flex items-center justify-center"
            style={{ background: 'rgba(0,0,0,0.5)' }}
            onClick={() => {
              pendingPrompt.resolve(false);
              setPendingPrompt(null);
            }}
          >
            <div
              className="rounded-md p-4 max-w-sm w-full mx-4 shadow-lg"
              style={{
                background: 'var(--bg, #1e1e1e)',
                color: 'var(--fg, #cccccc)',
                border: '1px solid var(--border, #2c2f33)',
              }}
              onClick={e => e.stopPropagation()}
            >
              <h3 id="harness-compress-title" className="text-sm font-semibold mb-2">
                Image exceeds size cap
              </h3>
              <p className="text-xs mb-2" style={{ color: 'var(--fg-muted, #9ca3af)' }}>
                <strong>{pendingPrompt.filename}</strong> is{' '}
                <strong>{pendingPrompt.originalKB.toLocaleString()} KB</strong>, exceeds the{' '}
                <strong>{pendingPrompt.capKB.toLocaleString()} KB</strong> cap.
              </p>
              <p className="text-xs mb-3" style={{ color: 'var(--fg-muted, #9ca3af)' }}>
                Compress to JPEG so it fits? Compression is <em>lossy</em>.
              </p>
              <div className="flex justify-end gap-2 mt-3">
                <button
                  data-testid="harness-compress-cancel"
                  onClick={() => {
                    pendingPrompt.resolve(false);
                    setPendingPrompt(null);
                  }}
                  className="px-3 py-1.5 text-xs rounded border"
                  style={{ borderColor: 'var(--border, #2c2f33)' }}
                >
                  Cancel (skip image)
                </button>
                <button
                  data-testid="harness-compress-confirm"
                  onClick={() => {
                    pendingPrompt.resolve(true);
                    setPendingPrompt(null);
                  }}
                  className="px-3 py-1.5 text-xs rounded font-medium"
                  style={{
                    background: 'var(--accent, #60a5fa)',
                    color: 'var(--bg, #1e1e1e)',
                    border: '1px solid var(--accent, #60a5fa)',
                  }}
                >
                  Compress &amp; attach
                </button>
              </div>
            </div>
          </div>
        )}
      </section>

      <ThinkingBugSection />
    </div>
  );
}
