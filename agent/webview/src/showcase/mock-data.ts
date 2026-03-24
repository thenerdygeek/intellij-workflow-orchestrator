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
