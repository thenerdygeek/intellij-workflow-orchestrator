import { useThemeStore } from '../stores/themeStore';

export function useTheme() {
  const isDark = useThemeStore(s => s.isDark);
  const getVar = useThemeStore(s => s.getVar);
  const cssVariables = useThemeStore(s => s.cssVariables);

  return { isDark, getVar, cssVariables };
}
