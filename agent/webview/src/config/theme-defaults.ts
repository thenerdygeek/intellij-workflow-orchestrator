import type { ThemeVars } from '../bridge/types';

export const darkThemeDefaults: ThemeVars = {
  'bg': '#2b2d30',
  'fg': '#cbd5e1',
  'fg-secondary': '#94a3b8',
  'fg-muted': '#6b7280',
  'border': '#3f3f46',

  'user-bg': '#1e293b',
  'tool-bg': '#1a1d23',
  'code-bg': '#1e1e2e',
  'thinking-bg': '#1f2937',

  'badge-read-bg': '#1e3a5f',
  'badge-read-fg': '#60a5fa',
  'badge-write-bg': '#14532d',
  'badge-write-fg': '#4ade80',
  'badge-edit-bg': '#451a03',
  'badge-edit-fg': '#fbbf24',
  'badge-cmd-bg': '#450a0a',
  'badge-cmd-fg': '#f87171',
  'badge-search-bg': '#083344',
  'badge-search-fg': '#22d3ee',

  'accent-read': '#3b82f6',
  'accent-write': '#22c55e',
  'accent-edit': '#f59e0b',
  'accent-cmd': '#ef4444',
  'accent-search': '#06b6d4',

  'diff-add-bg': '#14332a',
  'diff-add-fg': '#86efac',
  'diff-rem-bg': '#3b1818',
  'diff-rem-fg': '#fca5a5',

  'success': '#22c55e',
  'error': '#ef4444',
  'warning': '#f59e0b',
  'link': '#60a5fa',

  'hover-overlay': 'rgba(255,255,255,0.03)',
  'hover-overlay-strong': 'rgba(255,255,255,0.05)',
  'divider-subtle': 'rgba(255,255,255,0.05)',
  'row-alt': 'rgba(255,255,255,0.02)',
  'input-bg': '#1a1c22',
  'input-border': 'rgba(255,255,255,0.08)',
  'toolbar-bg': '#1e2028',
  'chip-bg': 'rgba(255,255,255,0.03)',
  'chip-border': 'rgba(255,255,255,0.07)',

  'accent': '#60a5fa',
  'running': '#60a5fa',
  'pending': '#6b7280',
};

export const lightThemeDefaults: ThemeVars = {
  'bg': '#ffffff',
  'fg': '#1e293b',
  'fg-secondary': '#475569',
  'fg-muted': '#64748b',
  'border': '#e2e8f0',

  'user-bg': '#f1f5f9',
  'tool-bg': '#f8fafc',
  'code-bg': '#f1f5f9',
  'thinking-bg': '#f9fafb',

  'badge-read-bg': '#dbeafe',
  'badge-read-fg': '#2563eb',
  'badge-write-bg': '#dcfce7',
  'badge-write-fg': '#16a34a',
  'badge-edit-bg': '#fef3c7',
  'badge-edit-fg': '#d97706',
  'badge-cmd-bg': '#fee2e2',
  'badge-cmd-fg': '#dc2626',
  'badge-search-bg': '#cffafe',
  'badge-search-fg': '#0891b2',

  'accent-read': '#3b82f6',
  'accent-write': '#22c55e',
  'accent-edit': '#f59e0b',
  'accent-cmd': '#ef4444',
  'accent-search': '#06b6d4',

  'diff-add-bg': '#dcfce7',
  'diff-add-fg': '#166534',
  'diff-rem-bg': '#fee2e2',
  'diff-rem-fg': '#991b1b',

  'success': '#16a34a',
  'error': '#dc2626',
  'warning': '#d97706',
  'link': '#2563eb',

  'hover-overlay': 'rgba(0,0,0,0.03)',
  'hover-overlay-strong': 'rgba(0,0,0,0.05)',
  'divider-subtle': 'rgba(0,0,0,0.05)',
  'row-alt': 'rgba(0,0,0,0.02)',
  'input-bg': '#ffffff',
  'input-border': '#e2e8f0',
  'toolbar-bg': '#f8fafc',
  'chip-bg': 'rgba(0,0,0,0.03)',
  'chip-border': '#e2e8f0',

  'accent': '#2563eb',
  'running': '#2563eb',
  'pending': '#64748b',
};

export const fontDefaults = {
  'font-body': "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif",
  'font-mono': "'JetBrains Mono', Menlo, Consolas, 'Courier New', monospace",
};
