import { useCallback } from 'react';
import { useSettingsStore } from '../../stores/settingsStore';
import type { VisualizationType, VisualizationConfig } from '../../bridge/types';

const TYPE_LABELS: Record<VisualizationType, string> = {
  mermaid: 'Mermaid Diagrams',
  chart: 'Charts (Chart.js)',
  flow: 'Flow Diagrams (dagre)',
  math: 'Math (KaTeX)',
  diff: 'Diff Viewer (diff2html)',
  interactiveHtml: 'Interactive HTML',
};

const VIZ_TYPES: VisualizationType[] = ['mermaid', 'chart', 'flow', 'math', 'diff', 'interactiveHtml'];

interface ToggleProps {
  checked: boolean;
  onChange: (checked: boolean) => void;
  label: string;
  disabled?: boolean;
}

function Toggle({ checked, onChange, label, disabled }: ToggleProps) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={`
        relative inline-flex h-5 w-9 shrink-0 cursor-pointer items-center rounded-full
        transition-colors duration-200 focus-visible:outline-none focus-visible:ring-2
        focus-visible:ring-[var(--link)] focus-visible:ring-offset-1
        ${disabled ? 'opacity-40 cursor-not-allowed' : ''}
        ${checked ? 'bg-[var(--link)]' : 'bg-[var(--fg-muted)]/30'}
      `}
    >
      <span
        className={`
          pointer-events-none inline-block h-3.5 w-3.5 rounded-full bg-white shadow-sm
          transition-transform duration-200
          ${checked ? 'translate-x-[18px]' : 'translate-x-[3px]'}
        `}
      />
    </button>
  );
}

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
      className={`
        rounded-lg border border-[var(--border)] bg-[var(--bg)] p-4
        transition-opacity duration-200
        ${disabled ? 'opacity-50' : ''}
      `}
    >
      {/* Header with enable toggle */}
      <div className="flex items-center justify-between mb-3">
        <span className="text-sm font-medium text-[var(--fg)]">
          {TYPE_LABELS[type]}
        </span>
        <Toggle
          checked={config.enabled}
          onChange={(v) => onUpdate(type, { enabled: v })}
          label={`Enable ${TYPE_LABELS[type]}`}
        />
      </div>

      {/* Settings */}
      <div className={`space-y-3 ${disabled ? 'pointer-events-none' : ''}`}>
        {/* Auto-render */}
        <div className="flex items-center justify-between">
          <span className="text-xs text-[var(--fg-secondary)]">Auto-render</span>
          <Toggle
            checked={config.autoRender}
            onChange={(v) => onUpdate(type, { autoRender: v })}
            label={`Auto-render ${TYPE_LABELS[type]}`}
            disabled={disabled}
          />
        </div>

        {/* Default expanded */}
        <div className="flex items-center justify-between">
          <span className="text-xs text-[var(--fg-secondary)]">Default expanded</span>
          <Toggle
            checked={config.defaultExpanded}
            onChange={(v) => onUpdate(type, { defaultExpanded: v })}
            label={`Default expanded ${TYPE_LABELS[type]}`}
            disabled={disabled}
          />
        </div>

        {/* Max height */}
        <div className="flex items-center justify-between">
          <span className="text-xs text-[var(--fg-secondary)]">Max height (px)</span>
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
            className={`
              w-20 rounded-md border border-[var(--input-border)] bg-[var(--input-bg)]
              px-2 py-1 text-xs text-[var(--fg)] text-right
              focus:outline-none focus:ring-1 focus:ring-[var(--link)]
              ${disabled ? 'opacity-50 cursor-not-allowed' : ''}
            `}
            aria-label={`Max height for ${TYPE_LABELS[type]}`}
          />
        </div>

        {/* Reset button */}
        <div className="flex justify-end pt-1">
          <button
            onClick={() => onReset(type)}
            disabled={disabled}
            className={`
              text-xs text-[var(--link)] hover:underline
              ${disabled ? 'opacity-50 cursor-not-allowed' : ''}
            `}
          >
            Reset to defaults
          </button>
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
      {/* Header */}
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-[var(--fg)]">Visualization Settings</h3>
        <button
          onClick={resetAll}
          className="text-xs text-[var(--link)] hover:underline"
        >
          Reset all to defaults
        </button>
      </div>

      {/* Per-type settings */}
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
