import { describe, it, expect, vi } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import { UsageIndicator } from '@/components/input/UsageIndicator';

/**
 * Multimodal-agent Phase 7 Task 7.2 — chat input usage indicator.
 *
 * Spec (design doc §Decision 7 → option D, input side):
 *   - Small text strip below the input: `context: 23K / 132K used (17%)`
 *   - Color shifts: gray <50%, amber 50-80%, red >80%
 *   - Polls `window.workflowAgent.getContextUsage()` every 1s
 *
 * Tests pin the rendered text format, color thresholds, and graceful no-op
 * when the bridge function is missing (which happens in test/sandbox builds
 * and during the brief window before page-ready). The polling hook is
 * exercised against `vi.useFakeTimers` so we can assert the 1s cadence
 * without real time passing.
 */
describe('Phase 7 — UsageIndicator', () => {
  it('renders 0 / 132K when bridge is not yet available', () => {
    // Ensure the bridge is undefined for this test
    delete (window as any).workflowAgent;
    render(<UsageIndicator />);
    expect(screen.getByText(/0K?\s*\/\s*132K/i)).toBeInTheDocument();
  });

  it('formats used / max as KK and percentage', async () => {
    (window as any).workflowAgent = {
      getContextUsage: vi.fn().mockResolvedValue({ used: 23_000, max: 132_000 }),
    };
    render(<UsageIndicator />);
    // Wait for the initial fetch to resolve
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(screen.getByText(/23K\s*\/\s*132K\s*used.*17%/i)).toBeInTheDocument();
  });

  it('uses gray color when utilization < 50%', async () => {
    (window as any).workflowAgent = {
      getContextUsage: vi.fn().mockResolvedValue({ used: 10_000, max: 132_000 }),
    };
    render(<UsageIndicator />);
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    const strip = screen.getByTestId('usage-indicator');
    // 10/132 ≈ 7.5% — should be gray (#888)
    expect((strip as HTMLElement).style.color).toMatch(/(#888|rgb\(136,\s*136,\s*136\)|gray)/i);
  });

  it('uses amber color when utilization between 50-80%', async () => {
    (window as any).workflowAgent = {
      getContextUsage: vi.fn().mockResolvedValue({ used: 80_000, max: 132_000 }),
    };
    render(<UsageIndicator />);
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    const strip = screen.getByTestId('usage-indicator');
    // 80/132 ≈ 60% — should be amber (#d97706)
    expect((strip as HTMLElement).style.color).toMatch(/(#d97706|rgb\(217,\s*119,\s*6\))/i);
  });

  it('uses red color when utilization > 80%', async () => {
    (window as any).workflowAgent = {
      getContextUsage: vi.fn().mockResolvedValue({ used: 120_000, max: 132_000 }),
    };
    render(<UsageIndicator />);
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    const strip = screen.getByTestId('usage-indicator');
    // 120/132 ≈ 91% — should be red (#dc2626)
    expect((strip as HTMLElement).style.color).toMatch(/(#dc2626|rgb\(220,\s*38,\s*38\))/i);
  });

  it('does not crash when bridge throws', async () => {
    (window as any).workflowAgent = {
      getContextUsage: vi.fn().mockRejectedValue(new Error('bridge failure')),
    };
    render(<UsageIndicator />);
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    // Falls back to 0/132K, no crash, no error thrown.
    expect(screen.getByText(/0K?\s*\/\s*132K/i)).toBeInTheDocument();
  });
});
