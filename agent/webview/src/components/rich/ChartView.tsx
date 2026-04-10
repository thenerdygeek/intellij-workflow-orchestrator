import { useEffect, useRef, useState, useCallback } from 'react';
import { RichBlock } from './RichBlock';
import { useThemeStore } from '@/stores/themeStore';
import { chartRegistry, updateChartById, removeChartFromRegistry, deepMergeChartConfig } from './chartUtils';

// Global function callable from Kotlin via JCEF — delegates to shared utility
(window as any).updateChart = (id: string, json: string) => {
  updateChartById(id, json);
};

// ── Singleton lazy-load for Chart.js ──

type ChartModule = typeof import('chart.js');

let chartModulePromise: Promise<ChartModule> | null = null;
let chartResolved: ChartModule | null = null;
let chartRegistered = false;

/** Race dynamic import against a timeout — JCEF's custom scheme can hang on chunk loads. */
function withTimeout<T>(promise: Promise<T>, ms: number, label: string): Promise<T> {
  return Promise.race([
    promise,
    new Promise<T>((_, reject) =>
      setTimeout(() => reject(new Error(`${label} timed out after ${ms}ms`)), ms)
    ),
  ]);
}

function loadChartJs(): Promise<ChartModule> {
  if (!chartModulePromise) {
    chartModulePromise = withTimeout(import('chart.js'), 8000, 'chart.js import')
      .then((m) => {
        if (!chartRegistered) {
          m.Chart.register(
            m.CategoryScale,
            m.LinearScale,
            m.LogarithmicScale,
            m.TimeScale,
            m.RadialLinearScale,
            m.BarController,
            m.LineController,
            m.PieController,
            m.DoughnutController,
            m.RadarController,
            m.PolarAreaController,
            m.ScatterController,
            m.BubbleController,
            m.BarElement,
            m.LineElement,
            m.PointElement,
            m.ArcElement,
            m.Filler,
            m.Legend,
            m.Title,
            m.Tooltip,
          );
          chartRegistered = true;
        }
        chartResolved = m;
        return m;
      })
      .catch((err) => {
        // Reset so next attempt retries
        chartModulePromise = null;
        throw err;
      });
  }
  return chartModulePromise;
}

// ── ChartView component ──

interface ChartViewProps {
  source: string;
}

export function ChartView({ source }: ChartViewProps) {
  const isDark = useThemeStore((s) => s.isDark);
  const cssVariables = useThemeStore((s) => s.cssVariables);
  const [isReady, setIsReady] = useState(() => chartResolved !== null);
  const [error, setError] = useState<Error | null>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const chartRef = useRef<InstanceType<ChartModule['Chart']> | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const renderIdRef = useRef(0);

  const getThemeColors = useCallback(() => {
    const fg = cssVariables['fg'] ?? (isDark ? '#cccccc' : '#333333');
    const fgMuted = cssVariables['fg-muted'] ?? (isDark ? '#888888' : '#999999');
    const border = cssVariables['border'] ?? (isDark ? '#444444' : '#dddddd');
    const gridColor = isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.1)';
    return { fg, fgMuted, border, gridColor };
  }, [isDark, cssVariables]);

  const renderChart = useCallback(async () => {
    const currentRender = ++renderIdRef.current;
    setError(null);

    try {
      const m = await loadChartJs();

      if (currentRender !== renderIdRef.current) return;

      const canvas = canvasRef.current;
      if (!canvas) return;

      // Destroy previous chart instance — clean registry BEFORE nulling ref
      if (chartRef.current) {
        removeChartFromRegistry(chartRef.current);
        chartRef.current.destroy();
        chartRef.current = null;
      }

      // Parse the chart config from source
      let config: Record<string, unknown>;
      try {
        config = JSON.parse(source) as Record<string, unknown>;
      } catch {
        throw new Error('Invalid chart JSON configuration');
      }

      const chartId = config.id as string | undefined;

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

      // Apply theme-aware defaults
      const { fg, fgMuted, gridColor } = getThemeColors();

      m.Chart.defaults.color = fgMuted;
      m.Chart.defaults.borderColor = gridColor;

      // Build theme defaults, then deep-merge user config on top
      const userOptions = (config.options ?? {}) as Record<string, unknown>;
      const userPlugins = (userOptions.plugins ?? {}) as Record<string, unknown>;
      const userLegend = (userPlugins.legend ?? {}) as Record<string, unknown>;
      const userLegendLabels = (userLegend.labels ?? {}) as Record<string, unknown>;
      const userTitle = (userPlugins.title ?? {}) as Record<string, unknown>;

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

      chartRef.current = new m.Chart(canvas, chartConfig as ConstructorParameters<typeof m.Chart>[1]);

      // Register chart by ID for incremental updates
      if (chartId) {
        chartRegistry.set(chartId, chartRef.current);
      }

      setIsReady(true);
    } catch (err) {
      if (currentRender !== renderIdRef.current) return;
      setError(err instanceof Error ? err : new Error(String(err)));
      setIsReady(true); // Show error state, not skeleton
    }
  }, [source, isDark, getThemeColors]);

  useEffect(() => {
    void renderChart();

    return () => {
      if (chartRef.current) {
        removeChartFromRegistry(chartRef.current);
        chartRef.current.destroy();
        chartRef.current = null;
      }
    };
  }, [renderChart]);

  // ResizeObserver for responsive resize
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const observer = new ResizeObserver(() => {
      if (chartRef.current) {
        chartRef.current.resize();
      }
    });

    observer.observe(container);
    return () => observer.disconnect();
  }, []);

  // Parse chart type and title for the placeholder
  const chartMeta = (() => {
    try {
      const parsed = JSON.parse(source) as Record<string, unknown>;
      return {
        type: (parsed.type as string) ?? 'chart',
        title: ((parsed.options as any)?.plugins?.title?.text as string) ?? null,
      };
    } catch (e) {
      console.warn('[chart] Failed to parse chart meta from source', e);
      return { type: 'chart', title: null };
    }
  })();

  return (
    <RichBlock
      type="chart"
      source={source}
      isLoading={false}
      error={error}
      onRetry={() => void renderChart()}
    >
      <div ref={containerRef} className="relative p-4" style={{ minHeight: 200 }}>
        {/* Placeholder shown OVER the canvas while Chart.js loads — canvas must stay in DOM */}
        {!isReady && !error && (
          <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 z-10" style={{ color: 'var(--fg-muted)' }}>
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" opacity="0.5">
              <rect x="3" y="12" width="4" height="9" rx="1" />
              <rect x="10" y="7" width="4" height="14" rx="1" />
              <rect x="17" y="3" width="4" height="18" rx="1" />
            </svg>
            <span className="text-xs">
              Loading {chartMeta.type} chart{chartMeta.title ? `: ${chartMeta.title}` : ''}...
            </span>
          </div>
        )}
        {/* Canvas ALWAYS in DOM so Chart.js can render to it — just visually transparent until ready */}
        <canvas ref={canvasRef} style={{ opacity: isReady ? 1 : 0, transition: 'opacity 0.3s ease' }} />
      </div>
    </RichBlock>
  );
}

// ── Helpers ──

function applyScaleColors(
  scales: Record<string, unknown> | undefined,
  tickColor: string,
  gridColor: string,
): Record<string, unknown> {
  if (!scales) {
    return {
      x: { ticks: { color: tickColor }, grid: { color: gridColor } },
      y: { ticks: { color: tickColor }, grid: { color: gridColor } },
    };
  }

  const result: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(scales)) {
    const scaleConfig = (value ?? {}) as Record<string, unknown>;
    result[key] = {
      ...scaleConfig,
      ticks: { color: tickColor, ...(scaleConfig.ticks as Record<string, unknown> ?? {}) },
      grid: { color: gridColor, ...(scaleConfig.grid as Record<string, unknown> ?? {}) },
    };
  }
  return result;
}
