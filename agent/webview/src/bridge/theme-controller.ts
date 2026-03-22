/**
 * Theme controller — receives theme CSS variables from Kotlin's
 * AgentCefPanel.applyCurrentTheme() and applies them to the document root.
 *
 * Variable names arrive WITHOUT the '--' prefix (e.g., 'bg', 'fg-secondary').
 * This controller adds the '--' prefix when setting CSS custom properties.
 */

export function applyThemeVariables(cssVarsJson: string | Record<string, string>): Record<string, string> {
  let vars: Record<string, string>;

  if (typeof cssVarsJson === 'string') {
    try {
      vars = JSON.parse(cssVarsJson);
    } catch {
      console.error('[theme] Failed to parse theme JSON:', cssVarsJson);
      return {};
    }
  } else {
    vars = cssVarsJson;
  }

  const root = document.documentElement;
  for (const [key, value] of Object.entries(vars)) {
    root.style.setProperty(`--${key}`, value);
  }

  return vars;
}

export function getCssVariable(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(`--${name}`).trim();
}

export function detectIsDark(bgColor: string): boolean {
  const hex = bgColor.replace('#', '');
  if (hex.length !== 6) return true;

  const r = parseInt(hex.substring(0, 2), 16) / 255;
  const g = parseInt(hex.substring(2, 4), 16) / 255;
  const b = parseInt(hex.substring(4, 6), 16) / 255;

  const luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
  return luminance < 0.5;
}
