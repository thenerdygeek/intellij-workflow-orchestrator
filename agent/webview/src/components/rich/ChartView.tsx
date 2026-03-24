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

// ── Singleton lazy-load for Chart.js ──

type ChartModule = typeof import('chart.js');

let chartModulePromise: Promise<ChartModule> | null = null;
let chartRegistered = false;

function loadChartJs(): Promise<ChartModule> {
  if (!chartModulePromise) {
    chartModulePromise = import('chart.js').then((m) => {
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
      return m;
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
  const [isLoading, setIsLoading] = useState(true);
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
    setIsLoading(true);
    setError(null);

    try {
      const m = await loadChartJs();

      if (currentRender !== renderIdRef.current) return;

      const canvas = canvasRef.current;
      if (!canvas) return;

      // Destroy previous chart instance
      if (chartRef.current) {
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
          // Merge new data into existing chart
          if (config.data) {
            Object.assign(existing.data, config.data);
          }
          if (config.options) {
            Object.assign(existing.options, config.options);
          }
          existing.update('active');
          setIsLoading(false);
          return;
        }
        // Canvas is stale — remove from registry, fall through to create new
        chartRegistry.delete(chartId);
      }

      // Apply theme-aware defaults
      const { fg, fgMuted, gridColor } = getThemeColors();

      m.Chart.defaults.color = fgMuted;
      m.Chart.defaults.borderColor = gridColor;

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

      // If previous chart had an ID, remove it from registry before creating new
      if (chartRef.current) {
        for (const [key, val] of chartRegistry) {
          if (val === chartRef.current) {
            chartRegistry.delete(key);
            break;
          }
        }
      }

      chartRef.current = new m.Chart(canvas, chartConfig as ConstructorParameters<typeof m.Chart>[1]);

      // Register chart by ID for incremental updates
      if (chartId) {
        chartRegistry.set(chartId, chartRef.current);
      }

      setIsLoading(false);
    } catch (err) {
      if (currentRender !== renderIdRef.current) return;
      setError(err instanceof Error ? err : new Error(String(err)));
      setIsLoading(false);
    }
  }, [source, isDark, getThemeColors]);

  useEffect(() => {
    void renderChart();

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

  return (
    <RichBlock
      type="chart"
      source={source}
      isLoading={isLoading}
      error={error}
      onRetry={() => void renderChart()}
    >
      <div ref={containerRef} className="p-4" style={{ minHeight: 200 }}>
        <canvas ref={canvasRef} />
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
