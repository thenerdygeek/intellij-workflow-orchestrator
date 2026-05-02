import { useState, useEffect } from 'react';
import { UsageIndicator } from '@/components/input/UsageIndicator';
import { ModelPickerRow } from '@/components/input/InputBar';
import { ChipPreview } from '@/components/input/ChipPreview';
import type { PendingAttachment } from '@/components/input/AttachmentManager';

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

export function HarnessApp() {
  // Section-level controls
  const [usedK, setUsedK] = useState(0);
  const [maxK, setMaxK] = useState(132);
  const [chips, setChips] = useState<PendingAttachment[]>(syntheticAttachments());
  const [enableImageInput, setEnableImageInput] = useState(true);

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
    </div>
  );
}
