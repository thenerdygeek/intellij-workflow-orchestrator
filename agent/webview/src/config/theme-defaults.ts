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
  // VS Code Light+ inspired — clean white, blue accent
  'bg': '#ffffff',
  'fg': '#1e1e1e',
  'fg-secondary': '#616161',
  'fg-muted': '#9e9e9e',
  'border': '#e0e0e0',

  'user-bg': '#f3f3f3',
  'tool-bg': '#f8f8f8',
  'code-bg': '#f3f3f3',
  'thinking-bg': '#f8f8f8',

  'badge-read-bg': '#d6ecff',
  'badge-read-fg': '#0451a5',
  'badge-write-bg': '#d4edda',
  'badge-write-fg': '#1b7742',
  'badge-edit-bg': '#fff3cd',
  'badge-edit-fg': '#795e00',
  'badge-cmd-bg': '#fde2e2',
  'badge-cmd-fg': '#cd3131',
  'badge-search-bg': '#d4f4f4',
  'badge-search-fg': '#16825d',

  'accent-read': '#0451a5',
  'accent-write': '#1b7742',
  'accent-edit': '#795e00',
  'accent-cmd': '#cd3131',
  'accent-search': '#16825d',

  'diff-add-bg': '#d4edda',
  'diff-add-fg': '#1b7742',
  'diff-rem-bg': '#fde2e2',
  'diff-rem-fg': '#cd3131',

  'success': '#1b7742',
  'error': '#cd3131',
  'warning': '#795e00',
  'link': '#0451a5',

  'hover-overlay': 'rgba(0,0,0,0.04)',
  'hover-overlay-strong': 'rgba(0,0,0,0.07)',
  'divider-subtle': 'rgba(0,0,0,0.05)',
  'row-alt': 'rgba(0,0,0,0.02)',
  // Input is elevated ABOVE the page background — brighter white surface
  'input-bg': '#ffffff',
  'input-border': '#e0e0e0',
  'toolbar-bg': '#f3f3f3',
  'chip-bg': 'rgba(0,0,0,0.04)',
  'chip-border': '#e0e0e0',

  'accent': '#0451a5',
  'running': '#0451a5',
  'pending': '#9e9e9e',
};

export const fontDefaults = {
  'font-body': "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif",
  'font-mono': "'JetBrains Mono', Menlo, Consolas, 'Courier New', monospace",
};
