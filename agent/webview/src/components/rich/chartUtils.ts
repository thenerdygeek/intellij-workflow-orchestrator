// ── Chart registry (singleton, shared across ChartView and bridge) ──

export const chartRegistry = new Map<string, any>();
if (typeof window !== 'undefined') {
  (window as any).__chartRegistry = chartRegistry;
}

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
