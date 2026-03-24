import { useCallback } from 'react';
import { useSettingsStore } from '../../stores/settingsStore';
import type { VisualizationType, VisualizationConfig } from '../../bridge/types';
import { Switch } from '@/components/ui/switch';
import { Button } from '@/components/ui/button';

const TYPE_LABELS: Record<VisualizationType, string> = {
  mermaid: 'Mermaid Diagrams',
  chart: 'Charts (Chart.js)',
  flow: 'Flow Diagrams (dagre)',
  math: 'Math (KaTeX)',
  diff: 'Diff Viewer (diff2html)',
  interactiveHtml: 'Interactive HTML',
};

const VIZ_TYPES: VisualizationType[] = ['mermaid', 'chart', 'flow', 'math', 'diff', 'interactiveHtml'];

interface TypeSectionProps {
  type: VisualizationType;
  config: VisualizationConfig;
  onUpdate: (type: VisualizationType, config: Partial<VisualizationConfig>) => void;
  onReset: (type: VisualizationType) => void;
}

function TypeSection({ type, config, onUpdate, onReset }: TypeSectionProps) {
  const disabled = !config.enabled;

  return (
    <div
      className={`rounded-lg border p-4 transition-opacity duration-200 ${disabled ? 'opacity-50' : ''}`}
      style={{ borderColor: 'var(--border)', backgroundColor: 'var(--bg)' }}
    >
      {/* Header with enable toggle */}
      <div className="flex items-center justify-between mb-3">
        <span className="text-sm font-medium" style={{ color: 'var(--fg)' }}>
          {TYPE_LABELS[type]}
        </span>
        <Switch
          checked={config.enabled}
          onCheckedChange={(v) => onUpdate(type, { enabled: v })}
          aria-label={`Enable ${TYPE_LABELS[type]}`}
        />
      </div>

      {/* Settings */}
      <div className={`space-y-3 ${disabled ? 'pointer-events-none' : ''}`}>
        <div className="flex items-center justify-between">
          <span className="text-xs" style={{ color: 'var(--fg-secondary)' }}>Auto-render</span>
          <Switch
            checked={config.autoRender}
            onCheckedChange={(v) => onUpdate(type, { autoRender: v })}
            aria-label={`Auto-render ${TYPE_LABELS[type]}`}
            disabled={disabled}
          />
        </div>

        <div className="flex items-center justify-between">
          <span className="text-xs" style={{ color: 'var(--fg-secondary)' }}>Default expanded</span>
          <Switch
            checked={config.defaultExpanded}
            onCheckedChange={(v) => onUpdate(type, { defaultExpanded: v })}
            aria-label={`Default expanded ${TYPE_LABELS[type]}`}
            disabled={disabled}
          />
        </div>

        <div className="flex items-center justify-between">
          <span className="text-xs" style={{ color: 'var(--fg-secondary)' }}>Max height (px)</span>
          <input
            type="number"
            min={0}
            max={2000}
            step={50}
            value={config.maxHeight}
            onChange={(e) => {
              const val = Math.max(0, Math.min(2000, Number(e.target.value) || 0));
              onUpdate(type, { maxHeight: val });
            }}
            disabled={disabled}
            className={`w-20 rounded-md border px-2 py-1 text-xs text-right focus:outline-none focus:ring-1 ${disabled ? 'opacity-50 cursor-not-allowed' : ''}`}
            style={{
              borderColor: 'var(--input-border)',
              backgroundColor: 'var(--input-bg)',
              color: 'var(--fg)',
            }}
            aria-label={`Max height for ${TYPE_LABELS[type]}`}
          />
        </div>

        <div className="flex justify-end pt-1">
          <Button
            variant="link"
            size="sm"
            className="text-xs h-auto p-0"
            onClick={() => onReset(type)}
            disabled={disabled}
          >
            Reset to defaults
          </Button>
        </div>
      </div>
    </div>
  );
}

export function VisualizationSettings() {
  const visualizations = useSettingsStore((s) => s.visualizations);
  const updateVisualization = useSettingsStore((s) => s.updateVisualization);
  const resetVisualization = useSettingsStore((s) => s.resetVisualization);
  const resetAll = useSettingsStore((s) => s.resetAll);

  const handleUpdate = useCallback(
    (type: VisualizationType, config: Partial<VisualizationConfig>) => {
      updateVisualization(type, config);
    },
    [updateVisualization],
  );

  const handleReset = useCallback(
    (type: VisualizationType) => {
      resetVisualization(type);
    },
    [resetVisualization],
  );

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold" style={{ color: 'var(--fg)' }}>Visualization Settings</h3>
        <Button variant="link" size="sm" className="text-xs h-auto p-0" onClick={resetAll}>
          Reset all to defaults
        </Button>
      </div>

      <div className="grid gap-3">
        {VIZ_TYPES.map((type) => (
          <TypeSection
            key={type}
            type={type}
            config={visualizations[type]}
            onUpdate={handleUpdate}
            onReset={handleReset}
          />
        ))}
      </div>
    </div>
  );
}
