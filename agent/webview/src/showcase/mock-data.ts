import type { UiMessage, ToolCall, Plan, Question, Mention, MentionSearchResult } from '@/bridge/types';

export const mockMessages: UiMessage[] = [
  { ts: Date.now() - 60000, type: 'SAY', say: 'USER_MESSAGE', text: 'Can you read the main configuration file and check for any deprecated settings?' },
  { ts: Date.now() - 55000, type: 'SAY', say: 'TEXT', text: 'I\'ll read the configuration file and analyze it for deprecated settings.\n\n```typescript\nconst config = loadConfig("app.config.ts");\n```\n\nFound **3 deprecated settings** that should be updated.' },
  { ts: Date.now() - 50000, type: 'SAY', say: 'USER_MESSAGE', text: 'Show me a markdown example with all formatting.' },
  { ts: Date.now() - 45000, type: 'SAY', say: 'TEXT', text: '## Markdown Demo\n\nHere\'s **bold**, *italic*, `inline code`, and a [link](https://example.com).\n\n- List item 1\n- List item 2\n  - Nested item\n\n> Blockquote text\n\n```python\ndef hello():\n    print("world")\n```' },
];

export const mockStreamingMessage: UiMessage = {
  ts: Date.now(), type: 'SAY', say: 'TEXT', text: 'Analyzing the test results and preparing a summary of the findings...', partial: true,
};

export const mockToolCalls: ToolCall[] = [
  { id: 'tc-1', name: 'read_file', args: '{"path": "src/config.ts"}', status: 'COMPLETED', result: 'File content (247 lines)', durationMs: 120 },
  { id: 'tc-2', name: 'search_code', args: '{"query": "deprecated", "glob": "**/*.ts"}', status: 'COMPLETED', result: '3 matches found', durationMs: 340 },
  { id: 'tc-3', name: 'edit_file', args: '{"path": "src/config.ts", "old": "legacy: true", "new": "modern: true"}', status: 'RUNNING', result: undefined, durationMs: undefined },
  { id: 'tc-4', name: 'run_command', args: '{"command": "npm test"}', status: 'COMPLETED', result: 'Tests: 47 passed, 2 failed\nTime: 12.4s', durationMs: 12400 },
  { id: 'tc-5', name: 'run_command', args: '{"command": "rm -rf dist/"}', status: 'ERROR', result: 'Permission denied', durationMs: 50 },
];

export const mockPlanPending: Plan = {
  title: 'Refactor Authentication Module',
  steps: [
    { id: '1', title: 'Extract token validation into separate service', status: 'pending' },
    { id: '2', title: 'Add unit tests for token service', status: 'pending' },
    { id: '3', title: 'Update API routes to use new service', status: 'pending' },
    { id: '4', title: 'Remove deprecated auth middleware', status: 'pending' },
  ],
  approved: false,
};

export const mockPlanInProgress: Plan = {
  title: 'Refactor Authentication Module',
  steps: [
    { id: '1', title: 'Phase 1: Extract token validation', status: 'completed' },
    { id: '2', title: 'Phase 1: Add unit tests', status: 'completed' },
    { id: '3', title: 'Phase 2: Update API routes', status: 'running' },
    { id: '4', title: 'Phase 2: Remove deprecated middleware', status: 'pending' },
    { id: '5', title: 'Phase 3: Integration tests', status: 'pending' },
  ],
  approved: true,
};

export const mockQuestions: Question[] = [
  {
    id: 'q1',
    text: 'Which authentication strategy should we use?',
    type: 'single-select',
    options: [
      { label: 'JWT with refresh tokens', description: 'Stateless, scalable', selected: false },
      { label: 'Session-based with Redis', description: 'Simple, server-side state', selected: false },
      { label: 'OAuth 2.0 with PKCE', description: 'Third-party delegation', selected: false },
    ],
  },
  {
    id: 'q2',
    text: 'Which features should be included?',
    type: 'multi-select',
    options: [
      { label: 'Two-factor authentication', selected: false },
      { label: 'Password reset flow', selected: false },
      { label: 'Social login (Google, GitHub)', selected: false },
      { label: 'API key management', selected: false },
    ],
  },
];

export const mockMentions: Mention[] = [
  { type: 'file', label: 'config.ts', path: 'src/config.ts', icon: '📄' },
  { type: 'symbol', label: 'AuthService', path: 'src/auth.ts', icon: '#' },
  { type: 'tool', label: 'run_tests', icon: '🔧' },
];

export const mockMentionResults: MentionSearchResult[] = [
  { type: 'file', label: 'config.ts', path: 'src/config.ts', description: 'Main configuration' },
  { type: 'file', label: 'auth.ts', path: 'src/auth.ts', description: 'Authentication module' },
  { type: 'symbol', label: 'AuthService', path: 'src/auth.ts', description: 'class' },
  { type: 'symbol', label: 'TokenValidator', path: 'src/token.ts', description: 'interface' },
  { type: 'tool', label: 'read_file', description: 'Read a file from the filesystem' },
  { type: 'tool', label: 'edit_file', description: 'Edit a file with search/replace' },
  { type: 'skill', label: 'systematic-debugging', description: 'Step-by-step debugging workflow' },
];

// DataTable mock
export const mockTableData = JSON.stringify({
  columns: ['Build', 'Status', 'Duration', 'Branch', 'Triggered By'],
  rows: [
    ['#456', 'PASSED', '2m 30s', 'main', 'CI/CD'],
    ['#455', 'FAILED', '1m 12s', 'feature/auth', 'manual'],
    ['#454', 'PASSED', '3m 45s', 'main', 'CI/CD'],
    ['#453', 'PASSED', '2m 10s', 'fix/cache', 'PR merge'],
    ['#452', 'PASSED', '2m 55s', 'main', 'CI/CD'],
    ['#451', 'FAILED', '0m 45s', 'feature/dashboard', 'manual'],
    ['#450', 'PASSED', '2m 20s', 'main', 'CI/CD'],
    ['#449', 'PASSED', '4m 01s', 'release/v1.0', 'tag'],
  ],
  sortable: true,
  searchable: true,
});

// Timeline mock
export const mockTimelineData = JSON.stringify({
  title: 'Deployment Pipeline',
  events: [
    { time: '10:30', label: 'Build #456 triggered', status: 'info' },
    { time: '10:32', label: 'Compilation complete', status: 'success' },
    { time: '10:33', label: 'Unit tests: 124 passed', status: 'success' },
    { time: '10:35', label: 'Integration tests: 2 failures', status: 'error', description: 'AuthServiceTest.testTokenRefresh, CacheManagerTest.testEviction' },
    { time: '10:36', label: 'Build marked as unstable', status: 'warning' },
    { time: '10:40', label: 'Hotfix applied, rebuild triggered', status: 'info' },
    { time: '10:44', label: 'All tests passing', status: 'success' },
    { time: '10:45', label: 'Deployed to staging', status: 'success' },
  ],
});

// Progress mock
export const mockProgressData = JSON.stringify({
  title: 'Running Test Suite',
  phases: [
    { label: 'Compile', status: 'completed', duration: '12s' },
    { label: 'Unit Tests', status: 'completed', duration: '45s' },
    { label: 'Integration Tests', status: 'running', progress: 67 },
    { label: 'E2E Tests', status: 'pending' },
    { label: 'Coverage Report', status: 'pending' },
  ],
  overall: 52,
});

// Collapsible output mock
export const mockOutputData = `### Build Log
[INFO] Compiling module core...
[INFO] 47 files compiled in 12.3s
[INFO] Compiling module agent...
[INFO] 23 files compiled in 8.1s
[WARN] Deprecated API usage in AuthService.kt:45

### Test Results
Tests run: 124, Failures: 2, Errors: 0, Skipped: 3
FAIL: AuthServiceTest.testTokenRefresh - Expected 200, got 401
FAIL: CacheManagerTest.testEviction - Timeout after 5000ms
PASS: All other tests

### Coverage Summary
Overall: 78.3%
  core: 85.1% (+2.3%)
  agent: 62.4% (-1.1%)
  jira: 91.2% (unchanged)`;

// Animated flow mock
export const mockAnimatedFlow = JSON.stringify({
  title: 'Authentication Request Flow',
  direction: 'LR',
  nodes: [
    { id: 'client', label: 'Client', color: '#3b82f6' },
    { id: 'gateway', label: 'API Gateway' },
    { id: 'auth', label: 'Auth Service', color: '#8b5cf6' },
    { id: 'db', label: 'User DB', color: '#f59e0b' },
  ],
  edges: [
    { from: 'client', to: 'gateway', label: 'POST /login' },
    { from: 'gateway', to: 'auth', label: 'validate' },
    { from: 'auth', to: 'db', label: 'query user' },
  ],
  animated: true,
  highlightPath: ['client', 'gateway', 'auth', 'db', 'auth', 'gateway', 'client'],
  pathLabels: ['Login request', 'Forward to auth', 'Query user DB', 'Return user record', 'Issue JWT token', '200 OK + JWT'],
});

// Mermaid sequence diagram mock
export const mockSequenceDiagram = `sequenceDiagram
    Client->>Gateway: POST /login {username, password}
    Gateway->>Auth: validateCredentials(username, password)
    Auth->>DB: SELECT * FROM users WHERE username=?
    DB-->>Auth: UserRecord {id, hash, roles}
    Auth->>Auth: bcrypt.compare(password, hash)
    Auth-->>Gateway: {valid: true, token: "eyJ..."}
    Gateway-->>Client: 200 OK {token, refreshToken}`;

// ANSI terminal output mock
export const mockAnsiText =
  '\x1b[32m✓\x1b[0m Running test suite...\n' +
  '\x1b[32m✓\x1b[0m \x1b[1mAuthServiceTest\x1b[0m\x1b[32m PASSED\x1b[0m (47ms)\n' +
  '\x1b[32m✓\x1b[0m \x1b[1mTokenValidatorTest\x1b[0m\x1b[32m PASSED\x1b[0m (12ms)\n' +
  '\x1b[31m✗\x1b[0m \x1b[1mCacheManagerTest.testEviction\x1b[0m\x1b[31m FAILED\x1b[0m\n' +
  '    \x1b[31mExpected:\x1b[0m 200\n' +
  '    \x1b[31mReceived:\x1b[0m 408 Timeout after 5000ms\n' +
  '\x1b[33m⚠\x1b[0m  Deprecated API in \x1b[36msrc/auth-middleware.ts\x1b[0m\x1b[33m:45\x1b[0m\n' +
  '\n' +
  '\x1b[1mTest Suites:\x1b[0m \x1b[31m1 failed\x1b[0m, \x1b[32m3 passed\x1b[0m, 4 total\n' +
  '\x1b[1mTests:\x1b[0m       \x1b[31m1 failed\x1b[0m, \x1b[32m47 passed\x1b[0m, 48 total\n' +
  '\x1b[1mTime:\x1b[0m        2.34s';

// Chart.js bar chart mock
export const mockChartData = JSON.stringify({
  type: 'bar',
  data: {
    labels: ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun'],
    datasets: [
      {
        label: 'Build Duration (s)',
        data: [148, 95, 120, 88, 132, 76],
        backgroundColor: 'rgba(59, 130, 246, 0.5)',
        borderColor: 'rgba(59, 130, 246, 0.8)',
        borderWidth: 1,
      },
      {
        label: 'Test Count',
        data: [42, 47, 47, 51, 51, 54],
        backgroundColor: 'rgba(34, 197, 94, 0.5)',
        borderColor: 'rgba(34, 197, 94, 0.8)',
        borderWidth: 1,
      },
    ],
  },
});

// Git unified diff mock
export const mockDiffSource = `--- a/src/auth/token-service.ts
+++ b/src/auth/token-service.ts
@@ -10,10 +10,14 @@ import { hash } from './crypto';

 export class TokenService {
-  private readonly EXPIRY = 3600;
+  private readonly EXPIRY = 86400; // 24h instead of 1h
+  private readonly REFRESH_EXPIRY = 604800; // 7 days

   async validateToken(token: string): Promise<boolean> {
-    const decoded = jwt.decode(token);
+    const decoded = jwt.verify(token, process.env.JWT_SECRET!);
     return !!decoded;
   }
+
+  async refreshToken(token: string): Promise<string> {
+    const payload = jwt.decode(token) as Record<string, unknown>;
+    return jwt.sign({ sub: payload['sub'] }, process.env.JWT_SECRET!, { expiresIn: this.REFRESH_EXPIRY });
+  }
 }`;

// Image (inline SVG data URL) mock
export const mockImageSource = JSON.stringify({
  src: "data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20width%3D%22400%22%20height%3D%22180%22%3E%3Crect%20width%3D%22400%22%20height%3D%22180%22%20fill%3D%22%23334155%22%2F%3E%3Ccircle%20cx%3D%22200%22%20cy%3D%2280%22%20r%3D%2230%22%20fill%3D%22%233b82f6%22%2F%3E%3Crect%20x%3D%2280%22%20y%3D%22120%22%20width%3D%2260%22%20height%3D%2240%22%20rx%3D%224%22%20fill%3D%22%2322c55e%22%2F%3E%3Crect%20x%3D%22260%22%20y%3D%22120%22%20width%3D%2260%22%20height%3D%2240%22%20rx%3D%224%22%20fill%3D%22%23f59e0b%22%2F%3E%3Ctext%20x%3D%22200%22%20y%3D%22170%22%20text-anchor%3D%22middle%22%20fill%3D%22%2394a3b8%22%20font-family%3D%22sans-serif%22%20font-size%3D%2212%22%3ESample%20Architecture%20Diagram%3C%2Ftext%3E%3C%2Fsvg%3E",
  alt: "Architecture diagram",
  caption: "Module dependency graph generated from codebase analysis",
});

// KaTeX math mock
export const mockMathLatex = '\\int_0^\\infty e^{-x^2}\\,dx = \\frac{\\sqrt{\\pi}}{2}';
export const mockMathLatexInline = 'E = mc^2';

// Interactive HTML mock
export const mockInteractiveHtml = `<style>
  .counter { font-size: 48px; font-weight: 700; text-align: center; margin: 24px 0; color: var(--fg, #d4d4d4); }
  .btn { display: inline-block; padding: 8px 20px; margin: 3px; border-radius: 6px; cursor: pointer; background: var(--accent, #3b82f6); color: white; border: none; font-size: 14px; transition: opacity .15s; }
  .btn:hover { opacity: 0.75; }
  .label { text-align: center; font-size: 12px; color: var(--fg-muted, #888); padding-top: 8px; }
</style>
<div class="label">Interactive counter — click to change</div>
<div class="counter" id="c">0</div>
<div style="text-align:center">
  <button class="btn" onclick="document.getElementById('c').textContent=+document.getElementById('c').textContent-1">−</button>
  <button class="btn" onclick="document.getElementById('c').textContent=0" style="background:var(--fg-muted,#555)">Reset</button>
  <button class="btn" onclick="document.getElementById('c').textContent=+document.getElementById('c').textContent+1">+</button>
</div>`;

// Edit diff old/new lines mock
export const mockEditOldLines = [
  'export class TokenService {',
  '  private readonly EXPIRY = 3600;',
  '',
  '  async validateToken(token: string): Promise<boolean> {',
  '    const decoded = jwt.decode(token);',
  '    return !!decoded;',
  '  }',
  '}',
];

export const mockEditNewLines = [
  'export class TokenService {',
  '  private readonly EXPIRY = 86400; // 24h',
  '  private readonly REFRESH_EXPIRY = 604800; // 7d',
  '',
  '  async validateToken(token: string): Promise<boolean> {',
  '    const decoded = jwt.verify(token, process.env.JWT_SECRET!);',
  '    return !!decoded;',
  '  }',
  '',
  '  async refreshToken(token: string): Promise<string> {',
  '    const payload = jwt.decode(token) as Record<string, unknown>;',
  '    return jwt.sign({ sub: payload["sub"] }, process.env.JWT_SECRET!, { expiresIn: this.REFRESH_EXPIRY });',
  '  }',
  '}',
];

// Plan — all steps completed
export const mockPlanCompleted = {
  title: 'Refactor Authentication Module',
  steps: [
    { id: '1', title: 'Extract token validation into separate service', status: 'completed' as const },
    { id: '2', title: 'Add unit tests for token service', status: 'completed' as const },
    { id: '3', title: 'Update API routes to use new service', status: 'completed' as const },
    { id: '4', title: 'Remove deprecated auth middleware', status: 'completed' as const },
    { id: '5', title: 'Integration tests & verification', status: 'completed' as const },
  ],
  approved: true,
};

// Plan — with a failed step
export const mockPlanWithFailure = {
  title: 'Refactor Authentication Module',
  steps: [
    { id: '1', title: 'Extract token validation into separate service', status: 'completed' as const },
    { id: '2', title: 'Add unit tests for token service', status: 'completed' as const },
    { id: '3', title: 'Update API routes to use new service', status: 'failed' as const },
    { id: '4', title: 'Remove deprecated auth middleware', status: 'pending' as const },
    { id: '5', title: 'Integration tests & verification', status: 'pending' as const },
  ],
  approved: true,
};

// Plan — large (10 steps) for scrollable display
export const mockPlanLarge = {
  title: 'Migrate to Microservices Architecture',
  steps: [
    { id: '1', title: 'Phase 1: Extract Auth Service', status: 'completed' as const },
    { id: '2', title: 'Phase 1: Extract User Service', status: 'completed' as const },
    { id: '3', title: 'Phase 1: Extract Notification Service', status: 'completed' as const },
    { id: '4', title: 'Phase 2: Setup API Gateway', status: 'running' as const },
    { id: '5', title: 'Phase 2: Configure Service Discovery', status: 'pending' as const },
    { id: '6', title: 'Phase 2: Implement Circuit Breakers', status: 'pending' as const },
    { id: '7', title: 'Phase 3: Database Per Service', status: 'pending' as const },
    { id: '8', title: 'Phase 3: Event-Driven Communication', status: 'pending' as const },
    { id: '9', title: 'Phase 4: Monitoring & Observability', status: 'pending' as const },
    { id: '10', title: 'Phase 4: Load Testing & Release', status: 'pending' as const },
  ],
  approved: true,
};

// Plan editor data (for plan-editor.html / AgentPlanEditor)
// Format matches AgentPlan / PlanStep from Kotlin — goal, approach, steps[], testing
export const mockPlanEditorData = JSON.stringify({
  goal: 'Refactor the authentication module to extract token handling into a dedicated service, remove direct JWT usage from route files, and ensure full test coverage.',
  approach: 'Create a new TokenService that centralises all JWT operations (issue, validate, refresh, revoke). Update every route that currently imports jsonwebtoken directly to use the service instead. The middleware layer becomes a thin adapter. Finish with a full test run to confirm no regressions.',
  steps: [
    {
      id: '1',
      title: 'Create TokenService',
      description: 'Add src/auth/token-service.ts. Implement issueToken(payload), validateToken(token), refreshToken(token), and revokeToken(jti). Use the existing SECRET_KEY env var. Export a singleton instance. This is the only place in the codebase that should import jsonwebtoken.',
      files: ['src/auth/token-service.ts'],
      action: 'create',
      status: 'completed',
    },
    {
      id: '2',
      title: 'Write unit tests for TokenService',
      description: 'Add src/auth/__tests__/token-service.test.ts covering the happy path for all four methods plus edge cases: expired token, tampered signature, missing jti for revocation. Use jest.useFakeTimers() for expiry tests. Target 100% branch coverage on the service file.',
      files: ['src/auth/__tests__/token-service.test.ts'],
      action: 'create',
      status: 'completed',
    },
    {
      id: '3',
      title: 'Migrate auth routes to TokenService',
      description: 'Replace all direct jsonwebtoken calls in src/routes/auth.ts with the new service. The login handler calls tokenService.issueToken(), the refresh endpoint calls tokenService.refreshToken(), and logout calls tokenService.revokeToken(). Remove the jsonwebtoken import from this file.',
      files: ['src/routes/auth.ts'],
      action: 'edit',
      status: 'running',
      userComment: 'Check whether refresh token rotation is already handled or needs to be added here',
    },
    {
      id: '4',
      title: 'Migrate user & protected routes',
      description: 'src/routes/user.ts and src/middleware/require-auth.ts both call jwt.verify() directly. Replace with tokenService.validateToken(). The middleware should call next() on success and return 401 on TokenExpiredError or JsonWebTokenError.',
      files: ['src/routes/user.ts', 'src/middleware/require-auth.ts'],
      action: 'edit',
      status: 'pending',
    },
    {
      id: '5',
      title: 'Remove jsonwebtoken from route layer',
      description: 'After steps 3 and 4, grep the codebase for "from jsonwebtoken" and "require(jsonwebtoken)". Any remaining import outside of token-service.ts is a missed migration. Fix them. Then update package.json to mark jsonwebtoken as a peer dependency of the auth module only.',
      files: ['package.json'],
      action: 'verify',
      status: 'pending',
    },
    {
      id: '6',
      title: 'Integration tests & regression check',
      description: 'Run the full test suite with npm test. Fix any failures. Then run npm run test:integration against a local PostgreSQL instance to verify the login → token → protected-route flow end-to-end. Update src/docs/auth-architecture.md to reflect the new service boundary.',
      files: ['src/docs/auth-architecture.md'],
      action: 'verify',
      status: 'pending',
    },
  ],
  testing: 'Run npm test (unit) and npm run test:integration (end-to-end login flow). All 47 existing tests must continue to pass. New tests should bring token-service.ts to 100% branch coverage. Verify with npx jest --coverage.',
  approved: false,
});

// PlanMarkdownRenderer HTML mock (Swing legacy component — dead code, never wired up)
export const mockPlanMarkdownHtml = `<style>
  body { font-family: -apple-system, BlinkMacSystemFont, sans-serif; margin: 0; padding: 12px; background: #1e1e1e; color: #d4d4d4; }
  h3 { margin: 0 0 8px; font-size: 14px; color: #d4d4d4; }
  .subtitle { font-size: 11px; color: #6a737d; margin-bottom: 12px; }
  .task { padding: 6px 0; border-bottom: 1px solid #333; }
  .task:last-child { border-bottom: none; }
  .icon { font-size: 15px; font-weight: bold; margin-right: 8px; }
  .label { font-size: 13px; }
  .meta { margin-left: 24px; font-size: 11px; color: #6a737d; }
  .pending { color: #6a737d; }
  .running { color: #0366d6; }
  .done { color: #28a745; }
  .failed { color: #dc3545; }
</style>
<h3>Task Plan</h3>
<div class="subtitle">5 tasks</div>
<div class="task">
  <span class="icon done">✓</span><span class="label"><b>task-1</b>: Extract token validation into TokenService</span>
  <div class="meta">CREATE → src/auth/token-service.ts</div>
</div>
<div class="task">
  <span class="icon done">✓</span><span class="label"><b>task-2</b>: Add unit tests for TokenService</span>
  <div class="meta">CREATE → src/auth/__tests__/token-service.test.ts &nbsp;·&nbsp; depends on: task-1</div>
</div>
<div class="task">
  <span class="icon running">◉</span><span class="label"><b>task-3</b>: Update API routes to use new service</span>
  <div class="meta">EDIT → src/routes/auth.ts, src/routes/user.ts &nbsp;·&nbsp; depends on: task-1</div>
</div>
<div class="task">
  <span class="icon pending">○</span><span class="label"><b>task-4</b>: Remove deprecated auth-middleware</span>
  <div class="meta">DELETE → src/auth-middleware.ts &nbsp;·&nbsp; depends on: task-3</div>
</div>
<div class="task">
  <span class="icon pending">○</span><span class="label"><b>task-5</b>: Run integration tests</span>
  <div class="meta">RUN → npm test &nbsp;·&nbsp; depends on: task-4</div>
</div>`;

// Approval metadata samples
export const mockApprovalEditMetadata = [
  { key: 'File', value: 'src/auth/token-service.ts' },
  { key: 'Changes', value: '+4 lines, -2 lines' },
];

export const mockApprovalCommandMetadata = [
  { key: 'Command', value: 'npm run db:migrate --env production' },
  { key: 'Working dir', value: '/app' },
];

export const mockApprovalDestructiveMetadata = [
  { key: 'Command', value: 'DROP TABLE users CASCADE;' },
  { key: 'Database', value: 'production_db' },
];

// Flow with groups mock
export const mockFlowWithGroups = JSON.stringify({
  title: 'Module Dependencies',
  direction: 'TB',
  nodes: [
    { id: 'core', label: 'Core', color: '#3b82f6' },
    { id: 'jira', label: 'Jira' },
    { id: 'bamboo', label: 'Bamboo' },
    { id: 'sonar', label: 'SonarQube' },
    { id: 'agent', label: 'Agent', color: '#8b5cf6' },
    { id: 'pr', label: 'Pull Request' },
  ],
  edges: [
    { from: 'jira', to: 'core' },
    { from: 'bamboo', to: 'core' },
    { from: 'sonar', to: 'core' },
    { from: 'agent', to: 'core' },
    { from: 'pr', to: 'core' },
  ],
  groups: [
    { id: 'features', label: 'Feature Modules', nodeIds: ['jira', 'bamboo', 'sonar', 'pr'] },
    { id: 'ai', label: 'AI Layer', nodeIds: ['agent'] },
  ],
});
