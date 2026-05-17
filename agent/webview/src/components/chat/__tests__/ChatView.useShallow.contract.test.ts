import { describe, it, expect } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';

describe('ChatView selectActiveSubAgents subscription is shallow', () => {
  it('uses useShallow (or custom equality fn) when reading selectActiveSubAgents', () => {
    const src = fs.readFileSync(
      path.resolve(__dirname, '../ChatView.tsx'),
      'utf8',
    );
    // The subscription must be wrapped in useShallow OR have a custom equality fn second arg.
    const hasUseShallow = /useChatStore\s*\(\s*useShallow\s*\(\s*selectActiveSubAgents\s*\)/.test(src);
    const hasEqFn = /useChatStore\s*\(\s*selectActiveSubAgents\s*,\s*[^)]+\)/.test(src);
    expect(hasUseShallow || hasEqFn).toBe(true);
    // Also assert useShallow is imported.
    const hasShallowImport = /import\s+\{[^}]*useShallow[^}]*\}\s+from\s+['"]zustand\/react\/shallow['"]/.test(src);
    if (hasUseShallow) {
      expect(hasShallowImport).toBe(true);
    }
  });
});
