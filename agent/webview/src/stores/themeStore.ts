import { create } from 'zustand';
import { applyThemeVariables, detectIsDark } from '../bridge/theme-controller';
import type { ThemeVars } from '../bridge/types';

interface ThemeState {
  cssVariables: Record<string, string>;
  isDark: boolean;
  applyTheme(cssVarsJson: string | Record<string, string>): void;
  setIsDark(isDark: boolean): void;
  getVar(name: keyof ThemeVars): string;
}

export const useThemeStore = create<ThemeState>((set, get) => ({
  cssVariables: {},
  isDark: true,

  applyTheme(cssVarsJson: string | Record<string, string>) {
    const vars = applyThemeVariables(cssVarsJson);
    const isDark = vars['bg'] ? detectIsDark(vars['bg']) : get().isDark;
    set({ cssVariables: vars, isDark });
  },

  setIsDark(isDark: boolean) {
    set({ isDark });
  },

  getVar(name: keyof ThemeVars): string {
    return get().cssVariables[name as string] ?? '';
  },
}));
