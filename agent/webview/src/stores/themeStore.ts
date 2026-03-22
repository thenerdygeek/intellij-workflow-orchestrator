import { create } from 'zustand';

// Minimal stub store — real implementation in Task 4

interface ThemeStoreStub {
  isDark: boolean;
  applyTheme: (json: string) => void;
  setIsDark: (isDark: boolean) => void;
}

export const useThemeStore = create<ThemeStoreStub>()((set) => ({
  isDark: true,
  applyTheme: (json: string) => {
    try {
      const vars = JSON.parse(json) as Record<string, string>;
      const root = document.documentElement;
      for (const [key, value] of Object.entries(vars)) {
        root.style.setProperty(`--${key}`, value);
      }
    } catch {
      // ignore parse errors in stub
    }
  },
  setIsDark: (isDark: boolean) => set({ isDark }),
}));
