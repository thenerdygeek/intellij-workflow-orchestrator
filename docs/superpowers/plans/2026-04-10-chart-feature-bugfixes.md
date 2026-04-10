# Chart Feature Bugfix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 6 bugs in the interactive charts feature — broken rendering path, dead registry cleanup, duplicate updateChart logic, shallow merges, lost theme colors, and silent error swallowing.

**Architecture:** All changes are in `agent/webview/src/`. Extract a shared `chartUtils.ts` for the deep-merge utility and the single canonical `updateChart` function. Fix the rendering gap in `AgentMessage.tsx`. Fix the dead-code cleanup in `ChartView.tsx`. Add `console.warn` to all silent catch blocks. All fixes are in the React/TS webview layer — no Kotlin changes needed.

**Tech Stack:** TypeScript, React, Chart.js, Vitest, Zustand

---

## File Structure

| Action | File | Responsibility |
|--------|------|---------------|
| Create | `agent/webview/src/components/rich/chartUtils.ts` | Deep-merge utility + canonical `updateChartById()` function + chart registry export |
| Modify | `agent/webview/src/components/rich/ChartView.tsx` | Fix dead-code cleanup, use shared utils, fix theme merge, add console.warn |
| Modify | `agent/webview/src/components/chat/AgentMessage.tsx:142-191` | Add `type === 'chart'` handler for system messages |
| Modify | `agent/webview/src/bridge/jcef-bridge.ts:196-214,282-307` | Delegate updateChart to shared util, add console.warn to 3 silent catches |
| Modify | `agent/webview/src/stores/chatStore.ts:1008-1022` | Add console.warn to addArtifact catch block |
| Create | `agent/webview/src/__tests__/chart-utils.test.ts` | Unit tests for deep-merge and updateChartById |
| Create | `agent/webview/src/__tests__/chart-rendering.test.tsx` | Tests for the AgentMessage chart rendering path |

---

### Task 1: Extract shared chart utilities (`chartUtils.ts`)

**Files:**
- Create: `agent/webview/src/components/rich/chartUtils.ts`
- Create: `agent/webview/src/__tests__/chart-utils.test.ts`

This task creates the deep-merge utility and the canonical `updateChartById` function that both `ChartView.tsx` and `jcef-bridge.ts` will delegate to, eliminating the duplication (Issue #3) and the shallow merge problem (Issue #4).

- [ ] **Step 1: Write failing tests for `deepMergeChartConfig`**

Create `agent/webview/src/__tests__/chart-utils.test.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { deepMergeChartConfig } from '../components/rich/chartUtils';

describe('deepMergeChartConfig', () => {
  it('merges top-level properties', () => {
    const target = { a: 1, b: 2 };
    const source = { b: 3, c: 4 };
    expect(deepMergeChartConfig(target, source)).toEqual({ a: 1, b: 3, c: 4 });
  });

  it('deep-merges nested objects', () => {
    const target = { plugins: { legend: { labels: { color: '#fff' } } } };
    const source = { plugins: { legend: { labels: { font: { size: 14 } } } } };
    expect(deepMergeChartConfig(target, source)).toEqual({
      plugins: { legend: { labels: { color: '#fff', font: { size: 14 } } } },
    });
  });

  it('replaces arrays entirely (Chart.js datasets)', () => {
    const target = { datasets: [{ label: 'A', data: [1, 2] }] };
    const source = { datasets: [{ label: 'A', data: [10, 20] }, { label: 'B', data: [3, 4] }] };
    expect(deepMergeChartConfig(target, source)).toEqual({
      datasets: [{ label: 'A', data: [10, 20] }, { label: 'B', data: [3, 4] }],
    });
  });

  it('does not mutate the target object', () => {
    const target = { a: { b: 1 } };
    const source = { a: { c: 2 } };
    const original = JSON.parse(JSON.stringify(target));
    deepMergeChartConfig(target, source);
    expect(target).toEqual(original);
  });

  it('handles null/undefined source gracefully', () => {
    const target = { a: 1 };
    expect(deepMergeChartConfig(target, undefined as any)).toEqual({ a: 1 });
    expect(deepMergeChartConfig(target, null as any)).toEqual({ a: 1 });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent/webview && npx vitest run src/__tests__/chart-utils.test.ts`
Expected: FAIL — module `chartUtils` does not exist

- [ ] **Step 3: Implement `chartUtils.ts`**

Create `agent/webview/src/components/rich/chartUtils.ts`:

```typescript
// ── Chart registry (singleton, shared across ChartView and bridge) ──

export const chartRegistry = new Map<string, any>();
(window as any).__chartRegistry = chartRegistry;

// ── Deep merge for Chart.js configs ──

function isPlainObject(val: unknown): val is Record<string, unknown> {
  return val !== null && typeof val === 'object' && !Array.isArray(val);
}

/**
 * Deep-merge two Chart.js config objects. Arrays are replaced (not concatenated)
 * because Chart.js datasets are positional. Objects are recursively merged.
 * Does not mutate either input — returns a new object.
 */
export function deepMergeChartConfig(
  target: Record<string, unknown>,
  source: Record<string, unknown>,
): Record<string, unknown> {
  if (!source || typeof source !== 'object') return { ...target };

  const result: Record<string, unknown> = { ...target };

  for (const key of Object.keys(source)) {
    const targetVal = target[key];
    const sourceVal = source[key];

    if (isPlainObject(targetVal) && isPlainObject(sourceVal)) {
      result[key] = deepMergeChartConfig(targetVal, sourceVal);
    } else {
      result[key] = sourceVal;
    }
  }

  return result;
}

// ── Canonical updateChart function ──

/**
 * Update an existing chart by ID in the global registry.
 * Uses deep merge for data and options to preserve nested structures.
 * Returns true if the chart was found and updated, false otherwise.
 */
export function updateChartById(id: string, json: string): boolean {
  const chart = chartRegistry.get(id);
  if (!chart || !chart.canvas?.isConnected) return false;

  try {
    const update = JSON.parse(json);
    let needsUpdate = false;

    if (update.data) {
      const merged = deepMergeChartConfig(chart.data, update.data);
      Object.assign(chart.data, merged);
      needsUpdate = true;
    }
    if (update.options) {
      const merged = deepMergeChartConfig(chart.options, update.options);
      Object.assign(chart.options, merged);
      needsUpdate = true;
    }
    if (needsUpdate) {
      chart.update('active');
    }
    return true;
  } catch (e) {
    console.warn('[chart] updateChartById: malformed JSON for chart', id, e);
    return false;
  }
}

// ── Registry cleanup helper ──

/**
 * Remove a chart instance from the registry by identity.
 * Call this before destroying a Chart.js instance to prevent stale entries.
 */
export function removeChartFromRegistry(chartInstance: any): void {
  for (const [key, val] of chartRegistry) {
    if (val === chartInstance) {
      chartRegistry.delete(key);
      break;
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd agent/webview && npx vitest run src/__tests__/chart-utils.test.ts`
Expected: PASS (all 5 tests)

- [ ] **Step 5: Commit**

```bash
git add agent/webview/src/components/rich/chartUtils.ts agent/webview/src/__tests__/chart-utils.test.ts
git commit -m "feat(chart): extract shared chartUtils — deep merge, canonical updateChartById, registry helpers"
```

---

### Task 2: Fix dead-code registry cleanup and use shared utils in ChartView

**Files:**
- Modify: `agent/webview/src/components/rich/ChartView.tsx`

This task fixes Issue #2 (dead-code registry cleanup) and integrates the shared utilities from Task 1. Also fixes Issue #5 (theme colors lost on partial legend/title config) and Issue #6 (silent error swallowing) within this file.

- [ ] **Step 1: Replace the module-level registry and global updateChart**

In `ChartView.tsx`, replace lines 1-26 (the imports, local registry, and global `updateChart` function):

Old code (lines 1-26):
```typescript
import { useEffect, useRef, useState, useCallback } from 'react';
import { RichBlock } from './RichBlock';
import { useThemeStore } from '@/stores/themeStore';

// ── Chart instance registry for incremental updates ──

const chartRegistry = new Map<string, any>();
(window as any).__chartRegistry = chartRegistry;

// Global function callable from Kotlin via JCEF to push incremental chart data updates
(window as any).updateChart = (id: string, json: string) => {
  const chart = chartRegistry.get(id);
  if (chart && chart.canvas?.isConnected) {
    try {
      const update = JSON.parse(json);
      if (update.data) {
        Object.assign(chart.data, update.data);
        chart.update('active');
      }
      if (update.options) {
        Object.assign(chart.options, update.options);
        chart.update('active');
      }
    } catch { /* ignore malformed JSON */ }
  }
};
```

New code:
```typescript
import { useEffect, useRef, useState, useCallback } from 'react';
import { RichBlock } from './RichBlock';
import { useThemeStore } from '@/stores/themeStore';
import { chartRegistry, updateChartById, removeChartFromRegistry, deepMergeChartConfig } from './chartUtils';

// Global function callable from Kotlin via JCEF — delegates to shared utility
(window as any).updateChart = (id: string, json: string) => {
  updateChartById(id, json);
};
```

- [ ] **Step 2: Fix the dead-code registry cleanup in `renderChart`**

In `ChartView.tsx`, replace the destroy + dead cleanup block (lines 124-128 and 211-219):

Old code (lines 124-128):
```typescript
      // Destroy previous chart instance
      if (chartRef.current) {
        chartRef.current.destroy();
        chartRef.current = null;
      }
```

New code:
```typescript
      // Destroy previous chart instance — clean registry BEFORE nulling ref
      if (chartRef.current) {
        removeChartFromRegistry(chartRef.current);
        chartRef.current.destroy();
        chartRef.current = null;
      }
```

Then delete the dead-code block entirely (lines 211-219):
```typescript
      // If previous chart had an ID, remove it from registry before creating new
      if (chartRef.current) {
        for (const [key, val] of chartRegistry) {
          if (val === chartRef.current) {
            chartRegistry.delete(key);
            break;
          }
        }
      }
```

- [ ] **Step 3: Fix incremental update to use deep merge (lines 140-157)**

Old code (lines 140-157):
```typescript
      // Handle incremental update action
      if (config.action === 'update' && chartId) {
        const existing = chartRegistry.get(chartId);
        if (existing && existing.canvas?.isConnected) {
          // Merge new data into existing chart
          if (config.data) {
            Object.assign(existing.data, config.data);
          }
          if (config.options) {
            Object.assign(existing.options, config.options);
          }
          existing.update('active');
          setIsReady(true);
          return;
        }
        // Canvas is stale — remove from registry, fall through to create new
        chartRegistry.delete(chartId);
      }
```

New code:
```typescript
      // Handle incremental update action
      if (config.action === 'update' && chartId) {
        const existing = chartRegistry.get(chartId);
        if (existing && existing.canvas?.isConnected) {
          // Deep-merge new data into existing chart (preserves nested structures)
          if (config.data) {
            const merged = deepMergeChartConfig(existing.data, config.data as Record<string, unknown>);
            Object.assign(existing.data, merged);
          }
          if (config.options) {
            const merged = deepMergeChartConfig(existing.options, config.options as Record<string, unknown>);
            Object.assign(existing.options, merged);
          }
          existing.update('active');
          setIsReady(true);
          return;
        }
        // Canvas is stale — remove from registry, fall through to create new
        chartRegistry.delete(chartId);
      }
```

- [ ] **Step 4: Fix theme color merge for legend and title (lines 192-209)**

Old code (lines 192-209):
```typescript
      // Re-apply plugins/scales after spread (spread of config.options above would overwrite them)
      const finalOptions = chartConfig.options as Record<string, unknown>;
      finalOptions.plugins = {
        ...(config.options as Record<string, unknown>)?.plugins as Record<string, unknown> ?? {},
        legend: {
          labels: { color: fg },
          ...((config.options as Record<string, unknown>)?.plugins as Record<string, unknown>)?.legend as Record<string, unknown> ?? {},
        },
        title: {
          color: fg,
          ...((config.options as Record<string, unknown>)?.plugins as Record<string, unknown>)?.title as Record<string, unknown> ?? {},
        },
      };
      finalOptions.scales = applyScaleColors(
        (config.options as Record<string, unknown>)?.scales as Record<string, unknown> | undefined,
        fg,
        gridColor,
      );
```

New code:
```typescript
      // Build theme defaults, then deep-merge user config on top
      const userOptions = (config.options ?? {}) as Record<string, unknown>;
      const userPlugins = (userOptions.plugins ?? {}) as Record<string, unknown>;
      const userLegend = (userPlugins.legend ?? {}) as Record<string, unknown>;
      const userLegendLabels = (userLegend.labels ?? {}) as Record<string, unknown>;
      const userTitle = (userPlugins.title ?? {}) as Record<string, unknown>;

      const finalOptions = chartConfig.options as Record<string, unknown>;
      finalOptions.plugins = {
        ...userPlugins,
        legend: {
          ...userLegend,
          labels: { color: fg, ...userLegendLabels },
        },
        title: {
          color: fg,
          ...userTitle,
        },
      };
      finalOptions.scales = applyScaleColors(
        userOptions.scales as Record<string, unknown> | undefined,
        fg,
        gridColor,
      );
```

Also remove the redundant first-pass plugins/scales in the `chartConfig` construction (lines 165-189) — replace the options block:

Old code (lines 165-189):
```typescript
      const chartConfig = {
        ...config,
        options: {
          responsive: true,
          maintainAspectRatio: true,
          animation: {
            duration: 800,
            easing: 'easeOutQuart' as const,
          },
          plugins: {
            legend: {
              labels: { color: fg },
            },
            title: {
              color: fg,
            },
            ...(config.options as Record<string, unknown>)?.plugins as Record<string, unknown> ?? {},
          },
          scales: applyScaleColors(
            (config.options as Record<string, unknown>)?.scales as Record<string, unknown> | undefined,
            fg,
            gridColor,
          ),
          ...(config.options as Record<string, unknown> ?? {}),
        },
      };
```

New code:
```typescript
      const chartConfig = {
        ...config,
        options: {
          responsive: true,
          maintainAspectRatio: true,
          animation: {
            duration: 800,
            easing: 'easeOutQuart' as const,
          },
          ...userOptions,
        },
      };
```

- [ ] **Step 5: Fix the unmount cleanup to use shared helper (lines 239-251)**

Old code:
```typescript
    return () => {
      if (chartRef.current) {
        // Remove from registry on unmount
        for (const [key, val] of chartRegistry) {
          if (val === chartRef.current) {
            chartRegistry.delete(key);
            break;
          }
        }
        chartRef.current.destroy();
        chartRef.current = null;
      }
    };
```

New code:
```typescript
    return () => {
      if (chartRef.current) {
        removeChartFromRegistry(chartRef.current);
        chartRef.current.destroy();
        chartRef.current = null;
      }
    };
```

- [ ] **Step 6: Add console.warn to the chartMeta catch (line 277)**

Old code:
```typescript
    } catch { return { type: 'chart', title: null }; }
```

New code:
```typescript
    } catch (e) {
      console.warn('[chart] Failed to parse chart meta from source', e);
      return { type: 'chart', title: null };
    }
```

- [ ] **Step 7: Run all existing webview tests to verify no regressions**

Run: `cd agent/webview && npx vitest run`
Expected: All existing tests PASS

- [ ] **Step 8: Commit**

```bash
git add agent/webview/src/components/rich/ChartView.tsx
git commit -m "fix(chart): dead-code registry cleanup, deep merge for updates, theme color preservation"
```

---

### Task 3: Fix `addChart` system messages never rendering (AgentMessage)

**Files:**
- Modify: `agent/webview/src/components/chat/AgentMessage.tsx:142-191`
- Create: `agent/webview/src/__tests__/chart-rendering.test.tsx`

This task fixes Issue #1 (the most critical bug) — `addChart` system messages with `type === 'chart'` fall through to `return null`.

- [ ] **Step 1: Write failing test for chart system message rendering**

Create `agent/webview/src/__tests__/chart-rendering.test.tsx`:

```tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { AgentMessage } from '../components/chat/AgentMessage';

// Mock ChartView since Chart.js requires canvas — we just verify it gets rendered
vi.mock('@/components/rich/ChartView', () => ({
  ChartView: ({ source }: { source: string }) => (
    <div data-testid="chart-view" data-source={source} />
  ),
}));

describe('AgentMessage: chart system messages', () => {
  it('renders ChartView for system messages with type=chart', () => {
    const chartConfig = JSON.stringify({
      type: 'bar',
      data: { labels: ['A', 'B'], datasets: [{ data: [1, 2] }] },
    });
    const message = {
      id: 'chart-1',
      role: 'system' as const,
      content: JSON.stringify({ type: 'chart', config: chartConfig }),
      timestamp: Date.now(),
    };

    render(<AgentMessage message={message} />);

    const chartView = screen.getByTestId('chart-view');
    expect(chartView).toBeInTheDocument();
    expect(chartView.getAttribute('data-source')).toBe(chartConfig);
  });

  it('does not render ChartView for non-chart system messages', () => {
    const message = {
      id: 'status-1',
      role: 'system' as const,
      content: JSON.stringify({ type: 'status', message: 'Working...' }),
      timestamp: Date.now(),
    };

    const { container } = render(<AgentMessage message={message} />);

    expect(screen.queryByTestId('chart-view')).not.toBeInTheDocument();
    // Status messages render a div with the text
    expect(container.textContent).toContain('Working...');
  });

  it('still returns null for unknown system message types', () => {
    const message = {
      id: 'unknown-1',
      role: 'system' as const,
      content: JSON.stringify({ type: 'unknown-future-type' }),
      timestamp: Date.now(),
    };

    const { container } = render(<AgentMessage message={message} />);
    expect(container.innerHTML).toBe('');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent/webview && npx vitest run src/__tests__/chart-rendering.test.tsx`
Expected: FAIL — first test fails because ChartView is not rendered (returns null)

- [ ] **Step 3: Add the `chart` handler in AgentMessage.tsx**

In `AgentMessage.tsx`, add the import at the top (after existing imports):

```typescript
import { ChartView } from '@/components/rich/ChartView';
```

Then add the chart handler after the `status` handler (after line 156, before the diff-explanation block):

```typescript
      if (parsed.type === 'chart' && parsed.config) {
        return <ChartView source={parsed.config} />;
      }
```

The full system message block (lines 142-191) becomes:

```typescript
  // System messages: thinking blocks, status lines, and charts
  if (message.role === 'system') {
    try {
      const parsed = JSON.parse(message.content) as Record<string, any>;
      if (parsed.type === 'thinking') {
        return <ThinkingView content={parsed.text ?? ''} isStreaming={false} />;
      }
      if (parsed.type === 'completion') {
        return <CompletionCard result={parsed.result ?? ''} verifyCommand={parsed.verifyCommand} />;
      }
      if (parsed.type === 'status') {
        return (
          <div className="px-1 py-0.5 text-[11px]" style={{ color: 'var(--fg-muted, #888)' }}>
            {parsed.message}
          </div>
        );
      }
      if (parsed.type === 'chart' && parsed.config) {
        return <ChartView source={parsed.config} />;
      }
      // Diff explanation from generate_explanation tool
      if (parsed.type === 'diff-explanation' && parsed.diffSource) {
        // ... (rest unchanged)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd agent/webview && npx vitest run src/__tests__/chart-rendering.test.tsx`
Expected: PASS (all 3 tests)

- [ ] **Step 5: Run all webview tests for regression check**

Run: `cd agent/webview && npx vitest run`
Expected: All tests PASS

- [ ] **Step 6: Commit**

```bash
git add agent/webview/src/components/chat/AgentMessage.tsx agent/webview/src/__tests__/chart-rendering.test.tsx
git commit -m "fix(chart): render addChart system messages — was silently returning null"
```

---

### Task 4: Eliminate duplicate `updateChart` in jcef-bridge and add console.warn

**Files:**
- Modify: `agent/webview/src/bridge/jcef-bridge.ts:196-214`

This task fixes Issue #3 (duplicate implementations) and Issue #6 (silent error swallowing in the bridge).

- [ ] **Step 1: Add import for shared utility at top of jcef-bridge.ts**

At the top of `jcef-bridge.ts`, after the existing imports, add:

```typescript
import { updateChartById } from '../components/rich/chartUtils';
```

- [ ] **Step 2: Replace the duplicate `updateChart` bridge function**

Old code (lines 199-213):
```typescript
  updateChart(id: string, dataJson: string) {
    const registry = (window as any).__chartRegistry as Map<string, any> | undefined;
    const chart = registry?.get(id);
    if (chart && chart.canvas?.isConnected) {
      try {
        const update = JSON.parse(dataJson);
        if (update.data) {
          Object.assign(chart.data, update.data);
        }
        if (update.options) {
          Object.assign(chart.options, update.options);
        }
        chart.update('active');
      } catch { /* ignore malformed JSON */ }
    }
  },
```

New code:
```typescript
  updateChart(id: string, dataJson: string) {
    updateChartById(id, dataJson);
  },
```

- [ ] **Step 3: Run all webview tests for regression check**

Run: `cd agent/webview && npx vitest run`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add agent/webview/src/bridge/jcef-bridge.ts
git commit -m "fix(chart): eliminate duplicate updateChart in bridge — delegate to shared chartUtils"
```

---

### Task 5: Add console.warn to remaining silent catch blocks

**Files:**
- Modify: `agent/webview/src/stores/chatStore.ts:1008-1022`
- Modify: `agent/webview/src/bridge/jcef-bridge.ts:282-307`

This task fixes the remaining Issue #6 instances — silent error swallowing in `addArtifact` (chatStore) and `addDebugLogEntry`, `updateCheckpoints`, `notifyRollback` (jcef-bridge).

- [ ] **Step 1: Add console.warn to addArtifact catch block in chatStore.ts**

Old code (lines 1008-1022):
```typescript
  addArtifact(payload: string) {
    try {
      const { title, source } = JSON.parse(payload);
      const msgId = nextId('artifact');
      set((state) => ({
        messages: [...state.messages, {
          id: msgId,
          role: 'system' as MessageRole,
          content: `artifact:${title}`,
          timestamp: Date.now(),
          artifact: { title, source },
        }],
      }));
    } catch { /* malformed payload, skip */ }
  },
```

New code:
```typescript
  addArtifact(payload: string) {
    try {
      const { title, source } = JSON.parse(payload);
      const msgId = nextId('artifact');
      set((state) => ({
        messages: [...state.messages, {
          id: msgId,
          role: 'system' as MessageRole,
          content: `artifact:${title}`,
          timestamp: Date.now(),
          artifact: { title, source },
        }],
      }));
    } catch (e) {
      console.warn('[chatStore] addArtifact: malformed payload', e);
    }
  },
```

- [ ] **Step 2: Add console.warn to three silent catches in jcef-bridge.ts**

In `jcef-bridge.ts`, replace the three silent catches at lines 282-307:

Old code (line 282-287):
```typescript
  addDebugLogEntry(entryJson: string) {
    try {
      const entry = JSON.parse(entryJson);
      stores?.getChatStore().addDebugLogEntry(entry);
    } catch { /* ignore malformed JSON */ }
  },
```

New code:
```typescript
  addDebugLogEntry(entryJson: string) {
    try {
      const entry = JSON.parse(entryJson);
      stores?.getChatStore().addDebugLogEntry(entry);
    } catch (e) {
      console.warn('[bridge] addDebugLogEntry: malformed JSON', e);
    }
  },
```

Old code (lines 296-301):
```typescript
  updateCheckpoints(json: string) {
    try {
      const checkpoints = JSON.parse(json);
      stores?.getChatStore().updateCheckpoints(checkpoints);
    } catch { /* ignore malformed JSON */ }
  },
```

New code:
```typescript
  updateCheckpoints(json: string) {
    try {
      const checkpoints = JSON.parse(json);
      stores?.getChatStore().updateCheckpoints(checkpoints);
    } catch (e) {
      console.warn('[bridge] updateCheckpoints: malformed JSON', e);
    }
  },
```

Old code (lines 302-307):
```typescript
  notifyRollback(json: string) {
    try {
      const rollback = JSON.parse(json);
      stores?.getChatStore().applyRollback(rollback);
    } catch { /* ignore malformed JSON */ }
  },
```

New code:
```typescript
  notifyRollback(json: string) {
    try {
      const rollback = JSON.parse(json);
      stores?.getChatStore().applyRollback(rollback);
    } catch (e) {
      console.warn('[bridge] notifyRollback: malformed JSON', e);
    }
  },
```

- [ ] **Step 3: Run all webview tests for regression check**

Run: `cd agent/webview && npx vitest run`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add agent/webview/src/stores/chatStore.ts agent/webview/src/bridge/jcef-bridge.ts
git commit -m "fix(chart): add console.warn to silent catch blocks for debuggability"
```

---

### Task 6: Final integration verification

**Files:** None (verification only)

- [ ] **Step 1: Run full webview test suite**

Run: `cd agent/webview && npx vitest run`
Expected: All tests PASS (including the 2 new test files from Tasks 1 and 3)

- [ ] **Step 2: Verify no TypeScript errors**

Run: `cd agent/webview && npx tsc --noEmit`
Expected: No type errors

- [ ] **Step 3: Verify the webview builds cleanly**

Run: `cd agent/webview && npm run build`
Expected: Build succeeds with no errors

- [ ] **Step 4: Verify full plugin builds**

Run: `./gradlew buildPlugin`
Expected: BUILD SUCCESSFUL
