import { darkThemeDefaults, lightThemeDefaults, fontDefaults } from '@/config/theme-defaults';
import type { ThemeVars } from '@/bridge/types';

export function applyShowcaseTheme(dark: boolean) {
  const vars: ThemeVars = dark ? darkThemeDefaults : lightThemeDefaults;
  const root = document.documentElement;

  for (const [key, value] of Object.entries(vars)) {
    if (value != null) root.style.setProperty(`--${key}`, value);
  }
  for (const [key, value] of Object.entries(fontDefaults)) {
    root.style.setProperty(`--${key}`, value);
  }

  localStorage.setItem('showcase-theme', dark ? 'dark' : 'light');
}

export function getStoredTheme(): boolean {
  return localStorage.getItem('showcase-theme') !== 'light';
}
