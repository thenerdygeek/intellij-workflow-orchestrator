const delay = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

export async function simulateAgentResponse(): Promise<void> {
  const w = window as any;
  w.startSession?.('Explain the project structure');
  w.appendUserMessage?.('Explain the project structure');
  w.setBusy?.(true);
  await delay(300);

  w.appendToolCall?.('read_file', '{"path": "src/main.tsx"}', 'RUNNING');
  await delay(800);
  w.updateToolResult?.(
    'import { StrictMode } from "react"\nimport { createRoot } from "react-dom/client"\nimport App from "./App"\n\ncreateRoot(document.getElementById("root")!).render(\n  <StrictMode>\n    <App />\n  </StrictMode>,\n)',
    142, 'read_file'
  );
  await delay(200);

  const tokens = [
    'The project ', 'uses a ', '**React 18** ', 'frontend ',
    'with **TypeScript** ', 'and **Tailwind CSS v4**.\n\n',
    '## Key Files\n\n',
    '- `src/main.tsx` — Entry point\n',
    '- `src/App.tsx` — Root component\n',
    '- `src/bridge/` — JCEF bridge layer\n\n',
    '```typescript\n', 'function App() {\n', '  return <div>Hello</div>\n', '}\n', '```\n\n',
    'The bridge layer maps **70 functions** between Kotlin and JavaScript.',
  ];
  for (const token of tokens) {
    w.appendToken?.(token);
    await delay(30 + Math.random() * 50);
  }
  w.endStream?.();
  w.setBusy?.(false);
  w.completeSession?.('COMPLETED', 4521, 3200, 2, ['src/main.tsx']);
}

export async function simulatePlan(): Promise<void> {
  const w = window as any;
  const planJson = JSON.stringify({
    title: 'Implement user authentication',
    steps: [
      { id: 'step-1', title: 'Create auth service', description: 'Implement AuthService with login/logout methods', status: 'completed', filePaths: ['src/services/auth.ts'] },
      { id: 'step-2', title: 'Add login form component', description: 'Create LoginForm with email/password fields', status: 'running', filePaths: ['src/components/LoginForm.tsx'] },
      { id: 'step-3', title: 'Wire up routing', description: 'Add protected routes and redirect logic', status: 'pending', filePaths: ['src/App.tsx', 'src/routes.tsx'] },
      { id: 'step-4', title: 'Write tests', description: 'Unit tests for auth service and integration tests for login flow', status: 'pending', filePaths: ['src/services/__tests__/auth.test.ts'] },
    ],
    approved: false,
  });
  w.renderPlan?.(planJson);
  await delay(2000);
  w.updatePlanStep?.('step-2', 'completed');
  await delay(500);
  w.updatePlanStep?.('step-3', 'running');
}

export async function simulateQuestions(): Promise<void> {
  const w = window as any;
  const questionsJson = JSON.stringify([
    { id: 'q1', text: 'Which authentication strategy should we use?', type: 'single-select', options: [
      { label: 'JWT tokens', description: 'Stateless, good for APIs' },
      { label: 'Session cookies', description: 'Traditional, server-side state' },
      { label: 'OAuth 2.0', description: 'Delegate to identity provider' },
    ]},
    { id: 'q2', text: 'Which features should be included?', type: 'multi-select', options: [
      { label: 'Password reset', description: 'Email-based password recovery' },
      { label: 'Two-factor auth', description: 'TOTP or SMS verification' },
      { label: 'Social login', description: 'Google, GitHub, etc.' },
      { label: 'Remember me', description: 'Persistent login sessions' },
    ]},
  ]);
  w.showQuestions?.(questionsJson);
  w.showQuestion?.(0);
}

export async function simulateToolCalls(): Promise<void> {
  const w = window as any;
  w.appendToolCall?.('search_code', '{"pattern": "interface AuthService", "path": "src/"}', 'RUNNING');
  await delay(600);
  w.updateToolResult?.('Found 3 matches in 2 files:\n  src/services/auth.ts:5\n  src/services/auth.ts:12\n  src/types/auth.d.ts:1', 89, 'search_code');
  await delay(300);
  w.appendToolCall?.('edit_file', '{"path": "src/services/auth.ts", "old_string": "// TODO", "new_string": "async login()"}', 'RUNNING');
  await delay(400);
  w.updateToolResult?.('Edit applied successfully. 1 replacement made.', 52, 'edit_file');
  await delay(300);
  w.appendToolCall?.('run_command', '{"command": "npm test -- --filter auth"}', 'RUNNING');
  await delay(1200);
  w.updateToolResult?.('PASS src/services/__tests__/auth.test.ts\n  AuthService\n    ✓ should login with valid credentials (23ms)\n    ✓ should reject invalid password (8ms)\n\nTests: 2 passed, 2 total', 1150, 'run_command');
}

export function simulateTheme(isDark: boolean): void {
  const w = window as any;
  if (isDark) {
    w.applyTheme?.({
      'bg': '#2b2d30', 'fg': '#cbd5e1', 'fg-secondary': '#94a3b8', 'fg-muted': '#6b7280',
      'border': '#3f3f46', 'user-bg': '#1e293b', 'tool-bg': '#1a1d23', 'code-bg': '#1e1e2e', 'thinking-bg': '#1f2937',
      'badge-read-bg': '#1e3a5f', 'badge-read-fg': '#60a5fa', 'badge-write-bg': '#14532d', 'badge-write-fg': '#4ade80',
      'badge-edit-bg': '#451a03', 'badge-edit-fg': '#fbbf24', 'badge-cmd-bg': '#450a0a', 'badge-cmd-fg': '#f87171',
      'badge-search-bg': '#083344', 'badge-search-fg': '#22d3ee',
      'accent-read': '#3b82f6', 'accent-write': '#22c55e', 'accent-edit': '#f59e0b', 'accent-cmd': '#ef4444', 'accent-search': '#06b6d4',
      'diff-add-bg': '#14332a', 'diff-add-fg': '#86efac', 'diff-rem-bg': '#3b1818', 'diff-rem-fg': '#fca5a5',
      'success': '#22c55e', 'error': '#ef4444', 'warning': '#f59e0b', 'link': '#60a5fa',
      'hover-overlay': 'rgba(255,255,255,0.03)', 'hover-overlay-strong': 'rgba(255,255,255,0.05)',
      'divider-subtle': 'rgba(255,255,255,0.05)', 'row-alt': 'rgba(255,255,255,0.02)',
      'input-bg': '#1a1c22', 'input-border': 'rgba(255,255,255,0.08)', 'toolbar-bg': '#1e2028',
      'chip-bg': 'rgba(255,255,255,0.03)', 'chip-border': 'rgba(255,255,255,0.07)',
    });
  } else {
    w.applyTheme?.({
      'bg': '#ffffff', 'fg': '#1e293b', 'fg-secondary': '#475569', 'fg-muted': '#64748b',
      'border': '#e2e8f0', 'user-bg': '#f1f5f9', 'tool-bg': '#f8fafc', 'code-bg': '#f1f5f9', 'thinking-bg': '#f9fafb',
      'badge-read-bg': '#dbeafe', 'badge-read-fg': '#2563eb', 'badge-write-bg': '#dcfce7', 'badge-write-fg': '#16a34a',
      'badge-edit-bg': '#fef3c7', 'badge-edit-fg': '#d97706', 'badge-cmd-bg': '#fee2e2', 'badge-cmd-fg': '#dc2626',
      'badge-search-bg': '#cffafe', 'badge-search-fg': '#0891b2',
      'accent-read': '#3b82f6', 'accent-write': '#22c55e', 'accent-edit': '#f59e0b', 'accent-cmd': '#ef4444', 'accent-search': '#06b6d4',
      'diff-add-bg': '#dcfce7', 'diff-add-fg': '#166534', 'diff-rem-bg': '#fee2e2', 'diff-rem-fg': '#991b1b',
      'success': '#16a34a', 'error': '#dc2626', 'warning': '#d97706', 'link': '#2563eb',
      'hover-overlay': 'rgba(0,0,0,0.03)', 'hover-overlay-strong': 'rgba(0,0,0,0.05)',
      'divider-subtle': 'rgba(0,0,0,0.05)', 'row-alt': 'rgba(0,0,0,0.02)',
      'input-bg': '#ffffff', 'input-border': '#e2e8f0', 'toolbar-bg': '#f8fafc',
      'chip-bg': 'rgba(0,0,0,0.03)', 'chip-border': '#e2e8f0',
    });
  }
  w.setPrismTheme?.(isDark);
  w.setMermaidTheme?.(isDark);
}

export function installMockBridge(): void {
  const w = window as any;
  w.__mock = { simulateAgentResponse, simulatePlan, simulateQuestions, simulateToolCalls, simulateTheme };
  console.log(
    '%c[Mock Bridge] Dev mode active. Available simulations:\n' +
    '  __mock.simulateAgentResponse()\n' +
    '  __mock.simulatePlan()\n' +
    '  __mock.simulateQuestions()\n' +
    '  __mock.simulateToolCalls()\n' +
    '  __mock.simulateTheme(true|false)',
    'color: #60a5fa; font-weight: bold'
  );
}
