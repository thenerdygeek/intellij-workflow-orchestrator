const delay = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

export async function simulateAgentResponse(): Promise<void> {
  const w = window as any;
  w.startSession?.('Explain the project structure');
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

export async function simulateStreaming(): Promise<void> {
  const w = window as any;

  const sampleText = `# Analysis Complete

Here's what I found in the codebase:

1. **Authentication** is handled via Bearer tokens stored in PasswordSafe
2. The \`HttpClientFactory\` manages connection pooling

\`\`\`kotlin
class AuthService(private val credentials: CredentialStore) {
    suspend fun authenticate(): Token {
        return credentials.getToken("service-key")
    }
}
\`\`\`

> Note: All API calls use \`suspend fun\` on \`Dispatchers.IO\`

| Module | Status |
|--------|--------|
| core | Ready |
| jira | In progress |
`;

  const tokens = sampleText.split(/(?<=\s)/);
  for (const token of tokens) {
    w.appendToken?.(token);
    await new Promise(r => setTimeout(r, 20 + Math.random() * 30));
  }
  w.endStream?.();
}

export function simulateDiff(): void {
  const w = window as any;
  w.appendDiff?.(
    'src/services/auth.ts',
    [
      'import { CredentialStore } from "./credentials";',
      '',
      'export class AuthService {',
      '    private store: CredentialStore;',
      '',
      '    constructor(store: CredentialStore) {',
      '        this.store = store;',
      '    }',
      '',
      '    async login(user: string, pass: string): Promise<boolean> {',
      '        const token = await this.store.getToken(user);',
      '        return token !== null;',
      '    }',
      '}',
    ],
    [
      'import { CredentialStore } from "./credentials";',
      'import { Logger } from "./logger";',
      '',
      'export class AuthService {',
      '    private store: CredentialStore;',
      '    private log = Logger.getInstance("AuthService");',
      '',
      '    constructor(store: CredentialStore) {',
      '        this.store = store;',
      '    }',
      '',
      '    async login(user: string, pass: string): Promise<boolean> {',
      '        this.log.info(`Login attempt for user: ${user}`);',
      '        const token = await this.store.getToken(user);',
      '        if (token === null) {',
      '            this.log.warn(`Login failed for user: ${user}`);',
      '            return false;',
      '        }',
      '        this.log.info(`Login successful for user: ${user}`);',
      '        return true;',
      '    }',
      '}',
    ],
    true
  );
}

export function clearChat(): void {
  const w = window as any;
  w.clearChat?.();
}

export function installMockBridge(): void {
  const w = window as any;

  // Mock _togglePlanMode for dev mode
  w._togglePlanMode = (enabled: boolean) => {
    console.log(`[bridge:dev] togglePlanMode(${enabled})`);
  };

  // Mock _changeModel for dev mode — extract display name from the model ID
  w._changeModel = (modelId: string) => {
    console.log(`[bridge:dev] changeModel(${modelId})`);
    // Parse "provider::version::model-name" → display name
    const rawName = modelId.includes('::') ? modelId.split('::').pop()! : modelId;
    const displayName = rawName
      .replace(/-\d{8}$/, '')  // remove date suffix
      .replace(/-latest$/, '') // remove "latest"
      .replace(/(\d)-(\d)/g, '$1.$2') // "4-5" → "4.5"
      .split('-')
      .map(p => p.charAt(0).toUpperCase() + p.slice(1))
      .join(' ');
    w.setModelName?.(displayName);
  };

  // Mock _searchMentions — returns files, folders, symbols only (no tools/skills)
  w._searchMentions = (query: string) => {
    const mockItems = [
      { type: 'file', label: 'src/App.tsx', path: 'src/App.tsx', description: 'Root component' },
      { type: 'file', label: 'src/main.tsx', path: 'src/main.tsx', description: 'Entry point' },
      { type: 'file', label: 'src/stores/chatStore.ts', path: 'src/stores/chatStore.ts', description: 'Chat state' },
      { type: 'file', label: 'src/bridge/jcef-bridge.ts', path: 'src/bridge/jcef-bridge.ts', description: 'JCEF bridge' },
      { type: 'file', label: 'src/components/chat/ChatView.tsx', path: 'src/components/chat/ChatView.tsx', description: 'Chat view' },
      { type: 'file', label: 'src/components/input/InputBar.tsx', path: 'src/components/input/InputBar.tsx', description: 'Input bar' },
      { type: 'folder', label: 'src/components/', path: 'src/components', description: 'UI components' },
      { type: 'folder', label: 'src/bridge/', path: 'src/bridge', description: 'Bridge layer' },
      { type: 'folder', label: 'src/stores/', path: 'src/stores', description: 'State management' },
      { type: 'symbol', label: 'ChatView', path: 'src/components/chat/ChatView.tsx', description: 'function component' },
      { type: 'symbol', label: 'useChatStore', path: 'src/stores/chatStore.ts', description: 'Zustand store' },
      { type: 'symbol', label: 'InputBar', path: 'src/components/input/InputBar.tsx', description: 'function component' },
    ];
    const [typeFilter, searchTerm] = query.includes(':') ? query.split(':', 2) as [string, string] : ['', query];
    const filtered = typeFilter === 'categories'
      ? mockItems  // Show all items for initial display
      : mockItems.filter(r => {
          if (!searchTerm) return true;
          const q = searchTerm.toLowerCase();
          return r.label.toLowerCase().includes(q) || (r.path ?? '').toLowerCase().includes(q);
        });
    w.receiveMentionResults?.(JSON.stringify(filtered));
  };

  // Mock _searchTickets for # ticket autocomplete
  w._searchTickets = (query: string) => {
    const mockTickets = [
      { type: 'ticket', label: 'PROJ-123', path: 'PROJ-123', description: 'Fix login redirect on mobile', icon: 'In Progress' },
      { type: 'ticket', label: 'PROJ-124', path: 'PROJ-124', description: 'Add dark mode support', icon: 'To Do' },
      { type: 'ticket', label: 'PROJ-125', path: 'PROJ-125', description: 'Update API documentation', icon: 'In Review' },
      { type: 'ticket', label: 'PROJ-120', path: 'PROJ-120', description: 'Refactor auth middleware', icon: 'Done' },
      { type: 'ticket', label: 'PROJ-118', path: 'PROJ-118', description: 'Performance audit findings', icon: 'In Progress' },
    ];
    const q = query.toLowerCase();
    const filtered = q
      ? mockTickets.filter(t => t.label.toLowerCase().includes(q) || t.description.toLowerCase().includes(q))
      : mockTickets;
    (window as any).__ticketSearchCallback?.(JSON.stringify(filtered));
  };

  // Mock _validateTicket for async ticket key validation
  w._validateTicket = (ticketKey: string, callbackName: string) => {
    console.log(`[bridge:dev] validateTicket(${ticketKey})`);
    // Simulate async validation with a short delay
    setTimeout(() => {
      const validTickets: Record<string, string> = {
        'PROJ-123': 'Fix login redirect on mobile',
        'PROJ-124': 'Add dark mode support',
        'PROJ-125': 'Update API documentation',
        'PROJ-120': 'Refactor auth middleware',
        'PROJ-118': 'Performance audit findings',
      };
      const summary = validTickets[ticketKey.toUpperCase()];
      const result = summary
        ? JSON.stringify({ valid: true, summary })
        : JSON.stringify({ valid: false });
      (window as any)[callbackName]?.(result);
    }, 800); // Simulate network latency
  };

  // Populate mock skills list for / autocomplete
  setTimeout(() => {
    w.updateSkillsList?.(JSON.stringify([
      { name: 'systematic-debugging', description: 'Debug with structured root-cause analysis' },
      { name: 'interactive-debugging', description: 'Step through code with breakpoints' },
      { name: 'create-skill', description: 'Create a new workflow skill' },
    ]));
  }, 100);

  // Mock setDebugLogVisible — toggles debug log panel visibility in dev mode
  w.setDebugLogVisible = (visible: boolean) => {
    console.log(`[bridge:dev] setDebugLogVisible(${visible})`);
    w.__bridge?.setDebugLogVisible?.(visible);
  };

  // Mock addDebugLogEntry — appends a debug log entry in dev mode
  w.addDebugLogEntry = (entryJson: string) => {
    console.log(`[bridge:dev] addDebugLogEntry(${entryJson})`);
    w.__bridge?.addDebugLogEntry?.(entryJson);
  };

  w.__mock = { simulateAgentResponse, simulatePlan, simulateQuestions, simulateToolCalls, simulateTheme, simulateStreaming, simulateDiff, clearChat };
  console.log(
    '%c[Mock Bridge] Dev mode active. Available simulations:\n' +
    '  __mock.simulateAgentResponse()\n' +
    '  __mock.simulatePlan()\n' +
    '  __mock.simulateQuestions()\n' +
    '  __mock.simulateToolCalls()\n' +
    '  __mock.simulateStreaming()\n' +
    '  __mock.simulateTheme(true|false)\n' +
    '  __mock.clearChat()',
    'color: #60a5fa; font-weight: bold'
  );
}
