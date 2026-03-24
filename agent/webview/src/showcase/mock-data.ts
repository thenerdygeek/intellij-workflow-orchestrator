import type { Message, ToolCall, Plan, Question, Mention, MentionSearchResult } from '@/bridge/types';

export const mockMessages: Message[] = [
  { id: '1', role: 'user', content: 'Can you read the main configuration file and check for any deprecated settings?', timestamp: Date.now() - 60000 },
  { id: '2', role: 'agent', content: 'I\'ll read the configuration file and analyze it for deprecated settings.\n\n```typescript\nconst config = loadConfig("app.config.ts");\n```\n\nFound **3 deprecated settings** that should be updated.', timestamp: Date.now() - 55000 },
  { id: '3', role: 'user', content: 'Show me a markdown example with all formatting.', timestamp: Date.now() - 50000 },
  { id: '4', role: 'agent', content: '## Markdown Demo\n\nHere\'s **bold**, *italic*, `inline code`, and a [link](https://example.com).\n\n- List item 1\n- List item 2\n  - Nested item\n\n> Blockquote text\n\n```python\ndef hello():\n    print("world")\n```', timestamp: Date.now() - 45000 },
];

export const mockStreamingMessage: Message = {
  id: '5', role: 'agent', content: 'Analyzing the test results and preparing a summary of the findings...', timestamp: Date.now(),
};

export const mockToolCalls: ToolCall[] = [
  { name: 'read_file', args: '{"path": "src/config.ts"}', status: 'COMPLETED', result: 'File content (247 lines)', durationMs: 120 },
  { name: 'search_code', args: '{"query": "deprecated", "glob": "**/*.ts"}', status: 'COMPLETED', result: '3 matches found', durationMs: 340 },
  { name: 'edit_file', args: '{"path": "src/config.ts", "old": "legacy: true", "new": "modern: true"}', status: 'RUNNING', result: undefined, durationMs: undefined },
  { name: 'run_command', args: '{"command": "npm test"}', status: 'COMPLETED', result: 'Tests: 47 passed, 2 failed\nTime: 12.4s', durationMs: 12400 },
  { name: 'run_command', args: '{"command": "rm -rf dist/"}', status: 'ERROR', result: 'Permission denied', durationMs: 50 },
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
