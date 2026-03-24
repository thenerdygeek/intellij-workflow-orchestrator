import type { ThemeVars } from '../bridge/types';

export const darkThemeDefaults: ThemeVars = {
  // Neutral dark — no purple tint. bg is the darkest surface, input/cards are elevated above it.
  'bg': '#131315',
  'fg': '#e2e4e9',
  'fg-secondary': '#9ba1ab',
  'fg-muted': '#64666e',
  'border': '#2a2b2f',

  'user-bg': '#1c1e24',
  'tool-bg': '#181a1f',
  'code-bg': '#0f1117',
  'thinking-bg': '#1a1c22',

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
  'hover-overlay-strong': 'rgba(255,255,255,0.06)',
  'divider-subtle': 'rgba(255,255,255,0.04)',
  'row-alt': 'rgba(255,255,255,0.02)',
  // Input is elevated ABOVE the page background — lighter, not darker
  'input-bg': '#1e1f23',
  'input-border': 'rgba(255,255,255,0.07)',
  'toolbar-bg': '#1a1b1f',
  'chip-bg': 'rgba(255,255,255,0.04)',
  'chip-border': 'rgba(255,255,255,0.08)',

  'accent': '#60a5fa',
  'running': '#60a5fa',
  'pending': '#64666e',
};

export const lightThemeDefaults: ThemeVars = {
  // Neutral light — bg is the base white, input/cards slightly off-white elevated above it
  'bg': '#f5f5f7',
  'fg': '#111113',
  'fg-secondary': '#4b4d55',
  'fg-muted': '#82848e',
  'border': '#e0e1e6',

  'user-bg': '#eaeaef',
  'tool-bg': '#f0f0f5',
  'code-bg': '#e8e8ed',
  'thinking-bg': '#f0f0f5',

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
  'hover-overlay-strong': 'rgba(0,0,0,0.06)',
  'divider-subtle': 'rgba(0,0,0,0.04)',
  'row-alt': 'rgba(0,0,0,0.02)',
  // Input is elevated ABOVE the page background — brighter white surface
  'input-bg': '#ffffff',
  'input-border': '#d8d9de',
  'toolbar-bg': '#ebebf0',
  'chip-bg': 'rgba(0,0,0,0.04)',
  'chip-border': '#d8d9de',

  'accent': '#2563eb',
  'running': '#2563eb',
  'pending': '#82848e',
};

export const fontDefaults = {
  'font-body': "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif",
  'font-mono': "'JetBrains Mono', Menlo, Consolas, 'Courier New', monospace",
};
