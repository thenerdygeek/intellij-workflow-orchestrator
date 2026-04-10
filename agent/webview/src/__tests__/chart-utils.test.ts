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
