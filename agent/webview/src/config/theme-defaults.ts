import type { ThemeVars } from '../bridge/types';

export const darkThemeDefaults: ThemeVars = {
  // VS Code Dark+ inspired — warm neutrals, blue accent
  'bg': '#1e1e1e',
  'fg': '#d4d4d4',
  'fg-secondary': '#9d9d9d',
  'fg-muted': '#6a6a6a',
  'border': '#3c3c3c',

  'user-bg': '#2d2d30',
  'tool-bg': '#252526',
  'code-bg': '#1e1e1e',
  'thinking-bg': '#252526',

  'badge-read-bg': '#1a3a5c',
  'badge-read-fg': '#569cd6',
  'badge-write-bg': '#1e3a1e',
  'badge-write-fg': '#6a9955',
  'badge-edit-bg': '#3d3017',
  'badge-edit-fg': '#dcdcaa',
  'badge-cmd-bg': '#3d1717',
  'badge-cmd-fg': '#f44747',
  'badge-search-bg': '#17333d',
  'badge-search-fg': '#4ec9b0',

  'accent-read': '#569cd6',
  'accent-write': '#6a9955',
  'accent-edit': '#dcdcaa',
  'accent-cmd': '#f44747',
  'accent-search': '#4ec9b0',

  'diff-add-bg': '#1e3a1e',
  'diff-add-fg': '#b5cea8',
  'diff-rem-bg': '#3d1717',
  'diff-rem-fg': '#f4a5a5',

  'success': '#6a9955',
  'error': '#f44747',
  'warning': '#dcdcaa',
  'link': '#569cd6',

  'hover-overlay': 'rgba(255,255,255,0.04)',
  'hover-overlay-strong': 'rgba(255,255,255,0.07)',
  'divider-subtle': 'rgba(255,255,255,0.05)',
  'row-alt': 'rgba(255,255,255,0.02)',
  // Input is elevated ABOVE the page background — lighter, not darker
  'input-bg': '#3c3c3c',
  'input-border': 'rgba(255,255,255,0.08)',
  'toolbar-bg': '#252526',
  'chip-bg': 'rgba(255,255,255,0.04)',
  'chip-border': 'rgba(255,255,255,0.08)',

  'accent': '#569cd6',
  'running': '#569cd6',
  'pending': '#6a6a6a',
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
