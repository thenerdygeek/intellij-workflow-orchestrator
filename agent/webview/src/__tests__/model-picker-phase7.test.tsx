import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ModelPickerRow } from '@/components/input/InputBar';

/**
 * Multimodal-agent Phase 7 — model picker capacity column + capability badges.
 *
 * **Spec (design doc §Decision 7 → option D, picker side):**
 *
 *   - Per-row: `132K context · 18K per-message` capacity strip
 *   - Per-row: `👁 vision · 🔧 tools · 🧠 reasoning · ⚠ deprecated` icon strip
 *
 * Source: payload pushed by Kotlin's `updateModelList(json)` — Phase 7 extends
 * it with `contextWindow` (object), `capabilities` (array), and `status`
 * (string). All optional, so legacy payloads still render.
 *
 * **Tests pin** the new columns/icons rendering when the payload includes the
 * new fields, and the graceful no-op path when older payloads (without the
 * new fields) are pushed. Tests render `<ModelPickerRow>` directly to avoid
 * Radix's portal-mounted `<DropdownMenuContent>` which jsdom doesn't fully
 * support.
 */
describe('Phase 7 — Model picker capacity + tags', () => {
  it('renders capacity column when contextWindow is provided', () => {
    render(
      <ModelPickerRow model={{
        id: 'anthropic::claude-sonnet-4-5',
        name: 'claude-sonnet-4-5',
        provider: 'anthropic',
        thinking: false,
        vision: true,
        contextWindow: { maxInputTokens: 132_000, maxUserInputTokens: 18_000 },
        capabilities: ['vision', 'tools'],
        status: 'stable',
      }} />,
    );
    // Capacity strip: "132K context · 18K per-message"
    expect(screen.getByText(/132K context.*18K per-message/i)).toBeInTheDocument();
  });

  it('omits per-message segment when maxUserInputTokens is missing', () => {
    render(
      <ModelPickerRow model={{
        id: 'm1', name: 'big-context', provider: 'anthropic',
        contextWindow: { maxInputTokens: 200_000 },
        capabilities: ['tools'],
        status: 'stable',
      }} />,
    );
    expect(screen.getByText(/200K context$/)).toBeInTheDocument();
  });

  it('renders 👁 vision badge when capabilities includes vision', () => {
    render(
      <ModelPickerRow model={{
        id: 'm', name: 'multimodal', capabilities: ['vision'],
        contextWindow: { maxInputTokens: 100_000 }, status: 'stable',
      }} />,
    );
    const vision = screen.getByTitle('vision');
    expect(vision.textContent).toContain('\u{1F441}');
  });

  it('renders 🔧 tools badge when capabilities includes tools', () => {
    render(
      <ModelPickerRow model={{
        id: 'm', name: 'tools-model', capabilities: ['tools'],
        contextWindow: { maxInputTokens: 100_000 }, status: 'stable',
      }} />,
    );
    const tools = screen.getByTitle('tools');
    expect(tools.textContent).toContain('\u{1F527}');
  });

  it('renders 🧠 reasoning badge when capabilities includes reasoning', () => {
    render(
      <ModelPickerRow model={{
        id: 'r', name: 'reasoner', capabilities: ['reasoning'],
        contextWindow: { maxInputTokens: 100_000 }, status: 'stable',
      }} />,
    );
    const reasoning = screen.getByTitle('reasoning');
    expect(reasoning.textContent).toContain('\u{1F9E0}');
  });

  it('renders ⚠ deprecated badge when status=deprecated', () => {
    render(
      <ModelPickerRow model={{
        id: 'old', name: 'legacy-claude', capabilities: ['tools'],
        contextWindow: { maxInputTokens: 100_000 }, status: 'deprecated',
      }} />,
    );
    const dep = screen.getByTitle('deprecated');
    expect(dep.textContent).toContain('⚠');
  });

  it('renders all four badges together when applicable', () => {
    render(
      <ModelPickerRow model={{
        id: 'kitchen-sink', name: 'everything', provider: 'anthropic',
        capabilities: ['vision', 'tools', 'reasoning'],
        contextWindow: { maxInputTokens: 132_000, maxUserInputTokens: 18_000 },
        status: 'deprecated',
      }} />,
    );
    expect(screen.getByTitle('vision')).toBeInTheDocument();
    expect(screen.getByTitle('tools')).toBeInTheDocument();
    expect(screen.getByTitle('reasoning')).toBeInTheDocument();
    expect(screen.getByTitle('deprecated')).toBeInTheDocument();
  });

  it('does NOT crash on legacy payload without contextWindow / capabilities / status', () => {
    render(
      <ModelPickerRow model={{
        id: 'legacy', name: 'legacy-model', provider: 'openai',
        // no contextWindow, no capabilities, no status
      }} />,
    );
    expect(screen.getByText('legacy-model')).toBeInTheDocument();
    // No capacity strip
    expect(screen.queryByText(/context/i)).not.toBeInTheDocument();
    // No badges
    expect(screen.queryByTitle('vision')).not.toBeInTheDocument();
    expect(screen.queryByTitle('deprecated')).not.toBeInTheDocument();
  });
});
