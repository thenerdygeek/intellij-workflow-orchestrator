import { create } from 'zustand';
import type { VisualizationType, VisualizationConfig } from '../bridge/types';

const defaultVisualizationConfig: VisualizationConfig = {
  enabled: true,
  autoRender: true,
  defaultExpanded: false,
  maxHeight: 300,
};

const defaultVisualizations: Record<VisualizationType, VisualizationConfig> = {
  mermaid: { ...defaultVisualizationConfig },
  chart: { ...defaultVisualizationConfig },
  flow: { ...defaultVisualizationConfig },
  math: { ...defaultVisualizationConfig, defaultExpanded: true, maxHeight: 0 },
  diff: { ...defaultVisualizationConfig, defaultExpanded: true, maxHeight: 400 },
  interactiveHtml: { ...defaultVisualizationConfig, maxHeight: 500 },
  table: { enabled: true, autoRender: true, defaultExpanded: true, maxHeight: 400 },
  output: { enabled: true, autoRender: true, defaultExpanded: true, maxHeight: 400 },
  progress: { enabled: true, autoRender: true, defaultExpanded: true, maxHeight: 300 },
  timeline: { enabled: true, autoRender: true, defaultExpanded: true, maxHeight: 400 },
  image: { enabled: true, autoRender: true, defaultExpanded: true, maxHeight: 500 },
  artifact: { enabled: true, autoRender: true, defaultExpanded: false, maxHeight: 400 },
};

interface SettingsState {
  visualizations: Record<VisualizationType, VisualizationConfig>;
  updateVisualization(type: VisualizationType, config: Partial<VisualizationConfig>): void;
  resetVisualization(type: VisualizationType): void;
  resetAll(): void;
}

export const useSettingsStore = create<SettingsState>((set) => ({
  visualizations: { ...defaultVisualizations },

  updateVisualization(type: VisualizationType, config: Partial<VisualizationConfig>) {
    set(state => ({
      visualizations: {
        ...state.visualizations,
        [type]: { ...state.visualizations[type], ...config },
      },
    }));
  },

  resetVisualization(type: VisualizationType) {
    set(state => ({
      visualizations: {
        ...state.visualizations,
        [type]: { ...defaultVisualizationConfig },
      },
    }));
  },

  resetAll() {
    set({ visualizations: { ...defaultVisualizations } });
  },
}));
