# Agentic Chat UI Overhaul Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate the agent chat UI from a monolithic 4,374-line HTML file to a React 18 + TypeScript + Tailwind CSS application served via JCEF, achieving full feature parity with improved performance, maintainability, and rich visualization configurability.

**Architecture:** Shadow-build React app in `agent/webview/` using Vite, developing standalone via dev server. prompt-kit components (shadcn/ui-based, MIT) as the UI foundation with "Elevated IDE" styling. Zustand for state management. All 70 JCEF bridges preserved. Single cutover when feature-complete.

**Tech Stack:** React 18, TypeScript, Tailwind CSS v4, Vite, Zustand, react-markdown, Shiki, TanStack Virtual, prompt-kit/shadcn/ui, DOMPurify

---

## Phase 1: Build Pipeline & Foundation

### Task 1: Vite Project Scaffolding

**Objective:** Create the `agent/webview/` directory with a fully working Vite + React 18 + TypeScript + Tailwind CSS v4 project that builds to `agent/src/main/resources/webview/dist/`. Integrate with Gradle so `./gradlew buildPlugin` automatically triggers the webview build.

**Estimated time:** 15-20 minutes

#### Steps

- [ ] **1.1 Create directory structure**

  Create the `agent/webview/src/` directory tree:

  ```bash
  mkdir -p agent/webview/src
  ```

- [ ] **1.2 Create `package.json`**

  Create `agent/webview/package.json` with all dependencies from the spec:

  ```json
  {
    "name": "workflow-agent-webview",
    "private": true,
    "version": "0.1.0",
    "type": "module",
    "scripts": {
      "dev": "vite",
      "build": "tsc -b && vite build",
      "preview": "vite preview"
    },
    "dependencies": {
      "react": "^18.3.1",
      "react-dom": "^18.3.1",
      "zustand": "^5.0.3",
      "react-markdown": "^9.0.3",
      "remark-gfm": "^4.0.0",
      "rehype-raw": "^7.0.0",
      "shiki": "^3.2.1",
      "@tanstack/react-virtual": "^3.13.6",
      "dompurify": "^3.2.4",
      "ansi_up": "^6.0.2"
    },
    "devDependencies": {
      "@types/react": "^18.3.18",
      "@types/react-dom": "^18.3.5",
      "@types/dompurify": "^3.2.0",
      "typescript": "~5.7.2",
      "vite": "^6.3.1",
      "@vitejs/plugin-react": "^4.4.1",
      "tailwindcss": "^4.1.4",
      "@tailwindcss/vite": "^4.1.4",
      "terser": "^5.39.0"
    }
  }
  ```

- [ ] **1.3 Create `tsconfig.json`**

  Create `agent/webview/tsconfig.json`:

  ```json
  {
    "compilerOptions": {
      "target": "ES2020",
      "useDefineForClassFields": true,
      "lib": ["ES2020", "DOM", "DOM.Iterable"],
      "module": "ESNext",
      "skipLibCheck": true,
      "moduleResolution": "bundler",
      "allowImportingTsExtensions": true,
      "isolatedModules": true,
      "moduleDetection": "force",
      "noEmit": true,
      "jsx": "react-jsx",
      "strict": true,
      "noUnusedLocals": true,
      "noUnusedParameters": true,
      "noFallthroughCasesInSwitch": true,
      "noUncheckedIndexedAccess": true,
      "baseUrl": ".",
      "paths": {
        "@/*": ["src/*"]
      }
    },
    "include": ["src"]
  }
  ```

- [ ] **1.4 Create `vite.config.ts`**

  Create `agent/webview/vite.config.ts` matching the spec exactly — output to `../src/main/resources/webview/dist`, manualChunks for viz libraries, terser minification:

  ```typescript
  import { defineConfig } from 'vite'
  import react from '@vitejs/plugin-react'
  import tailwindcss from '@tailwindcss/vite'
  import path from 'path'

  export default defineConfig({
    plugins: [react(), tailwindcss()],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, 'src'),
      },
    },
    build: {
      outDir: '../src/main/resources/webview/dist',
      emptyOutDir: true,
      rollupOptions: {
        output: {
          // Allow code splitting — CefResourceSchemeHandler serves ALL files
          // under webview/dist/ by path, so multiple chunks work natively.
          // Do NOT use inlineDynamicImports: true — it prevents code splitting.
          manualChunks: {
            // Viz libraries get predictable chunk names for debugging
            mermaid: ['mermaid'],
            katex: ['katex'],
            chartjs: ['chart.js'],
            dagre: ['dagre'],
            diff2html: ['diff2html'],
          },
        },
      },
      assetsInlineLimit: 8192, // Inline assets < 8KB as base64 (Vite default)
      minify: 'terser',
      terserOptions: {
        compress: { drop_console: true, drop_debugger: true },
      },
    },
  })
  ```

- [ ] **1.5 Create `tailwind.config.ts`**

  Create `agent/webview/tailwind.config.ts` with a basic setup (will be enhanced in Task 3 with IDE theme CSS variables):

  ```typescript
  import type { Config } from 'tailwindcss'

  export default {
    content: ['./index.html', './src/**/*.{ts,tsx}'],
  } satisfies Config
  ```

- [ ] **1.6 Create `index.html`**

  Create `agent/webview/index.html` — minimal shell that loads the React app:

  ```html
  <!doctype html>
  <html lang="en">
    <head>
      <meta charset="UTF-8" />
      <meta name="viewport" content="width=device-width, initial-scale=1.0" />
      <title>Agent Chat</title>
    </head>
    <body class="bg-[var(--bg,#1e1e1e)] text-[var(--fg,#cccccc)] m-0 p-0 overflow-hidden">
      <div id="root"></div>
      <script type="module" src="/src/main.tsx"></script>
    </body>
  </html>
  ```

- [ ] **1.7 Create `src/main.tsx`**

  Create `agent/webview/src/main.tsx` — React root mount:

  ```tsx
  import { StrictMode } from 'react'
  import { createRoot } from 'react-dom/client'
  import App from './App'

  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  )
  ```

- [ ] **1.8 Create `src/App.tsx`**

  Create `agent/webview/src/App.tsx` — placeholder component confirming the pipeline works:

  ```tsx
  function App() {
    return (
      <div className="flex items-center justify-center h-screen">
        <h1 className="text-2xl font-semibold">Agent Chat</h1>
      </div>
    )
  }

  export default App
  ```

- [ ] **1.9 Create `src/index.css`**

  Create `agent/webview/src/index.css` with Tailwind v4 import:

  ```css
  @import "tailwindcss";
  ```

  Then add the CSS import to `src/main.tsx`:

  ```tsx
  import './index.css'
  ```

- [ ] **1.10 Add `buildWebview` Gradle task**

  Add the following to `agent/build.gradle.kts`:

  ```kotlin
  tasks.register<Exec>("buildWebview") {
      workingDir = file("webview")
      commandLine("npm", "run", "build")
  }

  tasks.named("processResources") {
      dependsOn("buildWebview")
  }
  ```

  This ensures `./gradlew buildPlugin` automatically builds the React app before packaging resources.

- [ ] **1.11 Verify dev server**

  Run the Vite dev server to confirm the project scaffolding works:

  ```bash
  cd agent/webview && npm install && npm run dev
  ```

  Confirm the browser shows "Agent Chat" centered on screen. Kill the dev server.

- [ ] **1.12 Verify production build**

  Run the production build and confirm output lands in the correct directory:

  ```bash
  cd agent/webview && npm run build
  ls ../src/main/resources/webview/dist/
  ```

  Expected: `index.html`, `assets/` directory with JS and CSS chunks. The `manualChunks` for viz libraries (mermaid, katex, etc.) will warn about missing dependencies — this is expected since those libraries are not yet installed (they are installed in later tasks when the viz components are built).

- [ ] **1.13 Commit**

  Commit all scaffolding files:

  ```
  feat(agent): scaffold Vite + React 18 + TypeScript + Tailwind CSS v4 webview project
  ```

### Task 2: JCEF Bridge Protocol Layer

**Objective:** Create the complete TypeScript bridge layer in `agent/webview/src/bridge/` that maps all 70 JCEF bridge functions (26 JS-to-Kotlin + 44 Kotlin-to-JS) to typed interfaces and Zustand store dispatches. Include a mock bridge for standalone dev server development.

**Estimated time:** 25-30 minutes

#### Steps

- [ ] **2.1 Create `bridge/types.ts`**

  Create `agent/webview/src/bridge/types.ts` with ALL TypeScript interfaces:

  ```typescript
  // ── Message types ──

  export type MessageRole = 'user' | 'agent' | 'system';

  export interface Message {
    id: string;
    role: MessageRole;
    content: string;
    timestamp: number;
  }

  // ── Tool call types ──

  export type ToolCallStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'ERROR';

  export interface ToolCall {
    name: string;
    args: string;
    status: ToolCallStatus;
    result?: string;
    durationMs?: number;
  }

  // ── Plan types ──

  export type PlanStepStatus = 'pending' | 'running' | 'completed' | 'failed' | 'skipped';

  export interface PlanStep {
    id: string;
    title: string;
    description?: string;
    status: PlanStepStatus;
    comment?: string;
    filePaths?: string[];
  }

  export interface Plan {
    title: string;
    steps: PlanStep[];
    approved: boolean;
  }

  // ── Question types ──

  export type QuestionType = 'single-select' | 'multi-select' | 'text';

  export interface QuestionOption {
    label: string;
    description?: string;
    selected?: boolean;
  }

  export interface Question {
    id: string;
    text: string;
    type: QuestionType;
    options: QuestionOption[];
    answer?: string | string[];
    skipped?: boolean;
  }

  // ── Session types ──

  export type SessionStatus = 'RUNNING' | 'COMPLETED' | 'CANCELLED' | 'ERROR';

  export interface SessionInfo {
    status: SessionStatus;
    tokensUsed: number;
    durationMs: number;
    iterations: number;
    filesModified: string[];
  }

  // ── Mention types ──

  export type MentionType = 'file' | 'folder' | 'symbol' | 'tool' | 'skill';

  export interface Mention {
    type: MentionType;
    label: string;
    path?: string;
    icon?: string;
  }

  export interface MentionSearchResult {
    type: MentionType;
    label: string;
    path?: string;
    description?: string;
    icon?: string;
  }

  // ── Theme types ──

  export interface ThemeVars {
    // Core layout
    bg: string;
    fg: string;
    'fg-secondary': string;
    'fg-muted': string;
    border: string;

    // Semantic backgrounds
    'user-bg': string;
    'tool-bg': string;
    'code-bg': string;
    'thinking-bg': string;

    // Tool category badges (background + foreground pairs)
    'badge-read-bg': string;
    'badge-read-fg': string;
    'badge-write-bg': string;
    'badge-write-fg': string;
    'badge-edit-bg': string;
    'badge-edit-fg': string;
    'badge-cmd-bg': string;
    'badge-cmd-fg': string;
    'badge-search-bg': string;
    'badge-search-fg': string;

    // Tool category accent colors
    'accent-read': string;
    'accent-write': string;
    'accent-edit': string;
    'accent-cmd': string;
    'accent-search': string;

    // Diff colors
    'diff-add-bg': string;
    'diff-add-fg': string;
    'diff-rem-bg': string;
    'diff-rem-fg': string;

    // Status colors
    success: string;
    error: string;
    warning: string;
    link: string;

    // UI chrome
    'hover-overlay': string;
    'hover-overlay-strong': string;
    'divider-subtle': string;
    'row-alt': string;
    'input-bg': string;
    'input-border': string;
    'toolbar-bg': string;
    'chip-bg': string;
    'chip-border': string;

    // Additional from agent-plan.html
    accent?: string;
    running?: string;
    pending?: string;

    // Allow additional string keys
    [key: string]: string | undefined;
  }

  // ── Status types ──

  export type StatusType = 'INFO' | 'SUCCESS' | 'WARNING' | 'ERROR';

  // ── Toast types ──

  export type ToastType = 'info' | 'success' | 'warning' | 'error';

  // ── Skill types ──

  export interface Skill {
    name: string;
    description: string;
    active?: boolean;
  }

  // ── Diff types ──

  export interface EditDiff {
    filePath: string;
    oldLines: string[];
    newLines: string[];
    accepted: boolean | null;
  }

  // ── Visualization settings ──

  export type VisualizationType = 'mermaid' | 'chart' | 'flow' | 'math' | 'diff' | 'interactiveHtml';

  export interface VisualizationConfig {
    enabled: boolean;
    autoRender: boolean;
    defaultExpanded: boolean;
    maxHeight: number;
  }
  ```

- [ ] **2.2 Create `bridge/jcef-bridge.ts`**

  Create `agent/webview/src/bridge/jcef-bridge.ts` — exposes ALL Kotlin-to-JS methods as globals on `window` and on `window.__bridge`. Each method dispatches to the Zustand store. Detects dev mode when `window._sendMessage` is not defined by Kotlin.

  ```typescript
  import type {
    ThemeVars,
    ToolCallStatus,
    StatusType,
    SessionStatus,
  } from './types';

  // ── Zustand store imports (lazy — resolved after stores are created) ──
  // These will be set by initBridge() after stores are initialized
  type StoreAccessors = {
    getChatStore: () => any;
    getThemeStore: () => any;
    getSettingsStore: () => any;
  };

  let stores: StoreAccessors | null = null;

  /**
   * Detect if running inside JCEF (Kotlin has injected window._sendMessage)
   * or in standalone dev mode (npm run dev in browser).
   */
  export function isJcefEnvironment(): boolean {
    return typeof (window as any)._sendMessage === 'function';
  }

  // ══════════════════════════════════════════════════════════
  //  Kotlin → JS bridge functions (44 total)
  //  Kotlin calls these via executeJavaScript("functionName(args)")
  //  Each is registered as BOTH window.functionName AND window.__bridge.functionName
  // ══════════════════════════════════════════════════════════

  const bridgeFunctions: Record<string, (...args: any[]) => void> = {
    // #1 startSession(task)
    startSession(task: string) {
      stores?.getChatStore().startSession(task);
    },

    // #2 appendUserMessage(text)
    appendUserMessage(text: string) {
      stores?.getChatStore().addMessage('user', text);
    },

    // #3 endStream()
    endStream() {
      stores?.getChatStore().endStream();
    },

    // #4 completeSession(status, tokensUsed, durationMs, iterations, filesModified)
    completeSession(
      status: string,
      tokensUsed: number,
      durationMs: number,
      iterations: number,
      filesModified: string[]
    ) {
      stores?.getChatStore().completeSession({
        status: status as SessionStatus,
        tokensUsed,
        durationMs,
        iterations,
        filesModified: filesModified || [],
      });
    },

    // #5 appendToken(token)
    appendToken(token: string) {
      stores?.getChatStore().appendToken(token);
    },

    // #6 appendToolCall(toolName, args, status)
    appendToolCall(toolName: string, args: string, status: string) {
      stores?.getChatStore().addToolCall(toolName, args, status as ToolCallStatus);
    },

    // #7 updateToolResult(result, durationMs, toolName)
    updateToolResult(result: string, durationMs: number, toolName: string) {
      stores?.getChatStore().updateToolCall(toolName, 'COMPLETED', result, durationMs);
    },

    // #8 appendDiff(filePath, oldLines, newLines, accepted)
    appendDiff(
      filePath: string,
      oldLines: string[],
      newLines: string[],
      accepted: boolean | null
    ) {
      stores?.getChatStore().addDiff({ filePath, oldLines, newLines, accepted });
    },

    // #9 appendStatus(message, type)
    appendStatus(message: string, type: string) {
      stores?.getChatStore().addStatus(message, type as StatusType);
    },

    // #10 appendThinking(text)
    appendThinking(text: string) {
      stores?.getChatStore().addThinking(text);
    },

    // #11 clearChat()
    clearChat() {
      stores?.getChatStore().clearChat();
    },

    // #12 showToolsPanel(toolsJson)
    showToolsPanel(toolsJson: string) {
      stores?.getChatStore().showToolsPanel(toolsJson);
    },

    // #13 closeToolsPanel()
    closeToolsPanel() {
      stores?.getChatStore().hideToolsPanel();
    },

    // #14 renderPlan(planJson)
    renderPlan(planJson: string) {
      const plan = JSON.parse(planJson);
      stores?.getChatStore().setPlan(plan);
    },

    // #15 updatePlanStep(stepId, status)
    updatePlanStep(stepId: string, status: string) {
      stores?.getChatStore().updatePlanStep(stepId, status);
    },

    // #16 showQuestions(questionsJson)
    showQuestions(questionsJson: string) {
      const questions = JSON.parse(questionsJson);
      stores?.getChatStore().showQuestions(questions);
    },

    // #17 showQuestion(index)
    showQuestion(index: number) {
      stores?.getChatStore().showQuestion(index);
    },

    // #18 showQuestionSummary(summaryJson)
    showQuestionSummary(summaryJson: string) {
      const summary = JSON.parse(summaryJson);
      stores?.getChatStore().showQuestionSummary(summary);
    },

    // #19 enableChatInput()
    enableChatInput() {
      stores?.getChatStore().setInputLocked(false);
    },

    // #20 setBusy(busy)
    setBusy(busy: boolean) {
      stores?.getChatStore().setBusy(busy);
    },

    // #21 setInputLocked(locked)
    setInputLocked(locked: boolean) {
      stores?.getChatStore().setInputLocked(locked);
    },

    // #22 updateTokenBudget(used, max)
    updateTokenBudget(used: number, max: number) {
      stores?.getChatStore().updateTokenBudget(used, max);
    },

    // #23 setModelName(name)
    setModelName(name: string) {
      stores?.getChatStore().setModelName(name);
    },

    // #24 updateSkillsList(skillsJson)
    updateSkillsList(skillsJson: string) {
      const skills = JSON.parse(skillsJson);
      stores?.getChatStore().updateSkillsList(skills);
    },

    // #25 showRetryButton(lastMessage)
    showRetryButton(lastMessage: string) {
      stores?.getChatStore().showRetryButton(lastMessage);
    },

    // #26 focusInput()
    focusInput() {
      stores?.getChatStore().focusInput();
    },

    // #27 showSkillBanner(name)
    showSkillBanner(name: string) {
      stores?.getChatStore().showSkillBanner(name);
    },

    // #28 hideSkillBanner()
    hideSkillBanner() {
      stores?.getChatStore().hideSkillBanner();
    },

    // #29 appendChart(chartConfigJson)
    appendChart(chartConfigJson: string) {
      stores?.getChatStore().addChart(chartConfigJson);
    },

    // #30 appendAnsiOutput(text)
    appendAnsiOutput(text: string) {
      stores?.getChatStore().addAnsiOutput(text);
    },

    // #31 showSkeleton()
    showSkeleton() {
      stores?.getChatStore().showSkeleton();
    },

    // #32 hideSkeleton()
    hideSkeleton() {
      stores?.getChatStore().hideSkeleton();
    },

    // #33 showToast(message, type, durationMs)
    showToast(message: string, type: string, durationMs: number) {
      stores?.getChatStore().showToast(message, type, durationMs);
    },

    // #34 appendTabs(tabsJson)
    appendTabs(tabsJson: string) {
      stores?.getChatStore().addTabs(tabsJson);
    },

    // #35 appendTimeline(itemsJson)
    appendTimeline(itemsJson: string) {
      stores?.getChatStore().addTimeline(itemsJson);
    },

    // #36 appendProgressBar(percent, type)
    appendProgressBar(percent: number, type: string) {
      stores?.getChatStore().addProgressBar(percent, type);
    },

    // #37 appendJiraCard(cardJson)
    appendJiraCard(cardJson: string) {
      stores?.getChatStore().addJiraCard(cardJson);
    },

    // #38 appendSonarBadge(badgeJson)
    appendSonarBadge(badgeJson: string) {
      stores?.getChatStore().addSonarBadge(badgeJson);
    },

    // #39 receiveMentionResults(resultsJson)
    receiveMentionResults(resultsJson: string) {
      const results = JSON.parse(resultsJson);
      stores?.getChatStore().receiveMentionResults(results);
    },

    // #40 applyTheme(vars) — theme-specific
    applyTheme(vars: Record<string, string>) {
      stores?.getThemeStore().applyTheme(JSON.stringify(vars));
    },

    // #41 setPrismTheme(isDark) — maps to Shiki theme switch in React
    setPrismTheme(isDark: boolean) {
      stores?.getThemeStore().setIsDark(isDark);
    },

    // #42 setMermaidTheme(isDark) — maps to themeStore.isDark
    setMermaidTheme(isDark: boolean) {
      stores?.getThemeStore().setIsDark(isDark);
    },
  };

  // ══════════════════════════════════════════════════════════
  //  JS → Kotlin bridge wrappers (26 total)
  //  These call window._xxx globals injected by Kotlin's JBCefJSQuery
  //  In dev mode, they log to console instead
  // ══════════════════════════════════════════════════════════

  function callKotlin(fnName: string, ...args: any[]): void {
    const fn = (window as any)[fnName];
    if (typeof fn === 'function') {
      fn(...args);
    } else {
      console.log(`[bridge:dev] ${fnName}(${args.map(a => JSON.stringify(a)).join(', ')})`);
    }
  }

  export const kotlinBridge = {
    // #1 requestUndo
    requestUndo(): void {
      callKotlin('_requestUndo');
    },

    // #2 requestViewTrace
    requestViewTrace(): void {
      callKotlin('_requestViewTrace');
    },

    // #3 submitPrompt
    submitPrompt(text: string): void {
      callKotlin('_submitPrompt', text);
    },

    // #4 approvePlan
    approvePlan(): void {
      callKotlin('_approvePlan');
    },

    // #5 revisePlan
    revisePlan(comments: string): void {
      callKotlin('_revisePlan', comments);
    },

    // #6 toggleTool
    toggleTool(toolName: string, enabled: boolean): void {
      callKotlin('_toggleTool', `${toolName}:${enabled ? '1' : '0'}`);
    },

    // #7 questionAnswered
    questionAnswered(questionId: string, selectedOptionsJson: string): void {
      callKotlin('_questionAnswered', questionId, selectedOptionsJson);
    },

    // #8 questionSkipped
    questionSkipped(questionId: string): void {
      callKotlin('_questionSkipped', questionId);
    },

    // #9 chatAboutOption
    chatAboutOption(questionId: string, optionLabel: string, message: string): void {
      callKotlin('_chatAboutOption', questionId, optionLabel, message);
    },

    // #10 questionsSubmitted
    questionsSubmitted(): void {
      callKotlin('_questionsSubmitted');
    },

    // #11 questionsCancelled
    questionsCancelled(): void {
      callKotlin('_questionsCancelled');
    },

    // #12 editQuestion
    editQuestion(questionId: string): void {
      callKotlin('_editQuestion', questionId);
    },

    // #13 deactivateSkill
    deactivateSkill(): void {
      callKotlin('_deactivateSkill');
    },

    // #14 navigateToFile
    navigateToFile(filePath: string, line?: number): void {
      const path = line && line > 0 ? `${filePath}:${line}` : filePath;
      callKotlin('_navigateToFile', path);
    },

    // #15 cancelTask
    cancelTask(): void {
      callKotlin('_cancelTask');
    },

    // #16 newChat
    newChat(): void {
      callKotlin('_newChat');
    },

    // #17 sendMessage
    sendMessage(text: string): void {
      callKotlin('_sendMessage', text);
    },

    // #18 changeModel
    changeModel(modelId: string): void {
      callKotlin('_changeModel', modelId);
    },

    // #19 togglePlanMode
    togglePlanMode(enabled: boolean): void {
      callKotlin('_togglePlanMode', enabled);
    },

    // #20 activateSkill
    activateSkill(name: string): void {
      callKotlin('_activateSkill', name);
    },

    // #21 requestFocusIde
    requestFocusIde(): void {
      callKotlin('_requestFocusIde');
    },

    // #22 openSettings
    openSettings(): void {
      callKotlin('_openSettings');
    },

    // #23 openToolsPanel
    openToolsPanel(): void {
      callKotlin('_openToolsPanel');
    },

    // #24 searchMentions
    searchMentions(type: string, query: string): void {
      callKotlin('_searchMentions', `${type}:${query}`);
    },

    // #25 sendMessageWithMentions
    sendMessageWithMentions(text: string, mentionsJson: string): void {
      const payload = JSON.stringify({ text, mentions: JSON.parse(mentionsJson) });
      callKotlin('_sendMessageWithMentions', payload);
    },

    // #26 — Note: bridge #26 is sendMessageWithMentionsQuery
    // (already covered above as #25 — the spec table lists 25 numbered entries
    //  with #25 being sendMessageWithMentionsQuery; the JS function name is
    //  _sendMessageWithMentions)
  };

  // ══════════════════════════════════════════════════════════
  //  Initialization — register all globals
  // ══════════════════════════════════════════════════════════

  /**
   * Initialize the bridge layer. Must be called AFTER Zustand stores are created.
   * Registers all Kotlin→JS bridge functions as window globals and on window.__bridge.
   */
  export function initBridge(storeAccessors: StoreAccessors): void {
    stores = storeAccessors;

    // Register all Kotlin→JS functions as window globals AND on window.__bridge
    const bridge: Record<string, (...args: any[]) => void> = {};

    for (const [name, fn] of Object.entries(bridgeFunctions)) {
      (window as any)[name] = fn;
      bridge[name] = fn;
    }

    (window as any).__bridge = bridge;
  }
  ```

- [ ] **2.3 Create `bridge/mock-bridge.ts`**

  Create `agent/webview/src/bridge/mock-bridge.ts` — dev mode mock that simulates streaming tokens, tool call lifecycle, plan rendering, and question wizard. Used when running via `npm run dev` outside JCEF.

  ```typescript
  /**
   * Mock bridge for standalone development outside JCEF.
   * Simulates Kotlin→JS calls to exercise the full UI pipeline.
   * Activated automatically when window._sendMessage is not defined.
   */

  const delay = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

  /**
   * Simulate a streaming agent response with tool calls.
   * Call this from the dev console or from a "demo" button.
   */
  export async function simulateAgentResponse(): Promise<void> {
    const w = window as any;

    // Start session
    w.startSession?.('Explain the project structure');

    // Simulate user message
    w.appendUserMessage?.('Explain the project structure');

    // Simulate busy state
    w.setBusy?.(true);

    await delay(300);

    // Simulate a tool call — read_file
    w.appendToolCall?.('read_file', '{"path": "src/main.tsx"}', 'RUNNING');
    await delay(800);
    w.updateToolResult?.(
      'import { StrictMode } from "react"\nimport { createRoot } from "react-dom/client"\nimport App from "./App"\n\ncreateRoot(document.getElementById("root")!).render(\n  <StrictMode>\n    <App />\n  </StrictMode>,\n)',
      142,
      'read_file'
    );

    await delay(200);

    // Simulate streaming response
    const tokens = [
      'The project ', 'uses a ', '**React 18** ', 'frontend ',
      'with **TypeScript** ', 'and **Tailwind CSS v4**.\n\n',
      '## Key Files\n\n',
      '- `src/main.tsx` — Entry point\n',
      '- `src/App.tsx` — Root component\n',
      '- `src/bridge/` — JCEF bridge layer\n\n',
      '```typescript\n',
      'function App() {\n',
      '  return <div>Hello</div>\n',
      '}\n',
      '```\n\n',
      'The bridge layer maps **70 functions** between Kotlin and JavaScript.',
    ];

    for (const token of tokens) {
      w.appendToken?.(token);
      await delay(30 + Math.random() * 50);
    }

    w.endStream?.();
    w.setBusy?.(false);

    // Complete session
    w.completeSession?.('COMPLETED', 4521, 3200, 2, ['src/main.tsx']);
  }

  /**
   * Simulate a plan being rendered with interactive approval.
   */
  export async function simulatePlan(): Promise<void> {
    const w = window as any;

    const planJson = JSON.stringify({
      title: 'Implement user authentication',
      steps: [
        {
          id: 'step-1',
          title: 'Create auth service',
          description: 'Implement AuthService with login/logout methods',
          status: 'completed',
          filePaths: ['src/services/auth.ts'],
        },
        {
          id: 'step-2',
          title: 'Add login form component',
          description: 'Create LoginForm with email/password fields',
          status: 'running',
          filePaths: ['src/components/LoginForm.tsx'],
        },
        {
          id: 'step-3',
          title: 'Wire up routing',
          description: 'Add protected routes and redirect logic',
          status: 'pending',
          filePaths: ['src/App.tsx', 'src/routes.tsx'],
        },
        {
          id: 'step-4',
          title: 'Write tests',
          description: 'Unit tests for auth service and integration tests for login flow',
          status: 'pending',
          filePaths: ['src/services/__tests__/auth.test.ts'],
        },
      ],
      approved: false,
    });

    w.renderPlan?.(planJson);

    // Simulate step progress
    await delay(2000);
    w.updatePlanStep?.('step-2', 'completed');
    await delay(500);
    w.updatePlanStep?.('step-3', 'running');
  }

  /**
   * Simulate the question wizard flow.
   */
  export async function simulateQuestions(): Promise<void> {
    const w = window as any;

    const questionsJson = JSON.stringify([
      {
        id: 'q1',
        text: 'Which authentication strategy should we use?',
        type: 'single-select',
        options: [
          { label: 'JWT tokens', description: 'Stateless, good for APIs' },
          { label: 'Session cookies', description: 'Traditional, server-side state' },
          { label: 'OAuth 2.0', description: 'Delegate to identity provider' },
        ],
      },
      {
        id: 'q2',
        text: 'Which features should be included?',
        type: 'multi-select',
        options: [
          { label: 'Password reset', description: 'Email-based password recovery' },
          { label: 'Two-factor auth', description: 'TOTP or SMS verification' },
          { label: 'Social login', description: 'Google, GitHub, etc.' },
          { label: 'Remember me', description: 'Persistent login sessions' },
        ],
      },
    ]);

    w.showQuestions?.(questionsJson);
    w.showQuestion?.(0);
  }

  /**
   * Simulate tool call lifecycle with various tool types.
   */
  export async function simulateToolCalls(): Promise<void> {
    const w = window as any;

    // Search tool
    w.appendToolCall?.('search_code', '{"pattern": "interface AuthService", "path": "src/"}', 'RUNNING');
    await delay(600);
    w.updateToolResult?.('Found 3 matches in 2 files:\n  src/services/auth.ts:5\n  src/services/auth.ts:12\n  src/types/auth.d.ts:1', 89, 'search_code');

    await delay(300);

    // Edit tool
    w.appendToolCall?.('edit_file', '{"path": "src/services/auth.ts", "old_string": "// TODO", "new_string": "async login()"}', 'RUNNING');
    await delay(400);
    w.updateToolResult?.('Edit applied successfully. 1 replacement made.', 52, 'edit_file');

    await delay(300);

    // Command tool
    w.appendToolCall?.('run_command', '{"command": "npm test -- --filter auth"}', 'RUNNING');
    await delay(1200);
    w.updateToolResult?.('PASS src/services/__tests__/auth.test.ts\n  AuthService\n    ✓ should login with valid credentials (23ms)\n    ✓ should reject invalid password (8ms)\n\nTests: 2 passed, 2 total', 1150, 'run_command');
  }

  /**
   * Simulate theme application for dev mode.
   */
  export function simulateTheme(isDark: boolean): void {
    const w = window as any;

    if (isDark) {
      w.applyTheme?.({
        'bg': '#2b2d30', 'fg': '#cbd5e1',
        'fg-secondary': '#94a3b8', 'fg-muted': '#6b7280',
        'border': '#3f3f46', 'user-bg': '#1e293b',
        'tool-bg': '#1a1d23', 'code-bg': '#1e1e2e',
        'thinking-bg': '#1f2937',
        'badge-read-bg': '#1e3a5f', 'badge-read-fg': '#60a5fa',
        'badge-write-bg': '#14532d', 'badge-write-fg': '#4ade80',
        'badge-edit-bg': '#451a03', 'badge-edit-fg': '#fbbf24',
        'badge-cmd-bg': '#450a0a', 'badge-cmd-fg': '#f87171',
        'badge-search-bg': '#083344', 'badge-search-fg': '#22d3ee',
        'accent-read': '#3b82f6', 'accent-write': '#22c55e',
        'accent-edit': '#f59e0b', 'accent-cmd': '#ef4444',
        'accent-search': '#06b6d4',
        'diff-add-bg': '#14332a', 'diff-add-fg': '#86efac',
        'diff-rem-bg': '#3b1818', 'diff-rem-fg': '#fca5a5',
        'success': '#22c55e', 'error': '#ef4444',
        'warning': '#f59e0b', 'link': '#60a5fa',
        'hover-overlay': 'rgba(255,255,255,0.03)',
        'hover-overlay-strong': 'rgba(255,255,255,0.05)',
        'divider-subtle': 'rgba(255,255,255,0.05)',
        'row-alt': 'rgba(255,255,255,0.02)',
        'input-bg': '#1a1c22',
        'input-border': 'rgba(255,255,255,0.08)',
        'toolbar-bg': '#1e2028',
        'chip-bg': 'rgba(255,255,255,0.03)',
        'chip-border': 'rgba(255,255,255,0.07)',
      });
    } else {
      w.applyTheme?.({
        'bg': '#ffffff', 'fg': '#1e293b',
        'fg-secondary': '#475569', 'fg-muted': '#64748b',
        'border': '#e2e8f0', 'user-bg': '#f1f5f9',
        'tool-bg': '#f8fafc', 'code-bg': '#f1f5f9',
        'thinking-bg': '#f9fafb',
        'badge-read-bg': '#dbeafe', 'badge-read-fg': '#2563eb',
        'badge-write-bg': '#dcfce7', 'badge-write-fg': '#16a34a',
        'badge-edit-bg': '#fef3c7', 'badge-edit-fg': '#d97706',
        'badge-cmd-bg': '#fee2e2', 'badge-cmd-fg': '#dc2626',
        'badge-search-bg': '#cffafe', 'badge-search-fg': '#0891b2',
        'accent-read': '#3b82f6', 'accent-write': '#22c55e',
        'accent-edit': '#f59e0b', 'accent-cmd': '#ef4444',
        'accent-search': '#06b6d4',
        'diff-add-bg': '#dcfce7', 'diff-add-fg': '#166534',
        'diff-rem-bg': '#fee2e2', 'diff-rem-fg': '#991b1b',
        'success': '#16a34a', 'error': '#dc2626',
        'warning': '#d97706', 'link': '#2563eb',
        'hover-overlay': 'rgba(0,0,0,0.03)',
        'hover-overlay-strong': 'rgba(0,0,0,0.05)',
        'divider-subtle': 'rgba(0,0,0,0.05)',
        'row-alt': 'rgba(0,0,0,0.02)',
        'input-bg': '#ffffff',
        'input-border': '#e2e8f0',
        'toolbar-bg': '#f8fafc',
        'chip-bg': 'rgba(0,0,0,0.03)',
        'chip-border': '#e2e8f0',
      });
    }

    w.setPrismTheme?.(isDark);
    w.setMermaidTheme?.(isDark);
  }

  /**
   * Install mock bridge for dev mode. Called from App.tsx when not in JCEF.
   * Exposes simulation functions on window for console access.
   */
  export function installMockBridge(): void {
    const w = window as any;
    w.__mock = {
      simulateAgentResponse,
      simulatePlan,
      simulateQuestions,
      simulateToolCalls,
      simulateTheme,
    };
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
  ```

- [ ] **2.4 Wire bridge in `App.tsx`**

  Update `agent/webview/src/App.tsx` to initialize the bridge on mount. Import stores (created in Task 4) and pass them to `initBridge()`. In dev mode, install the mock bridge and apply default dark theme.

  ```tsx
  import { useEffect } from 'react'
  import { initBridge, isJcefEnvironment } from './bridge/jcef-bridge'
  import { installMockBridge, simulateTheme } from './bridge/mock-bridge'
  import { useChatStore } from './stores/chatStore'
  import { useThemeStore } from './stores/themeStore'
  import { useSettingsStore } from './stores/settingsStore'

  function App() {
    useEffect(() => {
      // Initialize bridge with store accessors
      initBridge({
        getChatStore: () => useChatStore.getState(),
        getThemeStore: () => useThemeStore.getState(),
        getSettingsStore: () => useSettingsStore.getState(),
      });

      // In dev mode, install mock bridge and apply default theme
      if (!isJcefEnvironment()) {
        installMockBridge();
        simulateTheme(true); // Default to dark theme in dev
      }
    }, []);

    return (
      <div className="flex items-center justify-center h-screen bg-[var(--bg,#2b2d30)] text-[var(--fg,#cbd5e1)]">
        <h1 className="text-2xl font-semibold">Agent Chat</h1>
        <p className="text-sm text-[var(--fg-secondary,#94a3b8)] mt-2">
          Bridge initialized. {isJcefEnvironment() ? 'JCEF mode' : 'Dev mode — open console for __mock commands.'}
        </p>
      </div>
    );
  }

  export default App
  ```

- [ ] **2.5 Verify in dev server console**

  ```bash
  cd agent/webview && npm run dev
  ```

  In the browser console:
  1. Confirm `[Mock Bridge] Dev mode active` message appears
  2. Run `__mock.simulateAgentResponse()` — confirm console logs show bridge calls
  3. Run `__mock.simulatePlan()` — confirm `renderPlan` dispatches to store
  4. Run `__mock.simulateQuestions()` — confirm `showQuestions` dispatches to store
  5. Confirm no TypeScript errors in the terminal

- [ ] **2.6 Commit**

  ```
  feat(agent): implement JCEF bridge protocol layer with 70 typed bridge functions and dev mock
  ```

---

### Task 3: Theme System

**Objective:** Create the theme system that receives CSS variables from Kotlin's `applyCurrentTheme()`, applies them to the document root, and makes them available to all components via Zustand and Tailwind CSS utilities.

**Estimated time:** 15-20 minutes

#### Steps

- [ ] **3.1 Create `bridge/theme-controller.ts`**

  Create `agent/webview/src/bridge/theme-controller.ts` — receives `applyTheme(cssVarsJson)` from Kotlin, parses the JSON, and applies CSS variables to `document.documentElement.style`:

  ```typescript
  /**
   * Theme controller — receives theme CSS variables from Kotlin's
   * AgentCefPanel.applyCurrentTheme() and applies them to the document root.
   *
   * Variable names arrive WITHOUT the '--' prefix (e.g., 'bg', 'fg-secondary').
   * This controller adds the '--' prefix when setting CSS custom properties.
   */

  /**
   * Apply a theme from a JSON string of CSS variable key-value pairs.
   * Called by Kotlin via: applyTheme({bg:'#2b2d30', fg:'#cbd5e1', ...})
   *
   * @param cssVarsJson — either a JSON string or an already-parsed object
   *   Keys are variable names WITHOUT '--' prefix.
   *   Values are CSS color values (hex, rgba, etc.).
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

  /**
   * Read the current value of a CSS variable from the document root.
   */
  export function getCssVariable(name: string): string {
    return getComputedStyle(document.documentElement).getPropertyValue(`--${name}`).trim();
  }

  /**
   * Detect if the current theme is dark based on the --bg variable.
   * Uses luminance calculation on the background color.
   */
  export function detectIsDark(bgColor: string): boolean {
    // Parse hex color
    const hex = bgColor.replace('#', '');
    if (hex.length !== 6) return true; // Default to dark if unparseable

    const r = parseInt(hex.substring(0, 2), 16) / 255;
    const g = parseInt(hex.substring(2, 4), 16) / 255;
    const b = parseInt(hex.substring(4, 6), 16) / 255;

    // Relative luminance (sRGB)
    const luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
    return luminance < 0.5;
  }
  ```

- [ ] **3.2 Create `stores/themeStore.ts`**

  Create `agent/webview/src/stores/themeStore.ts` — Zustand store for the current theme:

  ```typescript
  import { create } from 'zustand';
  import { applyThemeVariables, detectIsDark } from '../bridge/theme-controller';
  import type { ThemeVars } from '../bridge/types';

  interface ThemeState {
    /** All CSS variable values currently applied */
    cssVariables: Record<string, string>;
    /** Whether the current theme is dark */
    isDark: boolean;

    /** Apply theme from Kotlin's applyCurrentTheme() call */
    applyTheme(cssVarsJson: string | Record<string, string>): void;
    /** Set isDark explicitly (from setPrismTheme/setMermaidTheme calls) */
    setIsDark(isDark: boolean): void;
    /** Get a specific CSS variable value */
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
  ```

- [ ] **3.3 Create `hooks/useTheme.ts`**

  Create `agent/webview/src/hooks/useTheme.ts` — hook for components to access theme values:

  ```typescript
  import { useThemeStore } from '../stores/themeStore';
  import type { ThemeVars } from '../bridge/types';

  /**
   * Hook to access the current IDE theme in components.
   *
   * Usage:
   *   const { isDark, getVar } = useTheme();
   *   const bgColor = getVar('code-bg');
   */
  export function useTheme() {
    const isDark = useThemeStore(s => s.isDark);
    const getVar = useThemeStore(s => s.getVar);
    const cssVariables = useThemeStore(s => s.cssVariables);

    return { isDark, getVar, cssVariables };
  }
  ```

- [ ] **3.4 Update `tailwind.config.ts`**

  Replace `agent/webview/tailwind.config.ts` with a configuration that maps Tailwind utilities to the ACTUAL CSS variable names from `AgentCefPanel.kt`'s `applyCurrentTheme()`:

  ```typescript
  import type { Config } from 'tailwindcss'

  export default {
    content: ['./index.html', './src/**/*.{ts,tsx}'],
    theme: {
      extend: {
        colors: {
          // Core layout
          'ide-bg': 'var(--bg)',
          'ide-fg': 'var(--fg)',
          'ide-fg-secondary': 'var(--fg-secondary)',
          'ide-fg-muted': 'var(--fg-muted)',
          'ide-border': 'var(--border)',

          // Semantic backgrounds
          'ide-user-bg': 'var(--user-bg)',
          'ide-tool-bg': 'var(--tool-bg)',
          'ide-code-bg': 'var(--code-bg)',
          'ide-thinking-bg': 'var(--thinking-bg)',

          // Tool category badges
          'badge-read-bg': 'var(--badge-read-bg)',
          'badge-read-fg': 'var(--badge-read-fg)',
          'badge-write-bg': 'var(--badge-write-bg)',
          'badge-write-fg': 'var(--badge-write-fg)',
          'badge-edit-bg': 'var(--badge-edit-bg)',
          'badge-edit-fg': 'var(--badge-edit-fg)',
          'badge-cmd-bg': 'var(--badge-cmd-bg)',
          'badge-cmd-fg': 'var(--badge-cmd-fg)',
          'badge-search-bg': 'var(--badge-search-bg)',
          'badge-search-fg': 'var(--badge-search-fg)',

          // Tool category accents
          'accent-read': 'var(--accent-read)',
          'accent-write': 'var(--accent-write)',
          'accent-edit': 'var(--accent-edit)',
          'accent-cmd': 'var(--accent-cmd)',
          'accent-search': 'var(--accent-search)',

          // Diff
          'diff-add-bg': 'var(--diff-add-bg)',
          'diff-add-fg': 'var(--diff-add-fg)',
          'diff-rem-bg': 'var(--diff-rem-bg)',
          'diff-rem-fg': 'var(--diff-rem-fg)',

          // Status
          'ide-success': 'var(--success)',
          'ide-error': 'var(--error)',
          'ide-warning': 'var(--warning)',
          'ide-link': 'var(--link)',

          // UI chrome
          'ide-hover': 'var(--hover-overlay)',
          'ide-hover-strong': 'var(--hover-overlay-strong)',
          'ide-divider': 'var(--divider-subtle)',
          'ide-row-alt': 'var(--row-alt)',
          'ide-input-bg': 'var(--input-bg)',
          'ide-input-border': 'var(--input-border)',
          'ide-toolbar-bg': 'var(--toolbar-bg)',
          'ide-chip-bg': 'var(--chip-bg)',
          'ide-chip-border': 'var(--chip-border)',

          // Additional
          'ide-accent': 'var(--accent)',
          'ide-running': 'var(--running)',
          'ide-pending': 'var(--pending)',
        },
        fontFamily: {
          body: ['var(--font-body)', '-apple-system', 'BlinkMacSystemFont', 'Segoe UI', 'Roboto', 'sans-serif'],
          mono: ['var(--font-mono)', 'JetBrains Mono', 'Menlo', 'Consolas', 'Courier New', 'monospace'],
        },
        borderColor: {
          DEFAULT: 'var(--border)',
        },
      },
    },
  } satisfies Config
  ```

- [ ] **3.5 Create `config/theme-defaults.ts`**

  Create `agent/webview/src/config/theme-defaults.ts` — default dark theme values for the dev server, matching `AgentCefPanel.kt`'s dark theme map exactly:

  ```typescript
  /**
   * Default dark theme CSS variables for standalone dev server.
   * These values are copied directly from AgentCefPanel.kt's applyCurrentTheme()
   * dark-mode branch to ensure visual fidelity during development.
   */
  export const defaultDarkTheme: Record<string, string> = {
    // Core layout
    'bg': '#2b2d30',
    'fg': '#cbd5e1',
    'fg-secondary': '#94a3b8',
    'fg-muted': '#6b7280',
    'border': '#3f3f46',

    // Semantic backgrounds
    'user-bg': '#1e293b',
    'tool-bg': '#1a1d23',
    'code-bg': '#1e1e2e',
    'thinking-bg': '#1f2937',

    // Tool category badges
    'badge-read-bg': '#1e3a5f',
    'badge-read-fg': '#60a5fa',
    'badge-write-bg': '#14532d',
    'badge-write-fg': '#4ade80',
    'badge-edit-bg': '#451a03',
    'badge-edit-fg': '#fbbf24',
    'badge-cmd-bg': '#450a0a',
    'badge-cmd-fg': '#f87171',
    'badge-search-bg': '#083344',
    'badge-search-fg': '#22d3ee',

    // Tool category accents
    'accent-read': '#3b82f6',
    'accent-write': '#22c55e',
    'accent-edit': '#f59e0b',
    'accent-cmd': '#ef4444',
    'accent-search': '#06b6d4',

    // Diff
    'diff-add-bg': '#14332a',
    'diff-add-fg': '#86efac',
    'diff-rem-bg': '#3b1818',
    'diff-rem-fg': '#fca5a5',

    // Status
    'success': '#22c55e',
    'error': '#ef4444',
    'warning': '#f59e0b',
    'link': '#60a5fa',

    // UI chrome
    'hover-overlay': 'rgba(255,255,255,0.03)',
    'hover-overlay-strong': 'rgba(255,255,255,0.05)',
    'divider-subtle': 'rgba(255,255,255,0.05)',
    'row-alt': 'rgba(255,255,255,0.02)',
    'input-bg': '#1a1c22',
    'input-border': 'rgba(255,255,255,0.08)',
    'toolbar-bg': '#1e2028',
    'chip-bg': 'rgba(255,255,255,0.03)',
    'chip-border': 'rgba(255,255,255,0.07)',

    // Additional plan/accent variables
    'accent': '#60a5fa',
    'running': '#60a5fa',
    'pending': '#6b7280',
  };

  /**
   * Default light theme CSS variables for standalone dev server.
   * Copied from AgentCefPanel.kt's applyCurrentTheme() light-mode branch.
   */
  export const defaultLightTheme: Record<string, string> = {
    // Core layout
    'bg': '#ffffff',
    'fg': '#1e293b',
    'fg-secondary': '#475569',
    'fg-muted': '#64748b',
    'border': '#e2e8f0',

    // Semantic backgrounds
    'user-bg': '#f1f5f9',
    'tool-bg': '#f8fafc',
    'code-bg': '#f1f5f9',
    'thinking-bg': '#f9fafb',

    // Tool category badges
    'badge-read-bg': '#dbeafe',
    'badge-read-fg': '#2563eb',
    'badge-write-bg': '#dcfce7',
    'badge-write-fg': '#16a34a',
    'badge-edit-bg': '#fef3c7',
    'badge-edit-fg': '#d97706',
    'badge-cmd-bg': '#fee2e2',
    'badge-cmd-fg': '#dc2626',
    'badge-search-bg': '#cffafe',
    'badge-search-fg': '#0891b2',

    // Tool category accents
    'accent-read': '#3b82f6',
    'accent-write': '#22c55e',
    'accent-edit': '#f59e0b',
    'accent-cmd': '#ef4444',
    'accent-search': '#06b6d4',

    // Diff
    'diff-add-bg': '#dcfce7',
    'diff-add-fg': '#166534',
    'diff-rem-bg': '#fee2e2',
    'diff-rem-fg': '#991b1b',

    // Status
    'success': '#16a34a',
    'error': '#dc2626',
    'warning': '#d97706',
    'link': '#2563eb',

    // UI chrome
    'hover-overlay': 'rgba(0,0,0,0.03)',
    'hover-overlay-strong': 'rgba(0,0,0,0.05)',
    'divider-subtle': 'rgba(0,0,0,0.05)',
    'row-alt': 'rgba(0,0,0,0.02)',
    'input-bg': '#ffffff',
    'input-border': '#e2e8f0',
    'toolbar-bg': '#f8fafc',
    'chip-bg': 'rgba(0,0,0,0.03)',
    'chip-border': '#e2e8f0',

    // Additional plan/accent variables
    'accent': '#2563eb',
    'running': '#2563eb',
    'pending': '#64748b',
  };

  /** Font stacks (not sent by Kotlin — defined in :root CSS defaults) */
  export const fontDefaults = {
    'font-body': "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif",
    'font-mono': "'JetBrains Mono', Menlo, Consolas, 'Courier New', monospace",
  };
  ```

- [ ] **3.6 Update `src/index.css`**

  Update `agent/webview/src/index.css` to include font defaults and reduced-motion media query:

  ```css
  @import "tailwindcss";

  :root {
    --font-body: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    --font-mono: 'JetBrains Mono', Menlo, Consolas, 'Courier New', monospace;
  }

  body {
    font-family: var(--font-body);
    -webkit-font-smoothing: antialiased;
    -moz-osx-font-smoothing: grayscale;
  }

  code, pre, .font-mono {
    font-family: var(--font-mono);
  }

  /* Respect reduced-motion preferences — all animations disabled */
  @media (prefers-reduced-motion: reduce) {
    *, *::before, *::after {
      animation-duration: 0.01ms !important;
      transition-duration: 0.01ms !important;
    }
  }
  ```

- [ ] **3.7 Verify theme applies in dev**

  ```bash
  cd agent/webview && npm run dev
  ```

  In the browser console:
  1. Run `__mock.simulateTheme(true)` — confirm dark theme applies (dark background, light text)
  2. Run `__mock.simulateTheme(false)` — confirm light theme applies (white background, dark text)
  3. Inspect `document.documentElement.style` — confirm `--bg`, `--fg`, `--border`, etc. are set
  4. Confirm Tailwind utilities like `bg-ide-bg`, `text-ide-fg` resolve correctly

- [ ] **3.8 Commit**

  ```
  feat(agent): implement theme system with CSS variable injection and Tailwind integration
  ```

---

### Task 4: Zustand Stores

**Objective:** Create the two Zustand stores that hold all application state: `chatStore` for messages, streaming, tool calls, plans, questions, and UI state; `settingsStore` for per-type visualization configuration.

**Estimated time:** 20-25 minutes

#### Steps

- [ ] **4.1 Create `stores/chatStore.ts`**

  Create `agent/webview/src/stores/chatStore.ts` — the primary state store with full typing for all actions dispatched by the bridge:

  ```typescript
  import { create } from 'zustand';
  import type {
    Message,
    ToolCall,
    ToolCallStatus,
    Plan,
    PlanStepStatus,
    Question,
    SessionInfo,
    SessionStatus,
    Mention,
    MentionSearchResult,
    StatusType,
    EditDiff,
    Skill,
    ToastType,
  } from '../bridge/types';

  // ── Internal ID generator ──
  let _idCounter = 0;
  function nextId(prefix: string = 'msg'): string {
    return `${prefix}-${Date.now()}-${++_idCounter}`;
  }

  // ── Toast state ──
  interface Toast {
    id: string;
    message: string;
    type: ToastType;
    durationMs: number;
  }

  // ── Chat store state ──
  interface ChatState {
    // ── Messages ──
    messages: Message[];

    // ── Active stream ──
    activeStream: { text: string; isStreaming: boolean } | null;

    // ── Tool calls ──
    activeToolCalls: Map<string, ToolCall>;

    // ── Plan ──
    plan: Plan | null;

    // ── Questions ──
    questions: Question[] | null;
    activeQuestionIndex: number;
    questionSummary: any | null;

    // ── Session info ──
    session: SessionInfo;

    // ── Input state ──
    inputState: {
      locked: boolean;
      mentions: Mention[];
      model: string;
      mode: 'agent' | 'plan';
    };

    // ── UI state ──
    busy: boolean;
    showingToolsPanel: boolean;
    toolsPanelData: string | null;
    showingSkeleton: boolean;
    retryMessage: string | null;
    toasts: Toast[];
    skillBanner: string | null;
    skillsList: Skill[];
    tokenBudget: { used: number; max: number };
    mentionResults: MentionSearchResult[];
    focusInputTrigger: number; // Increment to trigger focus

    // ══════════════════════════════════════════════
    //  Actions
    // ══════════════════════════════════════════════

    // ── Session lifecycle ──
    startSession(task: string): void;
    completeSession(info: SessionInfo): void;

    // ── Messages ──
    addMessage(role: 'user' | 'agent', content: string): void;

    // ── Streaming ──
    appendToken(token: string): void;
    endStream(): void;

    // ── Tool calls ──
    addToolCall(name: string, args: string, status: ToolCallStatus): void;
    updateToolCall(name: string, status: ToolCallStatus, result: string, durationMs: number): void;

    // ── Diffs ──
    addDiff(diff: EditDiff): void;

    // ── Status ──
    addStatus(message: string, type: StatusType): void;

    // ── Thinking ──
    addThinking(text: string): void;

    // ── Chat management ──
    clearChat(): void;

    // ── Plan ──
    setPlan(plan: Plan): void;
    updatePlanStep(stepId: string, status: string): void;

    // ── Questions ──
    showQuestions(questions: Question[]): void;
    showQuestion(index: number): void;
    showQuestionSummary(summary: any): void;

    // ── Input control ──
    setInputLocked(locked: boolean): void;
    setBusy(busy: boolean): void;
    setModelName(model: string): void;

    // ── Token budget ──
    updateTokenBudget(used: number, max: number): void;

    // ── Skills ──
    updateSkillsList(skills: Skill[]): void;
    showSkillBanner(name: string): void;
    hideSkillBanner(): void;

    // ── Tools panel ──
    showToolsPanel(toolsJson: string): void;
    hideToolsPanel(): void;

    // ── Retry ──
    showRetryButton(lastMessage: string): void;

    // ── Focus ──
    focusInput(): void;

    // ── Rich content ──
    addChart(chartConfigJson: string): void;
    addAnsiOutput(text: string): void;
    addTabs(tabsJson: string): void;
    addTimeline(itemsJson: string): void;
    addProgressBar(percent: number, type: string): void;
    addJiraCard(cardJson: string): void;
    addSonarBadge(badgeJson: string): void;

    // ── Skeleton ──
    showSkeleton(): void;
    hideSkeleton(): void;

    // ── Toasts ──
    showToast(message: string, type: string, durationMs: number): void;
    dismissToast(id: string): void;

    // ── Mentions ──
    receiveMentionResults(results: MentionSearchResult[]): void;

    // ── Send message (dispatches to Kotlin bridge) ──
    sendMessage(text: string, mentions: Mention[]): void;
  }

  export const useChatStore = create<ChatState>((set, get) => ({
    // ── Initial state ──
    messages: [],
    activeStream: null,
    activeToolCalls: new Map(),
    plan: null,
    questions: null,
    activeQuestionIndex: 0,
    questionSummary: null,
    session: {
      status: 'RUNNING' as SessionStatus,
      tokensUsed: 0,
      durationMs: 0,
      iterations: 0,
      filesModified: [],
    },
    inputState: {
      locked: false,
      mentions: [],
      model: '',
      mode: 'agent',
    },
    busy: false,
    showingToolsPanel: false,
    toolsPanelData: null,
    showingSkeleton: false,
    retryMessage: null,
    toasts: [],
    skillBanner: null,
    skillsList: [],
    tokenBudget: { used: 0, max: 0 },
    mentionResults: [],
    focusInputTrigger: 0,

    // ══════════════════════════════════════════════
    //  Action implementations
    // ══════════════════════════════════════════════

    startSession(task: string) {
      set({
        messages: [],
        activeStream: null,
        activeToolCalls: new Map(),
        plan: null,
        questions: null,
        questionSummary: null,
        busy: true,
        retryMessage: null,
        session: {
          status: 'RUNNING',
          tokensUsed: 0,
          durationMs: 0,
          iterations: 0,
          filesModified: [],
        },
      });
    },

    completeSession(info: SessionInfo) {
      set({
        session: info,
        busy: false,
        activeStream: null,
      });
    },

    addMessage(role: 'user' | 'agent', content: string) {
      const message: Message = {
        id: nextId('msg'),
        role,
        content,
        timestamp: Date.now(),
      };
      set(state => ({
        messages: [...state.messages, message],
      }));
    },

    appendToken(token: string) {
      set(state => {
        const stream = state.activeStream ?? { text: '', isStreaming: true };
        return {
          activeStream: {
            text: stream.text + token,
            isStreaming: true,
          },
        };
      });
    },

    endStream() {
      const stream = get().activeStream;
      if (stream && stream.text.length > 0) {
        const message: Message = {
          id: nextId('msg'),
          role: 'agent',
          content: stream.text,
          timestamp: Date.now(),
        };
        set(state => ({
          messages: [...state.messages, message],
          activeStream: null,
        }));
      } else {
        set({ activeStream: null });
      }
    },

    addToolCall(name: string, args: string, status: ToolCallStatus) {
      set(state => {
        const newMap = new Map(state.activeToolCalls);
        newMap.set(name, { name, args, status });
        return { activeToolCalls: newMap };
      });
    },

    updateToolCall(name: string, status: ToolCallStatus, result: string, durationMs: number) {
      set(state => {
        const newMap = new Map(state.activeToolCalls);
        const existing = newMap.get(name);
        if (existing) {
          newMap.set(name, { ...existing, status, result, durationMs });
        } else {
          newMap.set(name, { name, args: '', status, result, durationMs });
        }
        return { activeToolCalls: newMap };
      });
    },

    addDiff(diff: EditDiff) {
      const message: Message = {
        id: nextId('diff'),
        role: 'system',
        content: JSON.stringify(diff),
        timestamp: Date.now(),
      };
      set(state => ({ messages: [...state.messages, message] }));
    },

    addStatus(message: string, type: StatusType) {
      const statusMessage: Message = {
        id: nextId('status'),
        role: 'system',
        content: JSON.stringify({ type: 'status', message, statusType: type }),
        timestamp: Date.now(),
      };
      set(state => ({ messages: [...state.messages, statusMessage] }));
    },

    addThinking(text: string) {
      const thinkingMessage: Message = {
        id: nextId('thinking'),
        role: 'system',
        content: JSON.stringify({ type: 'thinking', text }),
        timestamp: Date.now(),
      };
      set(state => ({ messages: [...state.messages, thinkingMessage] }));
    },

    clearChat() {
      set({
        messages: [],
        activeStream: null,
        activeToolCalls: new Map(),
        plan: null,
        questions: null,
        questionSummary: null,
        retryMessage: null,
      });
    },

    setPlan(plan: Plan) {
      set({ plan });
    },

    updatePlanStep(stepId: string, status: string) {
      set(state => {
        if (!state.plan) return {};
        const steps = state.plan.steps.map(step =>
          step.id === stepId ? { ...step, status: status as PlanStepStatus } : step
        );
        return { plan: { ...state.plan, steps } };
      });
    },

    showQuestions(questions: Question[]) {
      set({ questions, activeQuestionIndex: 0, questionSummary: null });
    },

    showQuestion(index: number) {
      set({ activeQuestionIndex: index });
    },

    showQuestionSummary(summary: any) {
      set({ questionSummary: summary });
    },

    setInputLocked(locked: boolean) {
      set(state => ({
        inputState: { ...state.inputState, locked },
      }));
    },

    setBusy(busy: boolean) {
      set({ busy });
    },

    setModelName(model: string) {
      set(state => ({
        inputState: { ...state.inputState, model },
      }));
    },

    updateTokenBudget(used: number, max: number) {
      set({ tokenBudget: { used, max } });
    },

    updateSkillsList(skills: Skill[]) {
      set({ skillsList: skills });
    },

    showSkillBanner(name: string) {
      set({ skillBanner: name });
    },

    hideSkillBanner() {
      set({ skillBanner: null });
    },

    showToolsPanel(toolsJson: string) {
      set({ showingToolsPanel: true, toolsPanelData: toolsJson });
    },

    hideToolsPanel() {
      set({ showingToolsPanel: false, toolsPanelData: null });
    },

    showRetryButton(lastMessage: string) {
      set({ retryMessage: lastMessage });
    },

    focusInput() {
      set(state => ({ focusInputTrigger: state.focusInputTrigger + 1 }));
    },

    addChart(chartConfigJson: string) {
      const message: Message = {
        id: nextId('chart'),
        role: 'system',
        content: JSON.stringify({ type: 'chart', config: chartConfigJson }),
        timestamp: Date.now(),
      };
      set(state => ({ messages: [...state.messages, message] }));
    },

    addAnsiOutput(text: string) {
      const message: Message = {
        id: nextId('ansi'),
        role: 'system',
        content: JSON.stringify({ type: 'ansi', text }),
        timestamp: Date.now(),
      };
      set(state => ({ messages: [...state.messages, message] }));
    },

    addTabs(tabsJson: string) {
      const message: Message = {
        id: nextId('tabs'),
        role: 'system',
        content: JSON.stringify({ type: 'tabs', data: tabsJson }),
        timestamp: Date.now(),
      };
      set(state => ({ messages: [...state.messages, message] }));
    },

    addTimeline(itemsJson: string) {
      const message: Message = {
        id: nextId('timeline'),
        role: 'system',
        content: JSON.stringify({ type: 'timeline', data: itemsJson }),
        timestamp: Date.now(),
      };
      set(state => ({ messages: [...state.messages, message] }));
    },

    addProgressBar(percent: number, type: string) {
      const message: Message = {
        id: nextId('progress'),
        role: 'system',
        content: JSON.stringify({ type: 'progressBar', percent, barType: type }),
        timestamp: Date.now(),
      };
      set(state => ({ messages: [...state.messages, message] }));
    },

    addJiraCard(cardJson: string) {
      const message: Message = {
        id: nextId('jira'),
        role: 'system',
        content: JSON.stringify({ type: 'jiraCard', data: cardJson }),
        timestamp: Date.now(),
      };
      set(state => ({ messages: [...state.messages, message] }));
    },

    addSonarBadge(badgeJson: string) {
      const message: Message = {
        id: nextId('sonar'),
        role: 'system',
        content: JSON.stringify({ type: 'sonarBadge', data: badgeJson }),
        timestamp: Date.now(),
      };
      set(state => ({ messages: [...state.messages, message] }));
    },

    showSkeleton() {
      set({ showingSkeleton: true });
    },

    hideSkeleton() {
      set({ showingSkeleton: false });
    },

    showToast(message: string, type: string, durationMs: number) {
      const toast: Toast = {
        id: nextId('toast'),
        message,
        type: type as ToastType,
        durationMs,
      };
      set(state => ({ toasts: [...state.toasts, toast] }));

      // Auto-dismiss after duration
      if (durationMs > 0) {
        setTimeout(() => {
          get().dismissToast(toast.id);
        }, durationMs);
      }
    },

    dismissToast(id: string) {
      set(state => ({
        toasts: state.toasts.filter(t => t.id !== id),
      }));
    },

    receiveMentionResults(results: MentionSearchResult[]) {
      set({ mentionResults: results });
    },

    sendMessage(text: string, mentions: Mention[]) {
      // Add user message to local state
      get().addMessage('user', text);

      // Dispatch to Kotlin via bridge
      // (Import kotlinBridge lazily to avoid circular deps)
      import('../bridge/jcef-bridge').then(({ kotlinBridge }) => {
        if (mentions.length > 0) {
          kotlinBridge.sendMessageWithMentions(text, JSON.stringify(mentions));
        } else {
          kotlinBridge.sendMessage(text);
        }
      });
    },
  }));
  ```

- [ ] **4.2 Create `stores/settingsStore.ts`**

  Create `agent/webview/src/stores/settingsStore.ts` — per-type visualization settings with defaults:

  ```typescript
  import { create } from 'zustand';
  import type { VisualizationType, VisualizationConfig } from '../bridge/types';

  /**
   * Default visualization settings per type.
   * These match the spec's per-type settings structure.
   */
  const defaultVisualizationConfig: VisualizationConfig = {
    enabled: true,
    autoRender: true,
    defaultExpanded: false,
    maxHeight: 300,
  };

  const defaultVisualizations: Record<VisualizationType, VisualizationConfig> = {
    mermaid: { ...defaultVisualizationConfig },
    chart: { ...defaultVisualizationConfig },
    flow: { ...defaultVisualizationConfig },
    math: { ...defaultVisualizationConfig, defaultExpanded: true, maxHeight: 0 },
    diff: { ...defaultVisualizationConfig, defaultExpanded: true, maxHeight: 400 },
    interactiveHtml: { ...defaultVisualizationConfig, maxHeight: 500 },
  };

  interface SettingsState {
    visualizations: Record<VisualizationType, VisualizationConfig>;

    /** Update settings for a specific visualization type */
    updateVisualization(type: VisualizationType, config: Partial<VisualizationConfig>): void;

    /** Reset a visualization type to defaults */
    resetVisualization(type: VisualizationType): void;

    /** Reset all visualization settings to defaults */
    resetAll(): void;
  }

  export const useSettingsStore = create<SettingsState>((set) => ({
    visualizations: { ...defaultVisualizations },

    updateVisualization(type: VisualizationType, config: Partial<VisualizationConfig>) {
      set(state => ({
        visualizations: {
          ...state.visualizations,
          [type]: { ...state.visualizations[type], ...config },
        },
      }));
    },

    resetVisualization(type: VisualizationType) {
      set(state => ({
        visualizations: {
          ...state.visualizations,
          [type]: { ...defaultVisualizationConfig },
        },
      }));
    },

    resetAll() {
      set({ visualizations: { ...defaultVisualizations } });
    },
  }));
  ```

- [ ] **4.3 Verify stores work**

  ```bash
  cd agent/webview && npm run dev
  ```

  In the browser console:
  1. Run `__mock.simulateAgentResponse()` — check that `useChatStore.getState().messages` accumulates messages
  2. Run `__mock.simulatePlan()` — check that `useChatStore.getState().plan` is populated with steps
  3. Run `__mock.simulateToolCalls()` — check that `useChatStore.getState().activeToolCalls` has entries
  4. Verify `useSettingsStore.getState().visualizations.mermaid.enabled === true`
  5. Verify `useThemeStore.getState().isDark === true` (from default theme applied in App.tsx)
  6. Confirm no TypeScript errors in the terminal

- [ ] **4.4 Commit**

  ```
  feat(agent): implement Zustand stores for chat state, settings, and theme management
  ```

---

## Phase 2: Core Chat Components

### Task 5: Message Components

**Objective:** Install prompt-kit chat components, build `MessageCard` and `MessageList` with TanStack Virtual for 100+ message performance, and wire auto-scroll behavior with a "New messages" badge.

**Estimated time:** 25-30 minutes

#### Steps

- [ ] **5.1 Install prompt-kit ChatContainer, Message, and ScrollButton**

  Run from `agent/webview/`:

  ```bash
  npx shadcn add "https://prompt-kit.com/c/chat-container.json"
  npx shadcn add "https://prompt-kit.com/c/message.json"
  npx shadcn add "https://prompt-kit.com/c/scroll-button.json"
  ```

  These copy component source files into `src/components/ui/` (shadcn convention). Verify the files exist after install.

- [ ] **5.2 Customize prompt-kit components with Elevated IDE styling**

  Edit the installed prompt-kit components to use IDE theme CSS variables instead of shadcn defaults:

  - In `ChatContainer`: replace any hardcoded background with `bg-[var(--bg)]`, border with `border-[var(--border)]`
  - In `Message`: replace card backgrounds — user messages use `bg-[var(--user-bg)]`, agent messages use `bg-transparent`
  - In `ScrollButton`: use `border-[var(--border)] bg-[var(--toolbar-bg)] text-[var(--fg)]` and accent hover
  - Set `font-family: var(--font-mono)` on any code-related text
  - Ensure all colors reference `var(--*)` CSS variables from `themeStore`

- [ ] **5.3 Create `components/chat/MessageCard.tsx`**

  Create `agent/webview/src/components/chat/MessageCard.tsx` — renders a single message (user or agent). References `Message` type from `bridge/types.ts`. Does NOT re-import store types.

  ```tsx
  import { memo } from 'react';
  import type { Message } from '@/bridge/types';

  interface MessageCardProps {
    message: Message;
    isStreaming?: boolean;
    streamText?: string;
  }

  export const MessageCard = memo(function MessageCard({
    message,
    isStreaming = false,
    streamText,
  }: MessageCardProps) {
    const isUser = message.role === 'user';
    const content = isStreaming ? (streamText ?? message.content) : message.content;

    // System messages (status, thinking, diffs, rich content) are handled
    // by dedicated renderers — MessageCard only renders user/agent text
    if (message.role === 'system') {
      return null; // SystemMessageRenderer handles these (Task 8+)
    }

    return (
      <div
        className={`
          group relative flex w-full
          ${isUser ? 'justify-end' : 'justify-start'}
          animate-[message-enter_220ms_ease-out_both]
        `}
      >
        <div
          className={`
            relative max-w-[85%] rounded-lg px-4 py-3
            ${isUser
              ? 'bg-[var(--user-bg)] text-[var(--fg)]'
              : 'bg-transparent text-[var(--fg)]'
            }
          `}
        >
          {/* Agent avatar + model tag */}
          {!isUser && (
            <div className="mb-1.5 flex items-center gap-2">
              <div className="flex h-5 w-5 items-center justify-center rounded-full bg-[var(--accent,#6366f1)] text-[10px] font-bold text-white">
                A
              </div>
              <span className="text-[11px] font-medium text-[var(--fg-muted)]">
                Agent
              </span>
            </div>
          )}

          {/* Message content area — plain text for now, MarkdownRenderer in Task 6 */}
          <div className="whitespace-pre-wrap break-words text-[13px] leading-relaxed">
            {content}
          </div>

          {/* Streaming cursor */}
          {isStreaming && (
            <span className="inline-block h-4 w-[2px] translate-y-[2px] bg-[var(--fg)] animate-[cursor-blink_530ms_step-end_infinite]" />
          )}

          {/* Timestamp on hover */}
          <div className="mt-1 opacity-0 transition-opacity duration-200 group-hover:opacity-100">
            <span className="text-[10px] text-[var(--fg-muted)]">
              {new Date(message.timestamp).toLocaleTimeString([], {
                hour: '2-digit',
                minute: '2-digit',
              })}
            </span>
          </div>
        </div>
      </div>
    );
  });
  ```

  Add the message-enter and cursor-blink keyframes to `src/index.css`:

  ```css
  @keyframes message-enter {
    from {
      opacity: 0;
      transform: translateY(8px);
    }
    to {
      opacity: 1;
      transform: translateY(0);
    }
  }

  @keyframes cursor-blink {
    0%, 100% { opacity: 1; }
    50% { opacity: 0; }
  }

  @media (prefers-reduced-motion: reduce) {
    *, *::before, *::after {
      animation-duration: 0.01ms !important;
      transition-duration: 0.01ms !important;
    }
  }
  ```

- [ ] **5.4 Create `hooks/useAutoScroll.ts`**

  Create `agent/webview/src/hooks/useAutoScroll.ts` — tracks scroll position, auto-scrolls when within 100px of bottom, exposes a "has new messages" flag when scrolled up:

  ```typescript
  import { useCallback, useEffect, useRef, useState } from 'react';

  interface UseAutoScrollOptions {
    /** Pixel threshold from bottom to consider "at bottom" */
    threshold?: number;
    /** Dependency that triggers scroll check (e.g., message count) */
    dependency?: unknown;
  }

  interface UseAutoScrollReturn {
    /** Ref to attach to the scrollable container */
    containerRef: React.RefObject<HTMLDivElement | null>;
    /** Whether user is scrolled away from bottom */
    isScrolledUp: boolean;
    /** Whether there are new messages while scrolled up */
    hasNewMessages: boolean;
    /** Programmatically scroll to bottom */
    scrollToBottom: (smooth?: boolean) => void;
    /** Call when new content arrives */
    onContentUpdate: () => void;
  }

  export function useAutoScroll({
    threshold = 100,
    dependency,
  }: UseAutoScrollOptions = {}): UseAutoScrollReturn {
    const containerRef = useRef<HTMLDivElement | null>(null);
    const [isScrolledUp, setIsScrolledUp] = useState(false);
    const [hasNewMessages, setHasNewMessages] = useState(false);
    const isAtBottomRef = useRef(true);

    const checkIsAtBottom = useCallback(() => {
      const el = containerRef.current;
      if (!el) return true;
      const distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
      return distanceFromBottom <= threshold;
    }, [threshold]);

    const scrollToBottom = useCallback((smooth = true) => {
      const el = containerRef.current;
      if (!el) return;
      el.scrollTo({
        top: el.scrollHeight,
        behavior: smooth ? 'smooth' : 'instant',
      });
      setIsScrolledUp(false);
      setHasNewMessages(false);
      isAtBottomRef.current = true;
    }, []);

    const onContentUpdate = useCallback(() => {
      if (isAtBottomRef.current) {
        // User is at bottom — auto-scroll
        requestAnimationFrame(() => scrollToBottom(true));
      } else {
        // User is scrolled up — show badge
        setHasNewMessages(true);
      }
    }, [scrollToBottom]);

    // Handle scroll events
    useEffect(() => {
      const el = containerRef.current;
      if (!el) return;

      const handleScroll = () => {
        const atBottom = checkIsAtBottom();
        isAtBottomRef.current = atBottom;
        setIsScrolledUp(!atBottom);
        if (atBottom) {
          setHasNewMessages(false);
        }
      };

      el.addEventListener('scroll', handleScroll, { passive: true });
      return () => el.removeEventListener('scroll', handleScroll);
    }, [checkIsAtBottom]);

    // Auto-scroll when dependency changes (e.g., message count)
    useEffect(() => {
      onContentUpdate();
    }, [dependency, onContentUpdate]);

    return {
      containerRef,
      isScrolledUp,
      hasNewMessages,
      scrollToBottom,
      onContentUpdate,
    };
  }
  ```

- [ ] **5.5 Create `hooks/useVirtualScroll.ts`**

  Create `agent/webview/src/hooks/useVirtualScroll.ts` — TanStack Virtual wrapper with dynamic row heights and overscan:

  ```typescript
  import { useVirtualizer } from '@tanstack/react-virtual';
  import { useCallback, useRef } from 'react';

  interface UseVirtualScrollOptions {
    /** Total number of items */
    count: number;
    /** Estimated item size in pixels */
    estimateSize?: number;
    /** Number of items to render outside the visible area */
    overscan?: number;
    /** Whether virtual scrolling is enabled (disabled for < 100 messages) */
    enabled?: boolean;
  }

  export function useVirtualScroll({
    count,
    estimateSize = 80,
    overscan = 5,
    enabled = true,
  }: UseVirtualScrollOptions) {
    const parentRef = useRef<HTMLDivElement | null>(null);

    const virtualizer = useVirtualizer({
      count,
      getScrollElement: () => parentRef.current,
      estimateSize: () => estimateSize,
      overscan,
      enabled,
    });

    const measureElement = useCallback(
      (node: HTMLElement | null) => {
        if (node) {
          const index = Number(node.dataset.index);
          if (!isNaN(index)) {
            virtualizer.measureElement(node);
          }
        }
      },
      [virtualizer],
    );

    return {
      parentRef,
      virtualizer,
      measureElement,
      virtualItems: virtualizer.getVirtualItems(),
      totalSize: virtualizer.getTotalSize(),
    };
  }
  ```

- [ ] **5.6 Create `components/chat/MessageList.tsx`**

  Create `agent/webview/src/components/chat/MessageList.tsx` — maps messages from `chatStore`, integrates TanStack Virtual for 100+ messages, includes the active streaming message and scroll button:

  ```tsx
  import { useChatStore } from '@/stores/chatStore';
  import { MessageCard } from './MessageCard';
  import { useAutoScroll } from '@/hooks/useAutoScroll';
  import { useVirtualScroll } from '@/hooks/useVirtualScroll';

  /** Threshold above which virtual scrolling activates */
  const VIRTUAL_SCROLL_THRESHOLD = 100;

  export function MessageList() {
    const messages = useChatStore(s => s.messages);
    const activeStream = useChatStore(s => s.activeStream);
    const messageCount = messages.length + (activeStream ? 1 : 0);

    const useVirtual = messageCount >= VIRTUAL_SCROLL_THRESHOLD;

    const {
      containerRef,
      isScrolledUp,
      hasNewMessages,
      scrollToBottom,
    } = useAutoScroll({ dependency: messageCount });

    const {
      parentRef,
      virtualizer,
      virtualItems,
      totalSize,
      measureElement,
    } = useVirtualScroll({
      count: messageCount,
      enabled: useVirtual,
    });

    // Merge refs: containerRef (auto-scroll) + parentRef (virtualizer)
    const setRef = (el: HTMLDivElement | null) => {
      (containerRef as React.MutableRefObject<HTMLDivElement | null>).current = el;
      (parentRef as React.MutableRefObject<HTMLDivElement | null>).current = el;
    };

    // Build the combined list: finalized messages + active stream placeholder
    const allItems = [...messages];
    const streamPlaceholder = activeStream
      ? {
          id: '__streaming__',
          role: 'agent' as const,
          content: activeStream.text,
          timestamp: Date.now(),
        }
      : null;

    const renderMessage = (index: number) => {
      if (index < messages.length) {
        return (
          <MessageCard
            key={messages[index].id}
            message={messages[index]}
          />
        );
      }
      // Active stream message
      if (streamPlaceholder) {
        return (
          <MessageCard
            key="__streaming__"
            message={streamPlaceholder}
            isStreaming={activeStream?.isStreaming ?? false}
            streamText={activeStream?.text}
          />
        );
      }
      return null;
    };

    return (
      <div className="relative flex-1 overflow-hidden">
        <div
          ref={setRef}
          className="h-full overflow-y-auto scroll-smooth px-4 py-3"
        >
          {useVirtual ? (
            /* ── Virtual scrolling for 100+ messages ── */
            <div
              style={{
                height: `${totalSize}px`,
                width: '100%',
                position: 'relative',
              }}
            >
              {virtualItems.map(virtualItem => (
                <div
                  key={virtualItem.key}
                  data-index={virtualItem.index}
                  ref={measureElement}
                  style={{
                    position: 'absolute',
                    top: 0,
                    left: 0,
                    width: '100%',
                    transform: `translateY(${virtualItem.start}px)`,
                  }}
                >
                  <div className="py-1.5">
                    {renderMessage(virtualItem.index)}
                  </div>
                </div>
              ))}
            </div>
          ) : (
            /* ── Standard rendering for < 100 messages ── */
            <div className="flex flex-col gap-3">
              {allItems.map((msg, i) => (
                <div
                  key={msg.id}
                  style={{ animationDelay: `${Math.min(i * 40, 200)}ms` }}
                >
                  <MessageCard message={msg} />
                </div>
              ))}
              {streamPlaceholder && (
                <MessageCard
                  key="__streaming__"
                  message={streamPlaceholder}
                  isStreaming={activeStream?.isStreaming ?? false}
                  streamText={activeStream?.text}
                />
              )}
            </div>
          )}
        </div>

        {/* Scroll-to-bottom button */}
        {isScrolledUp && (
          <button
            onClick={() => scrollToBottom(true)}
            className="
              absolute bottom-4 left-1/2 -translate-x-1/2
              flex items-center gap-2 rounded-full
              border border-[var(--border)] bg-[var(--toolbar-bg)]
              px-3 py-1.5 text-[12px] text-[var(--fg)]
              shadow-md backdrop-blur-sm
              transition-all duration-200
              hover:border-[var(--accent,#6366f1)] hover:shadow-lg
              animate-[message-enter_200ms_ease_both]
            "
          >
            <svg
              width="14"
              height="14"
              viewBox="0 0 16 16"
              fill="none"
              className="text-[var(--fg-muted)]"
            >
              <path
                d="M8 3v10m0 0l-4-4m4 4l4-4"
                stroke="currentColor"
                strokeWidth="1.5"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
            {hasNewMessages ? 'New messages' : 'Scroll to bottom'}
          </button>
        )}
      </div>
    );
  }
  ```

- [ ] **5.7 Update `App.tsx` to render `MessageList`**

  Replace the placeholder in `agent/webview/src/App.tsx` with the message list:

  ```tsx
  import { MessageList } from '@/components/chat/MessageList';

  function App() {
    return (
      <div className="flex h-screen flex-col bg-[var(--bg,#1e1e1e)] text-[var(--fg,#cccccc)]">
        {/* Message list takes all available space */}
        <MessageList />

        {/* Input bar placeholder — replaced in Task 7 */}
        <div className="border-t border-[var(--border,#333)] px-4 py-3">
          <div className="rounded-lg border border-[var(--input-border,#444)] bg-[var(--input-bg,#2a2a2a)] px-3 py-2 text-[13px] text-[var(--fg-muted,#888)]">
            Ask anything...
          </div>
        </div>
      </div>
    );
  }

  export default App;
  ```

- [ ] **5.8 Verify message rendering**

  ```bash
  cd agent/webview && npm run dev
  ```

  In the browser console:
  1. Run `__mock.simulateAgentResponse()` — verify messages render with correct alignment (user right, agent left)
  2. Verify agent messages show avatar and "Agent" model tag
  3. Verify streaming messages show blinking cursor
  4. Scroll up during streaming — verify "New messages" badge appears
  5. Click the scroll button — verify smooth scroll to bottom
  6. Hover over a message — verify timestamp appears
  7. Confirm no TypeScript errors in the terminal

- [ ] **5.9 Commit**

  ```
  feat(agent): implement MessageCard, MessageList with virtual scroll and auto-scroll hooks
  ```

### Task 6: Streaming Pipeline

**Objective:** Build the streaming token handler, markdown renderer with partial-parse support for open code fences, blinking cursor, and loading state components. Wire streaming to the mock bridge for dev testing.

**Estimated time:** 20-25 minutes

#### Steps

- [ ] **6.1 Create `hooks/useStreaming.ts`**

  Create `agent/webview/src/hooks/useStreaming.ts` — handles token accumulation with `requestAnimationFrame` gating to ensure only the active streaming `MessageCard` re-renders:

  ```typescript
  import { useCallback, useEffect, useRef, useState } from 'react';
  import { useChatStore } from '@/stores/chatStore';

  interface UseStreamingReturn {
    /** The current accumulated stream text */
    streamText: string;
    /** Whether a stream is currently active */
    isStreaming: boolean;
    /** Whether the stream has content */
    hasContent: boolean;
  }

  /**
   * Hook that subscribes to the active stream from chatStore.
   * Uses requestAnimationFrame gating to prevent > 60fps re-renders
   * during high-throughput token streaming.
   */
  export function useStreaming(): UseStreamingReturn {
    const activeStream = useChatStore(s => s.activeStream);
    const [displayText, setDisplayText] = useState('');
    const rafRef = useRef<number | null>(null);
    const latestTextRef = useRef('');

    // Track the latest text without triggering re-renders
    useEffect(() => {
      latestTextRef.current = activeStream?.text ?? '';
    }, [activeStream?.text]);

    // RAF-gated display updates
    useEffect(() => {
      if (!activeStream?.isStreaming) {
        // Not streaming — show final text immediately
        if (rafRef.current) {
          cancelAnimationFrame(rafRef.current);
          rafRef.current = null;
        }
        setDisplayText(activeStream?.text ?? '');
        return;
      }

      // Streaming — gate updates to animation frames
      const tick = () => {
        setDisplayText(latestTextRef.current);
        rafRef.current = requestAnimationFrame(tick);
      };
      rafRef.current = requestAnimationFrame(tick);

      return () => {
        if (rafRef.current) {
          cancelAnimationFrame(rafRef.current);
          rafRef.current = null;
        }
      };
    }, [activeStream?.isStreaming, activeStream?.text]);

    return {
      streamText: displayText,
      isStreaming: activeStream?.isStreaming ?? false,
      hasContent: displayText.length > 0,
    };
  }
  ```

- [ ] **6.2 Create `components/markdown/MarkdownRenderer.tsx`**

  Create `agent/webview/src/components/markdown/MarkdownRenderer.tsx` — wraps `react-markdown` with `remark-gfm`, `rehype-raw`, and DOMPurify sanitization. Handles open code fences during streaming:

  ```tsx
  import { memo, useMemo } from 'react';
  import ReactMarkdown from 'react-markdown';
  import remarkGfm from 'remark-gfm';
  import rehypeRaw from 'rehype-raw';
  import DOMPurify from 'dompurify';

  interface MarkdownRendererProps {
    content: string;
    isStreaming?: boolean;
  }

  /**
   * Detects if the markdown has an unclosed code fence.
   * During streaming, an open ``` without a closing ``` means
   * the code block is still being written.
   */
  function hasOpenCodeFence(text: string): boolean {
    const fencePattern = /^```/gm;
    const matches = text.match(fencePattern);
    if (!matches) return false;
    return matches.length % 2 !== 0;
  }

  /**
   * Closes any open code fence so react-markdown can parse it.
   * Appends a closing ``` if an unclosed fence is detected.
   */
  function closeOpenFences(text: string): string {
    if (hasOpenCodeFence(text)) {
      return text + '\n```';
    }
    return text;
  }

  export const MarkdownRenderer = memo(function MarkdownRenderer({
    content,
    isStreaming = false,
  }: MarkdownRendererProps) {
    const sanitizedContent = useMemo(() => {
      const processedContent = isStreaming ? closeOpenFences(content) : content;
      return DOMPurify.sanitize(processedContent);
    }, [content, isStreaming]);

    return (
      <div className="markdown-body text-[13px] leading-relaxed">
        <ReactMarkdown
          remarkPlugins={[remarkGfm]}
          rehypePlugins={[rehypeRaw]}
          components={{
            // Inline code
            code({ className, children, ...props }) {
              const isBlock = className?.startsWith('language-');
              if (isBlock) {
                // Block code — will be replaced by Shiki CodeBlock in later task
                const language = className?.replace('language-', '') ?? '';
                return (
                  <div className="relative my-2 rounded-md border border-[var(--border)] bg-[var(--code-bg)] overflow-hidden">
                    <div className="flex items-center justify-between border-b border-[var(--border)] px-3 py-1">
                      <span className="text-[10px] font-medium uppercase text-[var(--fg-muted)]">
                        {language || 'code'}
                      </span>
                    </div>
                    <pre className="overflow-x-auto p-3">
                      <code
                        className={`font-[var(--font-mono,'JetBrains_Mono',monospace)] text-[12px] ${className ?? ''}`}
                        {...props}
                      >
                        {children}
                        {/* Skeleton placeholder for open fence during streaming */}
                        {isStreaming && hasOpenCodeFence(content) && (
                          <span className="block mt-1 h-3 w-24 animate-pulse rounded bg-[var(--fg-muted)]/20" />
                        )}
                      </code>
                    </pre>
                  </div>
                );
              }
              // Inline code
              return (
                <code
                  className="rounded bg-[var(--code-bg)] px-1 py-0.5 font-[var(--font-mono,'JetBrains_Mono',monospace)] text-[12px]"
                  {...props}
                >
                  {children}
                </code>
              );
            },

            // Links
            a({ href, children, ...props }) {
              return (
                <a
                  href={href}
                  className="text-[var(--link)] underline decoration-[var(--link)]/30 hover:decoration-[var(--link)]"
                  onClick={(e) => {
                    e.preventDefault();
                    if (href) {
                      // Navigate via bridge if it's a file path
                      window._navigateToFile?.(href);
                    }
                  }}
                  {...props}
                >
                  {children}
                </a>
              );
            },

            // Tables
            table({ children, ...props }) {
              return (
                <div className="my-2 overflow-x-auto rounded border border-[var(--border)]">
                  <table className="w-full text-[12px]" {...props}>
                    {children}
                  </table>
                </div>
              );
            },

            th({ children, ...props }) {
              return (
                <th
                  className="border-b border-[var(--border)] bg-[var(--toolbar-bg)] px-3 py-1.5 text-left text-[11px] font-semibold text-[var(--fg-secondary)]"
                  {...props}
                >
                  {children}
                </th>
              );
            },

            td({ children, ...props }) {
              return (
                <td
                  className="border-b border-[var(--divider-subtle)] px-3 py-1.5"
                  {...props}
                >
                  {children}
                </td>
              );
            },

            // Blockquotes
            blockquote({ children, ...props }) {
              return (
                <blockquote
                  className="my-2 border-l-2 border-[var(--accent,#6366f1)] pl-3 text-[var(--fg-secondary)] italic"
                  {...props}
                >
                  {children}
                </blockquote>
              );
            },

            // Horizontal rule
            hr(props) {
              return (
                <hr
                  className="my-3 border-[var(--divider-subtle)]"
                  {...props}
                />
              );
            },

            // Lists
            ul({ children, ...props }) {
              return (
                <ul className="my-1 ml-4 list-disc space-y-0.5" {...props}>
                  {children}
                </ul>
              );
            },

            ol({ children, ...props }) {
              return (
                <ol className="my-1 ml-4 list-decimal space-y-0.5" {...props}>
                  {children}
                </ol>
              );
            },

            // Paragraphs — tighter spacing
            p({ children, ...props }) {
              return (
                <p className="my-1.5" {...props}>
                  {children}
                </p>
              );
            },
          }}
        >
          {sanitizedContent}
        </ReactMarkdown>
      </div>
    );
  });
  ```

- [ ] **6.3 Add streaming cursor CSS to `index.css`**

  The cursor-blink keyframe was added in Task 5.3. Verify it exists in `src/index.css`. If not, add:

  ```css
  @keyframes cursor-blink {
    0%, 100% { opacity: 1; }
    50% { opacity: 0; }
  }
  ```

  Also add the streaming-specific skeleton shimmer:

  ```css
  @keyframes shimmer {
    0% { background-position: -200% 0; }
    100% { background-position: 200% 0; }
  }
  ```

- [ ] **6.4 Install prompt-kit TextShimmer and Loader**

  Run from `agent/webview/`:

  ```bash
  npx shadcn add "https://prompt-kit.com/c/text-shimmer.json"
  npx shadcn add "https://prompt-kit.com/c/loader.json"
  ```

  Customize the installed components:
  - `TextShimmer`: use `text-[var(--fg-muted)]` as base, shimmer gradient uses `var(--accent,#6366f1)`
  - `Loader`: use `bg-[var(--accent,#6366f1)]` for the animated dots

- [ ] **6.5 Update `MessageCard` to use `MarkdownRenderer`**

  Edit `agent/webview/src/components/chat/MessageCard.tsx` to replace the plain-text content div with `MarkdownRenderer`:

  ```tsx
  // Add import at top:
  import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer';

  // Replace the plain-text content div:
  {/* Message content area */}
  <MarkdownRenderer
    content={content}
    isStreaming={isStreaming}
  />
  ```

  Remove the old `<div className="whitespace-pre-wrap ...">` block that rendered plain text.

- [ ] **6.6 Wire streaming to mock bridge**

  Update the mock bridge in `agent/webview/src/bridge/mock-bridge.ts` to expose a `simulateStreaming()` function that sends tokens one by one with realistic timing:

  ```typescript
  // Add to the mock bridge's __mock global:
  async simulateStreaming() {
    const { useChatStore } = await import('../stores/chatStore');
    const store = useChatStore.getState();

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

    // Stream tokens with 20-50ms delay per token
    const tokens = sampleText.split(/(?<=\s)/); // Split on whitespace boundaries
    for (const token of tokens) {
      store.appendToken(token);
      await new Promise(r => setTimeout(r, 20 + Math.random() * 30));
    }
    store.endStream();
  }
  ```

- [ ] **6.7 Verify streaming pipeline**

  ```bash
  cd agent/webview && npm run dev
  ```

  In the browser console:
  1. Run `__mock.simulateStreaming()` — verify tokens appear incrementally
  2. Verify markdown renders correctly: headings, bold, inline code, code blocks, tables, blockquotes
  3. Verify blinking cursor appears at the end of streaming text
  4. Verify code blocks have language label and proper styling
  5. Verify cursor disappears when stream ends
  6. Verify final message is added to the messages array (check `useChatStore.getState().messages`)
  7. Confirm no TypeScript errors

- [ ] **6.8 Commit**

  ```
  feat(agent): implement streaming pipeline with MarkdownRenderer, loading states, and RAF gating
  ```

### Task 7: Input Bar

**Objective:** Build the contextual input bar with progressive disclosure: clean text field by default, `@` mention autocomplete, model selector, mode toggle, and action toolbar. Wire all interactions to `chatStore` and the JCEF bridge.

**Estimated time:** 25-30 minutes

#### Steps

- [ ] **7.1 Install prompt-kit PromptInput**

  Run from `agent/webview/`:

  ```bash
  npx shadcn add "https://prompt-kit.com/c/prompt-input.json"
  ```

  Customize the installed component:
  - Background: `bg-[var(--input-bg)]`
  - Border: `border-[var(--input-border)]`
  - Text: `text-[var(--fg)]` with `text-[13px]`
  - Placeholder: `text-[var(--fg-muted)]`
  - Focus ring: `focus-within:border-[var(--accent,#6366f1)]`

- [ ] **7.2 Create `components/input/ContextChip.tsx`**

  Create `agent/webview/src/components/input/ContextChip.tsx` — pills showing attached files/mentions with remove button:

  ```tsx
  import { memo } from 'react';
  import type { Mention } from '@/bridge/types';

  interface ContextChipProps {
    mention: Mention;
    onRemove: () => void;
  }

  const typeIcons: Record<string, string> = {
    file: '\u{1F4C4}',    // Will be replaced by SVG icons later
    folder: '\u{1F4C1}',
    symbol: '#',
    tool: '\u{1F527}',
    skill: '\u{2728}',
  };

  export const ContextChip = memo(function ContextChip({
    mention,
    onRemove,
  }: ContextChipProps) {
    return (
      <span className="
        inline-flex items-center gap-1 rounded-md
        border border-[var(--chip-border)] bg-[var(--chip-bg)]
        px-1.5 py-0.5 text-[11px] text-[var(--fg-secondary)]
        transition-colors duration-150
        hover:border-[var(--accent,#6366f1)]
      ">
        <span className="text-[10px] opacity-60">
          {typeIcons[mention.type] ?? '@'}
        </span>
        <span className="max-w-[120px] truncate">
          {mention.label}
        </span>
        <button
          onClick={onRemove}
          className="
            ml-0.5 flex h-3.5 w-3.5 items-center justify-center rounded-sm
            text-[var(--fg-muted)] hover:bg-[var(--hover-overlay-strong)] hover:text-[var(--fg)]
          "
          aria-label={`Remove ${mention.label}`}
        >
          <svg width="8" height="8" viewBox="0 0 8 8" fill="none">
            <path d="M1 1l6 6M7 1l-6 6" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
          </svg>
        </button>
      </span>
    );
  });
  ```

- [ ] **7.3 Create `components/input/MentionAutocomplete.tsx`**

  Create `agent/webview/src/components/input/MentionAutocomplete.tsx` — dropdown populated via `bridge._searchMentions()`, results received via `receiveMentionResults()` in `chatStore`:

  ```tsx
  import { memo, useCallback, useEffect, useRef, useState } from 'react';
  import { useChatStore } from '@/stores/chatStore';
  import type { MentionSearchResult, MentionType } from '@/bridge/types';

  interface MentionAutocompleteProps {
    query: string;
    onSelect: (result: MentionSearchResult) => void;
    onDismiss: () => void;
  }

  const categoryLabels: Record<MentionType, string> = {
    file: 'Files',
    folder: 'Folders',
    symbol: 'Symbols',
    tool: 'Tools',
    skill: 'Skills',
  };

  const categoryIcons: Record<MentionType, string> = {
    file: '\u{1F4C4}',
    folder: '\u{1F4C1}',
    symbol: '#',
    tool: '\u{1F527}',
    skill: '\u{2728}',
  };

  export const MentionAutocomplete = memo(function MentionAutocomplete({
    query,
    onSelect,
    onDismiss,
  }: MentionAutocompleteProps) {
    const mentionResults = useChatStore(s => s.mentionResults);
    const [selectedIndex, setSelectedIndex] = useState(0);
    const listRef = useRef<HTMLDivElement>(null);

    // Trigger search via bridge when query changes
    useEffect(() => {
      if (query.length === 0) {
        // Show categories when no query
        window._searchMentions?.('categories:');
      } else {
        // Search with type prefix detection
        const colonIndex = query.indexOf(':');
        if (colonIndex > 0) {
          const type = query.slice(0, colonIndex);
          const searchQuery = query.slice(colonIndex + 1);
          window._searchMentions?.(`${type}:${searchQuery}`);
        } else {
          window._searchMentions?.(`file:${query}`);
        }
      }
    }, [query]);

    // Reset selection when results change
    useEffect(() => {
      setSelectedIndex(0);
    }, [mentionResults]);

    // Keyboard navigation
    const handleKeyDown = useCallback(
      (e: KeyboardEvent) => {
        switch (e.key) {
          case 'ArrowDown':
            e.preventDefault();
            setSelectedIndex(i => Math.min(i + 1, mentionResults.length - 1));
            break;
          case 'ArrowUp':
            e.preventDefault();
            setSelectedIndex(i => Math.max(i - 1, 0));
            break;
          case 'Enter':
            e.preventDefault();
            if (mentionResults[selectedIndex]) {
              onSelect(mentionResults[selectedIndex]);
            }
            break;
          case 'Escape':
            e.preventDefault();
            onDismiss();
            break;
        }
      },
      [mentionResults, selectedIndex, onSelect, onDismiss],
    );

    useEffect(() => {
      document.addEventListener('keydown', handleKeyDown);
      return () => document.removeEventListener('keydown', handleKeyDown);
    }, [handleKeyDown]);

    // Scroll selected item into view
    useEffect(() => {
      const list = listRef.current;
      if (!list) return;
      const selected = list.children[selectedIndex] as HTMLElement | undefined;
      selected?.scrollIntoView({ block: 'nearest' });
    }, [selectedIndex]);

    if (mentionResults.length === 0 && query.length > 0) {
      return (
        <div className="
          absolute bottom-full left-0 mb-1 w-72
          rounded-lg border border-[var(--border)] bg-[var(--bg)]
          p-3 text-[12px] text-[var(--fg-muted)] shadow-lg
        ">
          No results for "{query}"
        </div>
      );
    }

    // Group results by type
    const grouped = mentionResults.reduce<Record<string, MentionSearchResult[]>>(
      (acc, result) => {
        const key = result.type;
        if (!acc[key]) acc[key] = [];
        acc[key].push(result);
        return acc;
      },
      {},
    );

    let flatIndex = 0;

    return (
      <div className="
        absolute bottom-full left-0 z-50 mb-1 w-80
        max-h-64 overflow-y-auto rounded-lg
        border border-[var(--border)] bg-[var(--bg)]
        shadow-lg
        animate-[message-enter_150ms_ease-out_both]
      ">
        <div ref={listRef}>
          {Object.entries(grouped).map(([type, results]) => (
            <div key={type}>
              {/* Category header */}
              <div className="sticky top-0 flex items-center gap-1.5 bg-[var(--bg)] px-3 py-1.5 text-[10px] font-semibold uppercase tracking-wider text-[var(--fg-muted)]">
                <span>{categoryIcons[type as MentionType] ?? '@'}</span>
                <span>{categoryLabels[type as MentionType] ?? type}</span>
              </div>
              {/* Results */}
              {results.map(result => {
                const thisIndex = flatIndex++;
                return (
                  <button
                    key={`${result.type}-${result.label}-${thisIndex}`}
                    className={`
                      flex w-full items-center gap-2 px-3 py-1.5 text-left text-[12px]
                      transition-colors duration-100
                      ${thisIndex === selectedIndex
                        ? 'bg-[var(--hover-overlay-strong)] text-[var(--fg)]'
                        : 'text-[var(--fg-secondary)] hover:bg-[var(--hover-overlay)]'
                      }
                    `}
                    onClick={() => onSelect(result)}
                    onMouseEnter={() => setSelectedIndex(thisIndex)}
                  >
                    <span className="flex-1 truncate font-medium">
                      {result.label}
                    </span>
                    {result.description && (
                      <span className="flex-shrink-0 text-[10px] text-[var(--fg-muted)]">
                        {result.description}
                      </span>
                    )}
                  </button>
                );
              })}
            </div>
          ))}
        </div>
      </div>
    );
  });
  ```

- [ ] **7.4 Create `components/input/ActionToolbar.tsx`**

  Create `agent/webview/src/components/input/ActionToolbar.tsx` — toolbar with Undo, New, Stop, Traces, Settings buttons. Stop is always visible during execution; others appear on hover:

  ```tsx
  import { memo } from 'react';
  import { useChatStore } from '@/stores/chatStore';

  interface ActionToolbarProps {
    isHovered: boolean;
  }

  interface ToolbarButtonProps {
    label: string;
    onClick: () => void;
    icon: React.ReactNode;
    variant?: 'default' | 'danger';
    visible?: boolean;
  }

  function ToolbarButton({ label, onClick, icon, variant = 'default', visible = true }: ToolbarButtonProps) {
    if (!visible) return null;
    return (
      <button
        onClick={onClick}
        title={label}
        className={`
          flex items-center gap-1 rounded-md px-2 py-1
          text-[11px] transition-all duration-150
          active:scale-[0.97]
          ${variant === 'danger'
            ? 'text-[var(--error)] hover:bg-[var(--error)]/10'
            : 'text-[var(--fg-muted)] hover:bg-[var(--hover-overlay-strong)] hover:text-[var(--fg)]'
          }
        `}
      >
        {icon}
        <span>{label}</span>
      </button>
    );
  }

  export const ActionToolbar = memo(function ActionToolbar({
    isHovered,
  }: ActionToolbarProps) {
    const busy = useChatStore(s => s.busy);

    return (
      <div
        className={`
          flex items-center gap-0.5 px-1 py-0.5
          transition-opacity duration-200
          ${isHovered || busy ? 'opacity-100' : 'opacity-0'}
        `}
      >
        {/* Stop — always visible during execution */}
        <ToolbarButton
          label="Stop"
          visible={busy}
          variant="danger"
          onClick={() => window._cancelTask?.()}
          icon={
            <svg width="12" height="12" viewBox="0 0 16 16" fill="currentColor">
              <rect x="3" y="3" width="10" height="10" rx="1" />
            </svg>
          }
        />

        {/* Undo */}
        <ToolbarButton
          label="Undo"
          visible={isHovered}
          onClick={() => window._requestUndo?.()}
          icon={
            <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
              <path d="M3 7h7a3 3 0 1 1 0 6H9" strokeLinecap="round" strokeLinejoin="round" />
              <path d="M6 4L3 7l3 3" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          }
        />

        {/* New Chat */}
        <ToolbarButton
          label="New"
          visible={isHovered}
          onClick={() => window._newChat?.()}
          icon={
            <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
              <path d="M8 3v10M3 8h10" strokeLinecap="round" />
            </svg>
          }
        />

        {/* Spacer */}
        {isHovered && <div className="flex-1" />}

        {/* Traces */}
        <ToolbarButton
          label="Traces"
          visible={isHovered}
          onClick={() => window._requestViewTrace?.()}
          icon={
            <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
              <path d="M2 12l4-4 3 3 5-7" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          }
        />

        {/* Settings */}
        <ToolbarButton
          label="Settings"
          visible={isHovered}
          onClick={() => window._openSettings?.()}
          icon={
            <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
              <circle cx="8" cy="8" r="2.5" />
              <path d="M8 1.5v2M8 12.5v2M1.5 8h2M12.5 8h2M3.1 3.1l1.4 1.4M11.5 11.5l1.4 1.4M3.1 12.9l1.4-1.4M11.5 4.5l1.4-1.4" />
            </svg>
          }
        />
      </div>
    );
  });
  ```

- [ ] **7.5 Create `components/input/ChatInput.tsx`**

  Create `agent/webview/src/components/input/ChatInput.tsx` — the main contextual input bar with progressive disclosure, mention support, model selector, and mode toggle:

  ```tsx
  import { useCallback, useEffect, useRef, useState } from 'react';
  import { useChatStore } from '@/stores/chatStore';
  import { ContextChip } from './ContextChip';
  import { MentionAutocomplete } from './MentionAutocomplete';
  import { ActionToolbar } from './ActionToolbar';
  import type { Mention, MentionSearchResult } from '@/bridge/types';

  export function ChatInput() {
    const inputState = useChatStore(s => s.inputState);
    const busy = useChatStore(s => s.busy);
    const focusInputTrigger = useChatStore(s => s.focusInputTrigger);

    const [text, setText] = useState('');
    const [mentions, setMentions] = useState<Mention[]>([]);
    const [showMentions, setShowMentions] = useState(false);
    const [mentionQuery, setMentionQuery] = useState('');
    const [isHovered, setIsHovered] = useState(false);
    const [isFocused, setIsFocused] = useState(false);
    const textareaRef = useRef<HTMLTextAreaElement>(null);

    // Focus input when triggered by bridge
    useEffect(() => {
      if (focusInputTrigger > 0) {
        textareaRef.current?.focus();
      }
    }, [focusInputTrigger]);

    // Auto-resize textarea
    const autoResize = useCallback(() => {
      const el = textareaRef.current;
      if (!el) return;
      el.style.height = 'auto';
      el.style.height = `${Math.min(el.scrollHeight, 200)}px`;
    }, []);

    useEffect(() => {
      autoResize();
    }, [text, autoResize]);

    // Handle text changes — detect @ mentions
    const handleChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
      const value = e.target.value;
      setText(value);

      // Detect @ trigger
      const cursorPos = e.target.selectionStart;
      const textBeforeCursor = value.slice(0, cursorPos);
      const atMatch = textBeforeCursor.match(/@(\S*)$/);

      if (atMatch) {
        setShowMentions(true);
        setMentionQuery(atMatch[1]);
      } else {
        setShowMentions(false);
        setMentionQuery('');
      }
    };

    // Handle mention selection
    const handleMentionSelect = (result: MentionSearchResult) => {
      const mention: Mention = {
        type: result.type,
        label: result.label,
        path: result.path,
        icon: result.icon,
      };
      setMentions(prev => [...prev, mention]);

      // Remove the @query from text
      const cursorPos = textareaRef.current?.selectionStart ?? text.length;
      const textBeforeCursor = text.slice(0, cursorPos);
      const atIndex = textBeforeCursor.lastIndexOf('@');
      if (atIndex >= 0) {
        const newText = text.slice(0, atIndex) + text.slice(cursorPos);
        setText(newText);
      }

      setShowMentions(false);
      setMentionQuery('');
      textareaRef.current?.focus();
    };

    // Remove a mention
    const removeMention = (index: number) => {
      setMentions(prev => prev.filter((_, i) => i !== index));
    };

    // Send message
    const sendMessage = useCallback(() => {
      const trimmed = text.trim();
      if (!trimmed || inputState.locked || busy) return;

      useChatStore.getState().sendMessage(trimmed, mentions);
      setText('');
      setMentions([]);
      setShowMentions(false);

      // Reset textarea height
      if (textareaRef.current) {
        textareaRef.current.style.height = 'auto';
      }
    }, [text, mentions, inputState.locked, busy]);

    // Keyboard handling: Enter = send, Shift+Enter = newline
    const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
      if (e.key === 'Enter' && !e.shiftKey && !showMentions) {
        e.preventDefault();
        sendMessage();
      }
    };

    // Toggle plan mode
    const toggleMode = () => {
      const newMode = inputState.mode === 'agent' ? 'plan' : 'agent';
      window._togglePlanMode?.(newMode === 'plan');
    };

    return (
      <div
        className="border-t border-[var(--border,#333)]"
        onMouseEnter={() => setIsHovered(true)}
        onMouseLeave={() => setIsHovered(false)}
      >
        {/* Action toolbar */}
        <ActionToolbar isHovered={isHovered || isFocused} />

        {/* Input area */}
        <div className="relative px-3 pb-3 pt-1">
          {/* Mention autocomplete dropdown */}
          {showMentions && (
            <MentionAutocomplete
              query={mentionQuery}
              onSelect={handleMentionSelect}
              onDismiss={() => {
                setShowMentions(false);
                setMentionQuery('');
              }}
            />
          )}

          {/* Context chips (attached mentions) */}
          {mentions.length > 0 && (
            <div className="mb-1.5 flex flex-wrap gap-1">
              {mentions.map((mention, i) => (
                <ContextChip
                  key={`${mention.type}-${mention.label}-${i}`}
                  mention={mention}
                  onRemove={() => removeMention(i)}
                />
              ))}
            </div>
          )}

          {/* Text input with send button */}
          <div className={`
            flex items-end gap-2 rounded-lg border
            bg-[var(--input-bg,#2a2a2a)]
            transition-colors duration-150
            ${isFocused
              ? 'border-[var(--accent,#6366f1)]'
              : 'border-[var(--input-border,#444)]'
            }
            ${inputState.locked ? 'opacity-50' : ''}
          `}>
            {/* Mode toggle + model selector — visible on hover/focus */}
            <div className={`
              flex items-center gap-1 pl-2 pb-2
              transition-opacity duration-200
              ${isHovered || isFocused ? 'opacity-100' : 'opacity-0'}
            `}>
              {/* Mode toggle (agent/plan) */}
              <button
                onClick={toggleMode}
                title={`Mode: ${inputState.mode}`}
                className="
                  flex h-5 items-center rounded px-1.5
                  text-[10px] font-medium text-[var(--fg-muted)]
                  transition-colors duration-150
                  hover:bg-[var(--hover-overlay-strong)] hover:text-[var(--fg)]
                "
              >
                {inputState.mode === 'agent' ? (
                  <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
                    <circle cx="8" cy="8" r="5" />
                    <path d="M8 5v3l2 2" strokeLinecap="round" />
                  </svg>
                ) : (
                  <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
                    <path d="M3 3h10v2H3zM3 7h7v2H3zM3 11h5v2H3z" />
                  </svg>
                )}
              </button>

              {/* Model selector */}
              {inputState.model && (
                <button
                  onClick={() => {
                    // Open model selection — bridge handles the UI
                    window._changeModel?.('');
                  }}
                  title={`Model: ${inputState.model}`}
                  className="
                    flex h-5 items-center rounded px-1.5
                    text-[10px] text-[var(--fg-muted)]
                    transition-colors duration-150
                    hover:bg-[var(--hover-overlay-strong)] hover:text-[var(--fg)]
                  "
                >
                  {inputState.model}
                </button>
              )}
            </div>

            {/* Textarea */}
            <textarea
              ref={textareaRef}
              value={text}
              onChange={handleChange}
              onKeyDown={handleKeyDown}
              onFocus={() => setIsFocused(true)}
              onBlur={() => setIsFocused(false)}
              disabled={inputState.locked}
              placeholder="Ask anything..."
              rows={1}
              className="
                flex-1 resize-none bg-transparent px-1 py-2.5
                text-[13px] text-[var(--fg)] outline-none
                placeholder:text-[var(--fg-muted)]
                disabled:cursor-not-allowed
              "
            />

            {/* Send button */}
            <button
              onClick={sendMessage}
              disabled={!text.trim() || inputState.locked || busy}
              className="
                mb-2 mr-2 flex h-7 w-7 flex-shrink-0 items-center justify-center
                rounded-md transition-all duration-150
                active:scale-[0.97]
                disabled:opacity-30 disabled:cursor-not-allowed
                enabled:bg-[var(--accent,#6366f1)] enabled:text-white
                enabled:hover:brightness-110
              "
              title="Send (Enter)"
            >
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none">
                <path
                  d="M2 8l12-5-5 12-2-5-5-2z"
                  fill="currentColor"
                />
              </svg>
            </button>
          </div>

          {/* Keyboard hint — shown on first focus */}
          {isFocused && text.length === 0 && (
            <div className="mt-1 text-[10px] text-[var(--fg-muted)] opacity-60">
              Enter = send, Shift+Enter = newline, @ = mention
            </div>
          )}
        </div>
      </div>
    );
  }
  ```

- [ ] **7.6 Update `App.tsx` to render `ChatInput`**

  Replace the input bar placeholder in `agent/webview/src/App.tsx` with the real `ChatInput`:

  ```tsx
  import { MessageList } from '@/components/chat/MessageList';
  import { ChatInput } from '@/components/input/ChatInput';

  function App() {
    return (
      <div className="flex h-screen flex-col bg-[var(--bg,#1e1e1e)] text-[var(--fg,#cccccc)]">
        {/* Message list takes all available space */}
        <MessageList />

        {/* Contextual input bar */}
        <ChatInput />
      </div>
    );
  }

  export default App;
  ```

- [ ] **7.7 Add `window` type declarations for bridge globals**

  Create or update `agent/webview/src/bridge/globals.d.ts` to include the bridge function types used in components:

  ```typescript
  export {};

  declare global {
    interface Window {
      // JS-to-Kotlin bridges (subset used by input components)
      _sendMessage?: (text: string) => void;
      _sendMessageWithMentions?: (payload: string) => void;
      _searchMentions?: (data: string) => void;
      _cancelTask?: () => void;
      _newChat?: () => void;
      _requestUndo?: () => void;
      _requestViewTrace?: () => void;
      _openSettings?: () => void;
      _openToolsPanel?: () => void;
      _changeModel?: (modelId: string) => void;
      _togglePlanMode?: (enabled: boolean) => void;
      _navigateToFile?: (path: string) => void;
      _requestFocusIde?: () => void;
      _submitPrompt?: (text: string) => void;
      _approvePlan?: () => void;
      _revisePlan?: (comments: string) => void;
      _toggleTool?: (data: string) => void;
      _questionAnswered?: (qid: string, opts: string) => void;
      _questionSkipped?: (qid: string) => void;
      _chatAboutOption?: (qid: string, label: string, msg: string) => void;
      _questionsSubmitted?: () => void;
      _questionsCancelled?: () => void;
      _editQuestion?: (qid: string) => void;
      _deactivateSkill?: () => void;
      _activateSkill?: (name: string) => void;

      // Mock bridge for dev mode
      __mock?: Record<string, (...args: any[]) => any>;
    }
  }
  ```

  Note: If `globals.d.ts` was already created in Task 2, merge these declarations into the existing file rather than replacing it.

- [ ] **7.8 Verify input bar**

  ```bash
  cd agent/webview && npm run dev
  ```

  In the browser:
  1. Verify clean text field with "Ask anything..." placeholder
  2. Type text — verify auto-resize grows the textarea
  3. Press Enter — verify message is sent (appears in message list as user message)
  4. Press Shift+Enter — verify newline is inserted (no send)
  5. Type `@` — verify mention autocomplete dropdown appears (empty results if mock doesn't provide data)
  6. Hover over input area — verify model selector and mode toggle icons appear
  7. Click mode toggle — verify it switches between agent/plan icons
  8. Hover over toolbar area — verify Undo, New, Traces, Settings buttons appear
  9. Run `__mock.simulateStreaming()` — verify Stop button appears during execution
  10. Confirm no TypeScript errors

- [ ] **7.9 Commit**

  ```
  feat(agent): implement contextual ChatInput with mentions, model selector, mode toggle, and action toolbar
  ```

---

## Phase 3: Agentic Components

### Task 8: Tool Call Cards

**Objective:** Install the prompt-kit Tool component and create a rich `ToolCallCard` component that shows color-coded tool badges, live execution status with spinner and timer, expandable input/output details, and auto-collapse behavior. Wire to `chatStore.activeToolCalls`.

**Estimated time:** 25-30 minutes

#### Steps

- [ ] **8.1 Install prompt-kit Tool component**

  ```bash
  cd agent/webview && npx shadcn add "https://prompt-kit.com/c/tool.json"
  ```

  Verify the component is copied into `src/components/ui/tool.tsx` (or similar). Review the generated code and note the exported component API.

- [ ] **8.2 Create `components/agent/ToolCallCard.tsx`**

  Create `agent/webview/src/components/agent/ToolCallCard.tsx` — the expanded tool call visualization with status tracking, category badges, and expandable details:

  ```tsx
  import { useState, useEffect, useRef } from 'react';
  import type { ToolCall, ToolCallStatus } from '@/bridge/types';

  // ── Tool category detection ──

  type ToolCategory = 'READ' | 'WRITE' | 'EDIT' | 'CMD' | 'SEARCH';

  const TOOL_CATEGORIES: Record<string, ToolCategory> = {
    Read: 'READ',
    Glob: 'SEARCH',
    Grep: 'SEARCH',
    Bash: 'CMD',
    Write: 'WRITE',
    Edit: 'EDIT',
    MultiEdit: 'EDIT',
    NotebookEdit: 'EDIT',
    WebSearch: 'SEARCH',
    WebFetch: 'READ',
  };

  function getToolCategory(toolName: string): ToolCategory {
    // Match against known tool names (strip mcp__ prefix if present)
    const baseName = toolName.replace(/^mcp__\w+__/, '');
    return TOOL_CATEGORIES[baseName] ?? 'CMD';
  }

  const CATEGORY_STYLES: Record<ToolCategory, { label: string; bgVar: string; fgVar: string; accentVar: string }> = {
    READ:   { label: 'READ',   bgVar: '--badge-read-bg',   fgVar: '--badge-read-fg',   accentVar: '--accent-read' },
    WRITE:  { label: 'WRITE',  bgVar: '--badge-write-bg',  fgVar: '--badge-write-fg',  accentVar: '--accent-write' },
    EDIT:   { label: 'EDIT',   bgVar: '--badge-edit-bg',   fgVar: '--badge-edit-fg',   accentVar: '--accent-edit' },
    CMD:    { label: 'CMD',    bgVar: '--badge-cmd-bg',    fgVar: '--badge-cmd-fg',    accentVar: '--accent-cmd' },
    SEARCH: { label: 'SEARCH', bgVar: '--badge-search-bg', fgVar: '--badge-search-fg', accentVar: '--accent-search' },
  };

  // ── Tool target extraction ──

  function extractToolTarget(toolName: string, args: string): string | null {
    try {
      const parsed = JSON.parse(args);
      // File paths
      if (parsed.file_path) return parsed.file_path;
      if (parsed.path) return parsed.path;
      // Search queries
      if (parsed.pattern) return parsed.pattern;
      if (parsed.query) return parsed.query;
      // Commands
      if (parsed.command) {
        const cmd = parsed.command as string;
        return cmd.length > 60 ? cmd.substring(0, 57) + '...' : cmd;
      }
    } catch {
      // Args might not be valid JSON
      if (args.length > 0 && args.length <= 80) return args;
    }
    return null;
  }

  // ── Status icon components ──

  function StatusIcon({ status }: { status: ToolCallStatus }) {
    switch (status) {
      case 'PENDING':
        return (
          <div
            className="h-4 w-4 rounded-full border-2"
            style={{ borderColor: 'var(--fg-muted, #666)' }}
          />
        );
      case 'RUNNING':
        return (
          <svg className="h-4 w-4 animate-spin" viewBox="0 0 16 16" fill="none">
            <circle
              cx="8" cy="8" r="6"
              stroke="var(--link, #6366f1)"
              strokeWidth="2"
              strokeDasharray="28"
              strokeDashoffset="8"
              strokeLinecap="round"
            />
          </svg>
        );
      case 'COMPLETED':
        return (
          <svg className="h-4 w-4" viewBox="0 0 16 16" fill="none">
            <path
              d="M3 8.5l3.5 3.5 6.5-7"
              stroke="var(--success, #22c55e)"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        );
      case 'ERROR':
        return (
          <svg className="h-4 w-4 animate-[shake_0.3s_ease-in-out]" viewBox="0 0 16 16" fill="none">
            <path
              d="M4 4l8 8M12 4l-8 8"
              stroke="var(--error, #ef4444)"
              strokeWidth="2"
              strokeLinecap="round"
            />
          </svg>
        );
    }
  }

  // ── Live timer hook ──

  function useLiveTimer(isRunning: boolean): number {
    const startRef = useRef<number>(Date.now());
    const [elapsed, setElapsed] = useState(0);

    useEffect(() => {
      if (!isRunning) return;
      startRef.current = Date.now();
      const interval = setInterval(() => {
        setElapsed(Date.now() - startRef.current);
      }, 100);
      return () => clearInterval(interval);
    }, [isRunning]);

    return elapsed;
  }

  function formatDuration(ms: number): string {
    if (ms < 1000) return `${ms}ms`;
    const seconds = (ms / 1000).toFixed(1);
    return `${seconds}s`;
  }

  // ── Main component ──

  interface ToolCallCardProps {
    toolCall: ToolCall;
    /** Whether this is the most recent tool call (auto-expanded) */
    isLatest?: boolean;
  }

  export function ToolCallCard({ toolCall, isLatest = false }: ToolCallCardProps) {
    const { name, args, status, result, durationMs } = toolCall;
    const category = getToolCategory(name);
    const styles = CATEGORY_STYLES[category];
    const target = extractToolTarget(name, args);

    const isRunning = status === 'RUNNING';
    const isComplete = status === 'COMPLETED' || status === 'ERROR';

    // Auto-expand latest, collapse previous
    const [expanded, setExpanded] = useState(isLatest);

    // Auto-collapse when a newer card becomes latest
    useEffect(() => {
      if (!isLatest && expanded && isComplete) {
        setExpanded(false);
      }
    }, [isLatest, isComplete]);

    // Live timer during execution
    const liveElapsed = useLiveTimer(isRunning);

    return (
      <div
        className="
          my-1.5 rounded-lg border transition-all duration-200
          hover:border-[var(--hover-overlay-strong)]
        "
        style={{
          borderColor: isRunning
            ? `var(${styles.accentVar}, var(--border))`
            : 'var(--border, #333)',
          backgroundColor: 'var(--tool-bg, #1a1a2e)',
        }}
      >
        {/* ── Header row ── */}
        <button
          onClick={() => setExpanded(!expanded)}
          className="flex w-full items-center gap-2 px-3 py-2 text-left"
        >
          {/* Status icon */}
          <StatusIcon status={status} />

          {/* Category badge */}
          <span
            className="rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wider"
            style={{
              backgroundColor: `var(${styles.bgVar}, #333)`,
              color: `var(${styles.fgVar}, #fff)`,
            }}
          >
            {styles.label}
          </span>

          {/* Tool name */}
          <span
            className="text-xs font-medium"
            style={{ color: 'var(--fg, #ccc)' }}
          >
            {name}
          </span>

          {/* Tool target (file path, query, command) */}
          {target && (
            <span
              className="truncate text-xs font-mono"
              style={{ color: 'var(--fg-secondary, #999)' }}
              title={target}
            >
              {target}
            </span>
          )}

          {/* Spacer */}
          <span className="flex-1" />

          {/* Running: indeterminate progress + live timer */}
          {isRunning && (
            <span className="flex items-center gap-2">
              <div
                className="h-1 w-16 overflow-hidden rounded-full"
                style={{ backgroundColor: 'var(--hover-overlay, #333)' }}
              >
                <div
                  className="h-full w-8 animate-[indeterminate_1.5s_ease-in-out_infinite] rounded-full"
                  style={{ backgroundColor: `var(${styles.accentVar}, var(--link))` }}
                />
              </div>
              <span className="text-[10px] tabular-nums" style={{ color: 'var(--fg-muted)' }}>
                {formatDuration(liveElapsed)}
              </span>
            </span>
          )}

          {/* Completed: duration badge */}
          {isComplete && durationMs !== undefined && (
            <span
              className="rounded-full px-2 py-0.5 text-[10px] tabular-nums"
              style={{
                backgroundColor: 'var(--hover-overlay, #333)',
                color: 'var(--fg-secondary, #999)',
              }}
            >
              {formatDuration(durationMs)}
            </span>
          )}

          {/* Expand/collapse chevron */}
          <svg
            className="h-3.5 w-3.5 transition-transform duration-200"
            style={{
              transform: expanded ? 'rotate(180deg)' : 'rotate(0deg)',
              color: 'var(--fg-muted, #666)',
            }}
            viewBox="0 0 16 16"
            fill="none"
          >
            <path
              d="M4 6l4 4 4-4"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </button>

        {/* ── Expandable details ── */}
        <div
          className="overflow-hidden transition-all duration-300"
          style={{
            maxHeight: expanded ? '600px' : '0px',
            opacity: expanded ? 1 : 0,
          }}
        >
          <div className="border-t px-3 py-2" style={{ borderColor: 'var(--divider-subtle, #333)' }}>
            {/* Input args */}
            <div className="mb-2">
              <span className="text-[10px] font-semibold uppercase tracking-wider" style={{ color: 'var(--fg-muted)' }}>
                Input
              </span>
              <pre
                className="mt-1 max-h-48 overflow-auto rounded p-2 text-xs leading-relaxed"
                style={{
                  backgroundColor: 'var(--code-bg, #111)',
                  color: 'var(--fg-secondary, #999)',
                  fontFamily: 'var(--font-mono)',
                }}
              >
                {(() => {
                  try {
                    return JSON.stringify(JSON.parse(args), null, 2);
                  } catch {
                    return args;
                  }
                })()}
              </pre>
            </div>

            {/* Output result (only when completed/error) */}
            {result !== undefined && (
              <div>
                <span className="text-[10px] font-semibold uppercase tracking-wider" style={{ color: 'var(--fg-muted)' }}>
                  {status === 'ERROR' ? 'Error' : 'Output'}
                </span>
                <pre
                  className="mt-1 max-h-48 overflow-auto rounded p-2 text-xs leading-relaxed"
                  style={{
                    backgroundColor: 'var(--code-bg, #111)',
                    color: status === 'ERROR' ? 'var(--error, #ef4444)' : 'var(--fg-secondary, #999)',
                    fontFamily: 'var(--font-mono)',
                  }}
                >
                  {result}
                </pre>
              </div>
            )}
          </div>
        </div>
      </div>
    );
  }
  ```

- [ ] **8.3 Add CSS keyframes for tool card animations**

  Add the following keyframes to `agent/webview/src/index.css` (or the global stylesheet):

  ```css
  /* Indeterminate progress bar */
  @keyframes indeterminate {
    0% { transform: translateX(-100%); }
    50% { transform: translateX(200%); }
    100% { transform: translateX(-100%); }
  }

  /* Error shake */
  @keyframes shake {
    0%, 100% { transform: translateX(0); }
    20% { transform: translateX(-2px); }
    40% { transform: translateX(2px); }
    60% { transform: translateX(-2px); }
    80% { transform: translateX(1px); }
  }
  ```

- [ ] **8.4 Create `components/agent/ToolCallList.tsx`**

  Create `agent/webview/src/components/agent/ToolCallList.tsx` — renders all active tool calls from the store, passing `isLatest` to the most recent one:

  ```tsx
  import { useChatStore } from '@/stores/chatStore';
  import { ToolCallCard } from './ToolCallCard';

  export function ToolCallList() {
    const activeToolCalls = useChatStore(s => s.activeToolCalls);

    const entries = Array.from(activeToolCalls.entries());
    if (entries.length === 0) return null;

    return (
      <div className="px-4">
        {entries.map(([key, toolCall], index) => (
          <ToolCallCard
            key={key}
            toolCall={toolCall}
            isLatest={index === entries.length - 1}
          />
        ))}
      </div>
    );
  }
  ```

- [ ] **8.5 Verify tool call cards**

  ```bash
  cd agent/webview && npm run dev
  ```

  In the browser console:
  1. Run `__mock.simulateToolCall()` (or equivalent mock method)
  2. Verify category badge appears with correct color (e.g., CMD = red, READ = blue)
  3. Verify spinner and live timer appear during RUNNING status
  4. Verify indeterminate progress bar animates
  5. Verify auto-collapse of previous card when new one starts
  6. Verify expand/collapse toggle works on click
  7. Verify duration badge appears on completion
  8. Verify error state shows red X with shake animation
  9. Verify input args are pretty-printed JSON
  10. Confirm no TypeScript errors

- [ ] **8.6 Commit**

  ```
  feat(agent): implement ToolCallCard with category badges, live timer, expandable details, and auto-collapse
  ```

---

### Task 9: Thinking/Reasoning Blocks

**Objective:** Install prompt-kit Reasoning and ThinkingBar components, then create a `ThinkingBlock` component with purple accent border, live thinking timer, collapsible content with smooth height animation, and markdown support inside thinking content. Wire to chatStore thinking state.

**Estimated time:** 15-20 minutes

#### Steps

- [ ] **9.1 Install prompt-kit Reasoning and ThinkingBar**

  ```bash
  cd agent/webview && npx shadcn add "https://prompt-kit.com/c/reasoning.json"
  cd agent/webview && npx shadcn add "https://prompt-kit.com/c/thinking-bar.json"
  ```

  Verify the components are copied into `src/components/ui/`. Review the generated code and note the exported component API.

- [ ] **9.2 Create `components/agent/ThinkingBlock.tsx`**

  Create `agent/webview/src/components/agent/ThinkingBlock.tsx` — collapsible thinking/reasoning block with live timer, purple accent, and markdown rendering:

  ```tsx
  import { useState, useEffect, useRef } from 'react';

  // ── Live timer for thinking duration ──

  function useThinkingTimer(isStreaming: boolean): number {
    const startRef = useRef<number>(Date.now());
    const [elapsed, setElapsed] = useState(0);

    useEffect(() => {
      if (!isStreaming) return;
      startRef.current = Date.now();
      const interval = setInterval(() => {
        setElapsed(Date.now() - startRef.current);
      }, 100);
      return () => {
        clearInterval(interval);
        // Freeze the timer when streaming stops
        setElapsed(Date.now() - startRef.current);
      };
    }, [isStreaming]);

    return elapsed;
  }

  function formatThinkingDuration(ms: number): string {
    const seconds = Math.round(ms / 1000);
    if (seconds === 0) return 'Thinking...';
    if (seconds === 1) return 'Thought for 1s';
    return `Thought for ${seconds}s`;
  }

  // ── Main component ──

  interface ThinkingBlockProps {
    /** The thinking/reasoning text content */
    content: string;
    /** Whether the model is still generating thinking tokens */
    isStreaming?: boolean;
  }

  export function ThinkingBlock({ content, isStreaming = false }: ThinkingBlockProps) {
    // Auto-close when streaming ends
    const [expanded, setExpanded] = useState(true);
    const hasAutoClosedRef = useRef(false);

    const elapsed = useThinkingTimer(isStreaming);

    // Auto-collapse when streaming ends (only once)
    useEffect(() => {
      if (!isStreaming && !hasAutoClosedRef.current && content.length > 0) {
        hasAutoClosedRef.current = true;
        // Delay collapse slightly so user sees the final state
        const timer = setTimeout(() => setExpanded(false), 600);
        return () => clearTimeout(timer);
      }
    }, [isStreaming, content]);

    return (
      <div
        className="my-2 rounded-lg border-l-3 transition-all duration-200"
        style={{
          borderLeftColor: 'var(--accent, #8b5cf6)',
          backgroundColor: 'var(--thinking-bg, #1f2937)',
        }}
      >
        {/* ── Header ── */}
        <button
          onClick={() => setExpanded(!expanded)}
          className="flex w-full items-center gap-2 px-3 py-2 text-left"
        >
          {/* Brain/thinking icon */}
          <svg
            className="h-4 w-4 flex-shrink-0"
            style={{ color: 'var(--accent, #8b5cf6)' }}
            viewBox="0 0 16 16"
            fill="none"
          >
            <circle
              cx="8" cy="8" r="6"
              stroke="currentColor"
              strokeWidth="1.5"
              strokeDasharray={isStreaming ? '4 2' : 'none'}
            >
              {isStreaming && (
                <animateTransform
                  attributeName="transform"
                  type="rotate"
                  from="0 8 8"
                  to="360 8 8"
                  dur="3s"
                  repeatCount="indefinite"
                />
              )}
            </circle>
            <circle cx="6" cy="7" r="1" fill="currentColor" />
            <circle cx="10" cy="7" r="1" fill="currentColor" />
            <path
              d="M6 10c0.5 1 3.5 1 4 0"
              stroke="currentColor"
              strokeWidth="1"
              strokeLinecap="round"
            />
          </svg>

          {/* Timer label */}
          <span
            className="text-xs font-medium"
            style={{ color: 'var(--accent, #8b5cf6)' }}
          >
            {formatThinkingDuration(elapsed)}
          </span>

          {/* Shimmer bar when streaming */}
          {isStreaming && (
            <div
              className="ml-2 h-1 w-20 overflow-hidden rounded-full"
              style={{ backgroundColor: 'var(--hover-overlay, #333)' }}
            >
              <div
                className="h-full w-10 animate-[thinking-shimmer_1.8s_ease-in-out_infinite] rounded-full"
                style={{ backgroundColor: 'var(--accent, #8b5cf6)', opacity: 0.6 }}
              />
            </div>
          )}

          {/* Spacer */}
          <span className="flex-1" />

          {/* Expand/collapse chevron */}
          <svg
            className="h-3 w-3 transition-transform duration-200"
            style={{
              transform: expanded ? 'rotate(180deg)' : 'rotate(0deg)',
              color: 'var(--fg-muted, #666)',
            }}
            viewBox="0 0 16 16"
            fill="none"
          >
            <path
              d="M4 6l4 4 4-4"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </button>

        {/* ── Collapsible content ── */}
        <div
          className="overflow-hidden transition-all duration-300 ease-[cubic-bezier(0.4,0,0.2,1)]"
          style={{
            maxHeight: expanded ? '2000px' : '0px',
            opacity: expanded ? 1 : 0,
          }}
        >
          <div
            className="border-t px-3 py-2"
            style={{ borderColor: 'var(--divider-subtle, #333)' }}
          >
            <div
              className="prose prose-sm max-w-none text-xs leading-relaxed"
              style={{
                color: 'var(--fg-secondary, #999)',
                fontFamily: 'var(--font-body)',
              }}
            >
              {/* Render thinking content as plain text with line breaks preserved.
                  For full markdown support, replace with MarkdownRenderer once
                  the markdown component is implemented in a later task. */}
              {content.split('\n').map((line, i) => (
                <span key={i}>
                  {line}
                  {i < content.split('\n').length - 1 && <br />}
                </span>
              ))}

              {/* Blinking cursor during streaming */}
              {isStreaming && (
                <span
                  className="inline-block h-3.5 w-0.5 animate-[cursor-blink_530ms_step-end_infinite] align-text-bottom"
                  style={{ backgroundColor: 'var(--accent, #8b5cf6)' }}
                />
              )}
            </div>
          </div>
        </div>
      </div>
    );
  }
  ```

- [ ] **9.3 Add CSS keyframes for thinking animations**

  Add the following keyframes to `agent/webview/src/index.css` (merge with existing keyframes from Task 8):

  ```css
  /* Thinking shimmer bar */
  @keyframes thinking-shimmer {
    0% { transform: translateX(-150%); }
    50% { transform: translateX(300%); }
    100% { transform: translateX(-150%); }
  }

  /* Cursor blink for streaming */
  @keyframes cursor-blink {
    0%, 100% { opacity: 1; }
    50% { opacity: 0; }
  }
  ```

- [ ] **9.4 Verify thinking blocks**

  ```bash
  cd agent/webview && npm run dev
  ```

  In the browser console:
  1. Run `__mock.simulateThinking()` (or equivalent mock method)
  2. Verify purple accent border appears on the left
  3. Verify "Thinking..." label with live timer counting up
  4. Verify shimmer bar animates during streaming
  5. Verify blinking cursor at end of text during streaming
  6. Verify auto-collapse after streaming ends (with ~600ms delay)
  7. Verify click to expand/collapse works after auto-collapse
  8. Verify text content is readable with proper line breaks
  9. Confirm no TypeScript errors

- [ ] **9.5 Commit**

  ```
  feat(agent): implement ThinkingBlock with live timer, auto-collapse, purple accent, and streaming cursor
  ```

---

### Task 10: Plan Card

**Objective:** Install prompt-kit Steps component and create a comprehensive `PlanCard` component with numbered steps, per-step status indicators, step connectors, per-step comment input, clickable file links, collapsible approach/testing sections, and approve/revise buttons. Wire to `chatStore.plan`.

**Estimated time:** 30-40 minutes

#### Steps

- [ ] **10.1 Install prompt-kit Steps component**

  ```bash
  cd agent/webview && npx shadcn add "https://prompt-kit.com/c/steps.json"
  ```

  Verify the component is copied into `src/components/ui/`. Review the generated code and note the exported component API.

- [ ] **10.2 Create `components/agent/PlanCard.tsx`**

  Create `agent/webview/src/components/agent/PlanCard.tsx` — full plan visualization with interactive steps, comments, file links, and approval actions:

  ```tsx
  import { useState, useCallback } from 'react';
  import { useChatStore } from '@/stores/chatStore';
  import type { Plan, PlanStep, PlanStepStatus } from '@/bridge/types';

  // ── Step status icon ──

  function StepStatusIcon({ status, index }: { status: PlanStepStatus; index: number }) {
    switch (status) {
      case 'pending':
        return (
          <div
            className="flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-full border-2 text-[10px] font-bold"
            style={{
              borderColor: 'var(--pending, var(--fg-muted, #666))',
              color: 'var(--pending, var(--fg-muted, #666))',
            }}
          >
            {index + 1}
          </div>
        );
      case 'running':
        return (
          <div className="flex h-6 w-6 flex-shrink-0 items-center justify-center">
            <svg className="h-6 w-6 animate-spin" viewBox="0 0 24 24" fill="none">
              <circle
                cx="12" cy="12" r="10"
                stroke="var(--running, var(--link, #6366f1))"
                strokeWidth="2.5"
                strokeDasharray="50"
                strokeDashoffset="15"
                strokeLinecap="round"
              />
            </svg>
          </div>
        );
      case 'completed':
        return (
          <div
            className="flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-full"
            style={{ backgroundColor: 'var(--success, #22c55e)' }}
          >
            <svg className="h-3.5 w-3.5 text-white" viewBox="0 0 16 16" fill="none">
              <path
                d="M3 8.5l3.5 3.5 6.5-7"
                stroke="currentColor"
                strokeWidth="2.5"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          </div>
        );
      case 'failed':
        return (
          <div
            className="flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-full"
            style={{ backgroundColor: 'var(--error, #ef4444)' }}
          >
            <svg className="h-3.5 w-3.5 text-white" viewBox="0 0 16 16" fill="none">
              <path
                d="M4 4l8 8M12 4l-8 8"
                stroke="currentColor"
                strokeWidth="2.5"
                strokeLinecap="round"
              />
            </svg>
          </div>
        );
      case 'skipped':
        return (
          <div
            className="flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-full border-2 border-dashed"
            style={{ borderColor: 'var(--fg-muted, #666)' }}
          >
            <svg className="h-3 w-3" style={{ color: 'var(--fg-muted, #666)' }} viewBox="0 0 16 16" fill="none">
              <path d="M5 4l6 4-6 4z" fill="currentColor" />
            </svg>
          </div>
        );
    }
  }

  // ── Step connector line ──

  function StepConnector({ status }: { status: PlanStepStatus }) {
    const isDone = status === 'completed';
    return (
      <div
        className="ml-3 w-0.5 transition-colors duration-300"
        style={{
          height: '16px',
          backgroundColor: isDone
            ? 'var(--success, #22c55e)'
            : 'var(--divider-subtle, #333)',
        }}
      />
    );
  }

  // ── File link component ──

  function FileLink({ path }: { path: string }) {
    const fileName = path.split('/').pop() ?? path;

    const handleClick = () => {
      window._navigateToFile?.(path);
    };

    return (
      <button
        onClick={handleClick}
        className="inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-xs transition-colors duration-150 hover:underline"
        style={{
          color: 'var(--link, #6366f1)',
          backgroundColor: 'var(--hover-overlay, transparent)',
        }}
        title={path}
      >
        <svg className="h-3 w-3" viewBox="0 0 16 16" fill="none">
          <path
            d="M4 2h5l4 4v8a1 1 0 01-1 1H4a1 1 0 01-1-1V3a1 1 0 011-1z"
            stroke="currentColor"
            strokeWidth="1.5"
          />
          <path d="M9 2v4h4" stroke="currentColor" strokeWidth="1.5" />
        </svg>
        {fileName}
      </button>
    );
  }

  // ── Step comment input ──

  function StepComment({
    stepId,
    comment,
    onComment,
  }: {
    stepId: string;
    comment?: string;
    onComment: (stepId: string, text: string) => void;
  }) {
    const [editing, setEditing] = useState(false);
    const [text, setText] = useState(comment ?? '');

    if (!editing && !comment) {
      return (
        <button
          onClick={() => setEditing(true)}
          className="mt-1 text-[10px] transition-colors duration-150 hover:underline"
          style={{ color: 'var(--fg-muted, #666)' }}
        >
          + Add comment
        </button>
      );
    }

    if (editing) {
      return (
        <div className="mt-1.5 flex gap-1">
          <input
            type="text"
            value={text}
            onChange={e => setText(e.target.value)}
            onKeyDown={e => {
              if (e.key === 'Enter' && text.trim()) {
                onComment(stepId, text.trim());
                setEditing(false);
              }
              if (e.key === 'Escape') {
                setText(comment ?? '');
                setEditing(false);
              }
            }}
            autoFocus
            className="flex-1 rounded px-2 py-1 text-xs outline-none"
            style={{
              backgroundColor: 'var(--input-bg, #111)',
              border: '1px solid var(--input-border, #444)',
              color: 'var(--fg, #ccc)',
              fontFamily: 'var(--font-body)',
            }}
            placeholder="Add a comment for this step..."
          />
          <button
            onClick={() => {
              if (text.trim()) onComment(stepId, text.trim());
              setEditing(false);
            }}
            className="rounded px-2 py-1 text-[10px] font-medium"
            style={{
              backgroundColor: 'var(--accent, #6366f1)',
              color: '#fff',
            }}
          >
            Save
          </button>
        </div>
      );
    }

    return (
      <button
        onClick={() => setEditing(true)}
        className="mt-1 text-xs italic transition-colors duration-150 hover:underline"
        style={{ color: 'var(--fg-muted, #666)' }}
      >
        {comment}
      </button>
    );
  }

  // ── Main component ──

  interface PlanCardProps {
    plan: Plan;
  }

  export function PlanCard({ plan }: PlanCardProps) {
    const [comments, setComments] = useState<Record<string, string>>({});
    const [showApproach, setShowApproach] = useState(false);
    const [showTesting, setShowTesting] = useState(false);

    const completedCount = plan.steps.filter(s => s.status === 'completed').length;

    const handleComment = useCallback((stepId: string, text: string) => {
      setComments(prev => ({ ...prev, [stepId]: text }));
    }, []);

    const handleApprove = useCallback(() => {
      window._approvePlan?.();
    }, []);

    const handleRevise = useCallback(() => {
      const commentJson = JSON.stringify(comments);
      window._revisePlan?.(commentJson);
    }, [comments]);

    const hasComments = Object.values(comments).some(c => c.trim().length > 0);

    return (
      <div
        className="my-3 rounded-xl border"
        style={{
          borderColor: 'var(--border, #333)',
          backgroundColor: 'var(--tool-bg, #1a1a2e)',
        }}
      >
        {/* ── Header ── */}
        <div className="flex items-center gap-3 border-b px-4 py-3" style={{ borderColor: 'var(--divider-subtle, #333)' }}>
          {/* Plan icon */}
          <svg className="h-5 w-5 flex-shrink-0" style={{ color: 'var(--accent, #6366f1)' }} viewBox="0 0 16 16" fill="none">
            <rect x="2" y="2" width="12" height="12" rx="2" stroke="currentColor" strokeWidth="1.5" />
            <path d="M5 5h6M5 8h6M5 11h4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
          </svg>

          {/* Title */}
          <span className="flex-1 text-sm font-semibold" style={{ color: 'var(--fg, #ccc)' }}>
            {plan.title}
          </span>

          {/* Step count */}
          <span
            className="rounded-full px-2.5 py-0.5 text-[10px] font-medium tabular-nums"
            style={{
              backgroundColor: 'var(--hover-overlay, #333)',
              color: 'var(--fg-secondary, #999)',
            }}
          >
            {completedCount}/{plan.steps.length} steps
          </span>
        </div>

        {/* ── Steps list ── */}
        <div className="px-4 py-3">
          {plan.steps.map((step, index) => (
            <div key={step.id}>
              {/* Connector line (between steps, not before first) */}
              {index > 0 && <StepConnector status={plan.steps[index - 1].status} />}

              <div className="flex gap-3">
                {/* Status icon */}
                <StepStatusIcon status={step.status} index={index} />

                {/* Step content */}
                <div className="flex-1 pb-1">
                  {/* Step title */}
                  <span
                    className="text-sm font-medium"
                    style={{
                      color: step.status === 'completed'
                        ? 'var(--fg-secondary, #999)'
                        : step.status === 'running'
                        ? 'var(--fg, #ccc)'
                        : 'var(--fg, #ccc)',
                      textDecoration: step.status === 'skipped' ? 'line-through' : 'none',
                    }}
                  >
                    {step.title}
                  </span>

                  {/* Step description */}
                  {step.description && (
                    <p
                      className="mt-0.5 text-xs leading-relaxed"
                      style={{ color: 'var(--fg-muted, #666)' }}
                    >
                      {step.description}
                    </p>
                  )}

                  {/* File links */}
                  {step.filePaths && step.filePaths.length > 0 && (
                    <div className="mt-1.5 flex flex-wrap gap-1">
                      {step.filePaths.map(path => (
                        <FileLink key={path} path={path} />
                      ))}
                    </div>
                  )}

                  {/* Comment */}
                  <StepComment
                    stepId={step.id}
                    comment={comments[step.id] ?? step.comment}
                    onComment={handleComment}
                  />
                </div>
              </div>
            </div>
          ))}
        </div>

        {/* ── Collapsible sections ── */}
        <div className="border-t" style={{ borderColor: 'var(--divider-subtle, #333)' }}>
          {/* Approach section */}
          <button
            onClick={() => setShowApproach(!showApproach)}
            className="flex w-full items-center gap-2 px-4 py-2 text-left text-xs font-medium transition-colors duration-150"
            style={{ color: 'var(--fg-secondary, #999)' }}
          >
            <svg
              className="h-3 w-3 transition-transform duration-200"
              style={{ transform: showApproach ? 'rotate(90deg)' : 'rotate(0deg)' }}
              viewBox="0 0 16 16"
              fill="none"
            >
              <path d="M6 4l4 4-4 4" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
            Approach
          </button>
          <div
            className="overflow-hidden transition-all duration-300"
            style={{ maxHeight: showApproach ? '500px' : '0px', opacity: showApproach ? 1 : 0 }}
          >
            <div className="px-4 pb-3 text-xs leading-relaxed" style={{ color: 'var(--fg-muted, #666)' }}>
              {/* Approach content is derived from step descriptions */}
              {plan.steps
                .filter(s => s.description)
                .map(s => (
                  <p key={s.id} className="mb-1">
                    <strong>{s.title}:</strong> {s.description}
                  </p>
                ))}
            </div>
          </div>

          {/* Testing section */}
          <button
            onClick={() => setShowTesting(!showTesting)}
            className="flex w-full items-center gap-2 border-t px-4 py-2 text-left text-xs font-medium transition-colors duration-150"
            style={{
              color: 'var(--fg-secondary, #999)',
              borderColor: 'var(--divider-subtle, #333)',
            }}
          >
            <svg
              className="h-3 w-3 transition-transform duration-200"
              style={{ transform: showTesting ? 'rotate(90deg)' : 'rotate(0deg)' }}
              viewBox="0 0 16 16"
              fill="none"
            >
              <path d="M6 4l4 4-4 4" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
            Testing
          </button>
          <div
            className="overflow-hidden transition-all duration-300"
            style={{ maxHeight: showTesting ? '500px' : '0px', opacity: showTesting ? 1 : 0 }}
          >
            <div className="px-4 pb-3 text-xs leading-relaxed" style={{ color: 'var(--fg-muted, #666)' }}>
              Verification steps will be shown here after plan execution.
            </div>
          </div>
        </div>

        {/* ── Action buttons ── */}
        {!plan.approved && (
          <div
            className="flex items-center justify-end gap-2 border-t px-4 py-3"
            style={{ borderColor: 'var(--divider-subtle, #333)' }}
          >
            {/* Revise with Comments (only when comments exist) */}
            <button
              onClick={handleRevise}
              disabled={!hasComments}
              className="
                rounded-lg px-4 py-2 text-xs font-medium transition-all duration-150
                active:scale-[0.97]
                disabled:opacity-30 disabled:cursor-not-allowed
              "
              style={{
                border: '1px solid var(--warning, #f59e0b)',
                color: 'var(--warning, #f59e0b)',
                backgroundColor: 'transparent',
              }}
            >
              Revise with Comments
            </button>

            {/* Approve & Execute */}
            <button
              onClick={handleApprove}
              className="
                rounded-lg px-4 py-2 text-xs font-semibold text-white transition-all duration-150
                active:scale-[0.97]
                hover:brightness-110
              "
              style={{
                backgroundColor: 'var(--success, #22c55e)',
              }}
            >
              Approve & Execute
            </button>
          </div>
        )}
      </div>
    );
  }
  ```

- [ ] **10.3 Wire PlanCard to chatStore**

  In the message rendering flow (e.g., `MessageList.tsx` or `App.tsx`), add a conditional render for the plan:

  ```tsx
  import { useChatStore } from '@/stores/chatStore';
  import { PlanCard } from '@/components/agent/PlanCard';

  // Inside the component render:
  const plan = useChatStore(s => s.plan);

  // After the message list, before input:
  {plan && <PlanCard plan={plan} />}
  ```

  This renders the PlanCard when `chatStore.plan` is non-null (set by `renderPlan()` bridge call).

- [ ] **10.4 Verify plan card**

  ```bash
  cd agent/webview && npm run dev
  ```

  In the browser console:
  1. Run `__mock.simulatePlan()` (or equivalent mock method)
  2. Verify plan header with icon, title, and step count badge
  3. Verify numbered step circles for pending steps
  4. Verify vertical connector lines between steps (filled green when step above is done)
  5. Verify spinner on running step
  6. Verify green check on completed step, red X on failed
  7. Click "+ Add comment" on a step — verify input appears
  8. Type a comment and press Enter — verify it saves
  9. Verify file links appear and are clickable (calls `_navigateToFile`)
  10. Click "Approach" section — verify it expands/collapses
  11. Click "Approve & Execute" — verify it calls `_approvePlan`
  12. Add a comment, then click "Revise with Comments" — verify it calls `_revisePlan` with comment JSON
  13. Verify "Revise with Comments" is disabled when no comments exist
  14. Confirm no TypeScript errors

- [ ] **10.5 Commit**

  ```
  feat(agent): implement PlanCard with step status, connectors, comments, file links, and approval actions
  ```

---

### Task 11: Approval Gate

**Objective:** Create an `ApprovalGate` component with warning-bordered card styling (orange/amber border and background), action description, command preview in monospace block, and approve/deny buttons. Wire to appropriate bridge callbacks.

**Estimated time:** 10-15 minutes

#### Steps

- [ ] **11.1 Create `components/agent/ApprovalGate.tsx`**

  Create `agent/webview/src/components/agent/ApprovalGate.tsx` — warning-styled approval card for destructive actions:

  ```tsx
  import { useCallback } from 'react';

  // ── Types ──

  interface ApprovalGateProps {
    /** Title of the action requiring approval */
    title: string;
    /** Description of what will happen */
    description?: string;
    /** Command or action to preview (shown in monospace block) */
    commandPreview?: string;
    /** Callback when user approves */
    onApprove: () => void;
    /** Callback when user denies */
    onDeny: () => void;
  }

  export function ApprovalGate({
    title,
    description,
    commandPreview,
    onApprove,
    onDeny,
  }: ApprovalGateProps) {
    const handleApprove = useCallback(() => {
      onApprove();
    }, [onApprove]);

    const handleDeny = useCallback(() => {
      onDeny();
    }, [onDeny]);

    return (
      <div
        className="my-3 rounded-xl border-2 animate-[fade-in_220ms_ease-out]"
        style={{
          borderColor: 'var(--warning, #f59e0b)',
          backgroundColor: 'color-mix(in srgb, var(--warning, #f59e0b) 6%, var(--bg, #1e1e1e))',
        }}
      >
        {/* ── Header ── */}
        <div className="flex items-center gap-3 px-4 py-3">
          {/* Warning/caution icon */}
          <div
            className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg"
            style={{
              backgroundColor: 'color-mix(in srgb, var(--warning, #f59e0b) 15%, transparent)',
            }}
          >
            <svg className="h-5 w-5" style={{ color: 'var(--warning, #f59e0b)' }} viewBox="0 0 16 16" fill="none">
              <path
                d="M8 1.5l6.5 12H1.5L8 1.5z"
                stroke="currentColor"
                strokeWidth="1.5"
                strokeLinejoin="round"
              />
              <path d="M8 6v3" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
              <circle cx="8" cy="11.5" r="0.75" fill="currentColor" />
            </svg>
          </div>

          {/* Title */}
          <div className="flex-1">
            <h3
              className="text-sm font-semibold"
              style={{ color: 'var(--warning, #f59e0b)' }}
            >
              Approval Required
            </h3>
            <p
              className="text-xs font-medium"
              style={{ color: 'var(--fg, #ccc)' }}
            >
              {title}
            </p>
          </div>
        </div>

        {/* ── Description ── */}
        {description && (
          <div className="px-4 pb-2">
            <p
              className="text-xs leading-relaxed"
              style={{ color: 'var(--fg-secondary, #999)' }}
            >
              {description}
            </p>
          </div>
        )}

        {/* ── Command preview ── */}
        {commandPreview && (
          <div className="px-4 pb-3">
            <pre
              className="rounded-lg p-3 text-xs leading-relaxed"
              style={{
                backgroundColor: 'var(--code-bg, #111)',
                color: 'var(--fg, #ccc)',
                fontFamily: 'var(--font-mono)',
                border: '1px solid var(--divider-subtle, #333)',
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-all',
              }}
            >
              {commandPreview}
            </pre>
          </div>
        )}

        {/* ── Action buttons ── */}
        <div
          className="flex items-center justify-end gap-2 border-t px-4 py-3"
          style={{
            borderColor: 'color-mix(in srgb, var(--warning, #f59e0b) 20%, var(--divider-subtle, #333))',
          }}
        >
          {/* Deny button (outline, red) */}
          <button
            onClick={handleDeny}
            className="
              rounded-lg px-4 py-2 text-xs font-medium transition-all duration-150
              active:scale-[0.97]
              hover:brightness-110
            "
            style={{
              border: '1px solid var(--error, #ef4444)',
              color: 'var(--error, #ef4444)',
              backgroundColor: 'transparent',
            }}
          >
            Deny
          </button>

          {/* Approve button (filled, green) */}
          <button
            onClick={handleApprove}
            className="
              rounded-lg px-4 py-2 text-xs font-semibold text-white transition-all duration-150
              active:scale-[0.97]
              hover:brightness-110
            "
            style={{
              backgroundColor: 'var(--success, #22c55e)',
            }}
          >
            Approve
          </button>
        </div>
      </div>
    );
  }
  ```

- [ ] **11.2 Add fade-in keyframe**

  Add to `agent/webview/src/index.css` (merge with existing keyframes):

  ```css
  /* Fade-in for approval gate and other cards */
  @keyframes fade-in {
    from {
      opacity: 0;
      transform: translateY(8px);
    }
    to {
      opacity: 1;
      transform: translateY(0);
    }
  }
  ```

- [ ] **11.3 Verify approval gate**

  ```bash
  cd agent/webview && npm run dev
  ```

  Test by rendering the component with mock data:
  1. Verify orange/amber border and tinted background
  2. Verify warning triangle icon in header
  3. Verify "Approval Required" header with action title below
  4. Verify description text renders correctly
  5. Verify command preview renders in monospace block with code background
  6. Click "Approve" — verify callback fires
  7. Click "Deny" — verify callback fires
  8. Verify button hover and active states
  9. Verify fade-in entrance animation
  10. Confirm no TypeScript errors

- [ ] **11.4 Commit**

  ```
  feat(agent): implement ApprovalGate with warning styling, command preview, and approve/deny actions
  ```

---

### Task 12: Question Wizard

**Objective:** Create a multi-page `QuestionWizard` component with step dots navigation, single-select (radio) and multi-select (checkboxes), back/skip/next navigation, "Chat about this" option per choice, summary page with edit buttons, submit/cancel actions, and all 6 bridge callbacks wired. Wire to `chatStore.questions`.

**Estimated time:** 35-45 minutes

#### Steps

- [ ] **12.1 Create `components/agent/QuestionWizard.tsx`**

  Create `agent/webview/src/components/agent/QuestionWizard.tsx` — full multi-page question wizard with all navigation and bridge wiring:

  ```tsx
  import { useState, useCallback, useMemo } from 'react';
  import { useChatStore } from '@/stores/chatStore';
  import type { Question, QuestionOption, QuestionType } from '@/bridge/types';

  // ── Step dots navigation ──

  function StepDots({
    total,
    current,
    onDotClick,
  }: {
    total: number;
    current: number;
    onDotClick: (index: number) => void;
  }) {
    return (
      <div className="flex items-center justify-center gap-1.5 py-2">
        {Array.from({ length: total }, (_, i) => (
          <button
            key={i}
            onClick={() => onDotClick(i)}
            className="h-2 rounded-full transition-all duration-200"
            style={{
              width: i === current ? '16px' : '8px',
              backgroundColor: i === current
                ? 'var(--accent, #6366f1)'
                : i < current
                ? 'var(--success, #22c55e)'
                : 'var(--fg-muted, #666)',
              opacity: i === current ? 1 : 0.5,
            }}
            title={`Question ${i + 1}`}
          />
        ))}
      </div>
    );
  }

  // ── Single-select option (radio button) ──

  function RadioOption({
    option,
    selected,
    onSelect,
    onChatAbout,
    questionId,
  }: {
    option: QuestionOption;
    selected: boolean;
    onSelect: () => void;
    onChatAbout: (label: string) => void;
    questionId: string;
  }) {
    return (
      <button
        onClick={onSelect}
        className="flex w-full items-start gap-3 rounded-lg border px-3 py-2.5 text-left transition-all duration-150 active:scale-[0.99]"
        style={{
          borderColor: selected
            ? 'var(--accent, #6366f1)'
            : 'var(--border, #333)',
          backgroundColor: selected
            ? 'color-mix(in srgb, var(--accent, #6366f1) 8%, var(--bg, #1e1e1e))'
            : 'transparent',
        }}
      >
        {/* Radio circle */}
        <div
          className="mt-0.5 flex h-4 w-4 flex-shrink-0 items-center justify-center rounded-full border-2 transition-colors duration-150"
          style={{
            borderColor: selected ? 'var(--accent, #6366f1)' : 'var(--fg-muted, #666)',
          }}
        >
          {selected && (
            <div
              className="h-2 w-2 rounded-full"
              style={{ backgroundColor: 'var(--accent, #6366f1)' }}
            />
          )}
        </div>

        {/* Label + description */}
        <div className="flex-1">
          <span
            className="text-sm font-medium"
            style={{ color: 'var(--fg, #ccc)' }}
          >
            {option.label}
          </span>
          {option.description && (
            <p
              className="mt-0.5 text-xs leading-relaxed"
              style={{ color: 'var(--fg-muted, #666)' }}
            >
              {option.description}
            </p>
          )}
        </div>

        {/* Chat about this option */}
        <button
          onClick={e => {
            e.stopPropagation();
            onChatAbout(option.label);
          }}
          className="flex-shrink-0 rounded p-1 text-[10px] transition-colors duration-150"
          style={{ color: 'var(--link, #6366f1)' }}
          title="Chat about this option"
        >
          <svg className="h-3.5 w-3.5" viewBox="0 0 16 16" fill="none">
            <path
              d="M2 3h12v8H5l-3 3V3z"
              stroke="currentColor"
              strokeWidth="1.5"
              strokeLinejoin="round"
            />
          </svg>
        </button>
      </button>
    );
  }

  // ── Multi-select option (checkbox) ──

  function CheckboxOption({
    option,
    selected,
    onToggle,
    onChatAbout,
    questionId,
  }: {
    option: QuestionOption;
    selected: boolean;
    onToggle: () => void;
    onChatAbout: (label: string) => void;
    questionId: string;
  }) {
    return (
      <button
        onClick={onToggle}
        className="flex w-full items-start gap-3 rounded-lg border px-3 py-2.5 text-left transition-all duration-150 active:scale-[0.99]"
        style={{
          borderColor: selected
            ? 'var(--accent, #6366f1)'
            : 'var(--border, #333)',
          backgroundColor: selected
            ? 'color-mix(in srgb, var(--accent, #6366f1) 8%, var(--bg, #1e1e1e))'
            : 'transparent',
        }}
      >
        {/* Checkbox */}
        <div
          className="mt-0.5 flex h-4 w-4 flex-shrink-0 items-center justify-center rounded border-2 transition-colors duration-150"
          style={{
            borderColor: selected ? 'var(--accent, #6366f1)' : 'var(--fg-muted, #666)',
            backgroundColor: selected ? 'var(--accent, #6366f1)' : 'transparent',
          }}
        >
          {selected && (
            <svg className="h-3 w-3 text-white" viewBox="0 0 16 16" fill="none">
              <path
                d="M3 8.5l3.5 3.5 6.5-7"
                stroke="currentColor"
                strokeWidth="2.5"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          )}
        </div>

        {/* Label + description */}
        <div className="flex-1">
          <span
            className="text-sm font-medium"
            style={{ color: 'var(--fg, #ccc)' }}
          >
            {option.label}
          </span>
          {option.description && (
            <p
              className="mt-0.5 text-xs leading-relaxed"
              style={{ color: 'var(--fg-muted, #666)' }}
            >
              {option.description}
            </p>
          )}
        </div>

        {/* Chat about this option */}
        <button
          onClick={e => {
            e.stopPropagation();
            onChatAbout(option.label);
          }}
          className="flex-shrink-0 rounded p-1 text-[10px] transition-colors duration-150"
          style={{ color: 'var(--link, #6366f1)' }}
          title="Chat about this option"
        >
          <svg className="h-3.5 w-3.5" viewBox="0 0 16 16" fill="none">
            <path
              d="M2 3h12v8H5l-3 3V3z"
              stroke="currentColor"
              strokeWidth="1.5"
              strokeLinejoin="round"
            />
          </svg>
        </button>
      </button>
    );
  }

  // ── Summary page ──

  function SummaryPage({
    questions,
    answers,
    onEdit,
  }: {
    questions: Question[];
    answers: Record<string, string | string[]>;
    onEdit: (questionId: string) => void;
  }) {
    return (
      <div className="space-y-3">
        <h3
          className="text-sm font-semibold"
          style={{ color: 'var(--fg, #ccc)' }}
        >
          Review your answers
        </h3>
        {questions.map(q => {
          const answer = answers[q.id];
          const isSkipped = !answer || (Array.isArray(answer) && answer.length === 0);
          return (
            <div
              key={q.id}
              className="flex items-start justify-between gap-2 rounded-lg border px-3 py-2"
              style={{ borderColor: 'var(--border, #333)' }}
            >
              <div className="flex-1">
                <p
                  className="text-xs font-medium"
                  style={{ color: 'var(--fg-secondary, #999)' }}
                >
                  {q.text}
                </p>
                <p
                  className="mt-0.5 text-sm"
                  style={{
                    color: isSkipped
                      ? 'var(--fg-muted, #666)'
                      : 'var(--fg, #ccc)',
                    fontStyle: isSkipped ? 'italic' : 'normal',
                  }}
                >
                  {isSkipped
                    ? 'Skipped'
                    : Array.isArray(answer)
                    ? answer.join(', ')
                    : answer}
                </p>
              </div>
              <button
                onClick={() => onEdit(q.id)}
                className="flex-shrink-0 rounded px-2 py-1 text-[10px] font-medium transition-colors duration-150 hover:underline"
                style={{ color: 'var(--link, #6366f1)' }}
              >
                Edit
              </button>
            </div>
          );
        })}
      </div>
    );
  }

  // ── Main component ──

  interface QuestionWizardProps {
    questions: Question[];
    activeIndex: number;
  }

  export function QuestionWizard({ questions, activeIndex }: QuestionWizardProps) {
    const [currentIndex, setCurrentIndex] = useState(activeIndex);
    const [answers, setAnswers] = useState<Record<string, string | string[]>>(() => {
      // Initialize from existing answers on questions
      const initial: Record<string, string | string[]> = {};
      for (const q of questions) {
        if (q.answer !== undefined) {
          initial[q.id] = q.answer;
        }
      }
      return initial;
    });
    const [showSummary, setShowSummary] = useState(false);

    const currentQuestion = questions[currentIndex];
    const isFirst = currentIndex === 0;
    const isLast = currentIndex === questions.length - 1;

    // ── Answer management ──

    const setAnswer = useCallback((questionId: string, value: string | string[]) => {
      setAnswers(prev => ({ ...prev, [questionId]: value }));
    }, []);

    const getSelectedLabels = useCallback(
      (questionId: string): string[] => {
        const answer = answers[questionId];
        if (!answer) return [];
        return Array.isArray(answer) ? answer : [answer];
      },
      [answers],
    );

    // ── Navigation ──

    const goBack = useCallback(() => {
      if (showSummary) {
        setShowSummary(false);
        setCurrentIndex(questions.length - 1);
        return;
      }
      if (!isFirst) {
        setCurrentIndex(i => i - 1);
      }
    }, [isFirst, showSummary, questions.length]);

    const goNext = useCallback(() => {
      // Report answer to Kotlin bridge
      const answer = answers[currentQuestion.id];
      if (answer) {
        const optionsJson = JSON.stringify(
          Array.isArray(answer) ? answer : [answer],
        );
        window._questionAnswered?.(currentQuestion.id, optionsJson);
      }

      if (isLast) {
        setShowSummary(true);
      } else {
        setCurrentIndex(i => i + 1);
      }
    }, [isLast, answers, currentQuestion]);

    const skip = useCallback(() => {
      window._questionSkipped?.(currentQuestion.id);
      if (isLast) {
        setShowSummary(true);
      } else {
        setCurrentIndex(i => i + 1);
      }
    }, [isLast, currentQuestion]);

    // ── Bridge callbacks ──

    const handleChatAbout = useCallback(
      (label: string) => {
        const message = `Tell me more about "${label}" for question: ${currentQuestion.text}`;
        window._chatAboutOption?.(currentQuestion.id, label, message);
      },
      [currentQuestion],
    );

    const handleSubmit = useCallback(() => {
      window._questionsSubmitted?.();
    }, []);

    const handleCancel = useCallback(() => {
      window._questionsCancelled?.();
    }, []);

    const handleEdit = useCallback(
      (questionId: string) => {
        const index = questions.findIndex(q => q.id === questionId);
        if (index >= 0) {
          setShowSummary(false);
          setCurrentIndex(index);
          window._editQuestion?.(questionId);
        }
      },
      [questions],
    );

    // ── Single-select handler ──

    const handleSingleSelect = useCallback(
      (label: string) => {
        setAnswer(currentQuestion.id, label);
      },
      [currentQuestion, setAnswer],
    );

    // ── Multi-select handler ──

    const handleMultiToggle = useCallback(
      (label: string) => {
        const current = getSelectedLabels(currentQuestion.id);
        const updated = current.includes(label)
          ? current.filter(l => l !== label)
          : [...current, label];
        setAnswer(currentQuestion.id, updated);
      },
      [currentQuestion, getSelectedLabels, setAnswer],
    );

    const hasAnswer = useMemo(() => {
      const answer = answers[currentQuestion?.id];
      if (!answer) return false;
      return Array.isArray(answer) ? answer.length > 0 : answer.length > 0;
    }, [answers, currentQuestion]);

    return (
      <div
        className="my-3 rounded-xl border animate-[fade-in_220ms_ease-out]"
        style={{
          borderColor: 'var(--border, #333)',
          backgroundColor: 'var(--tool-bg, #1a1a2e)',
        }}
      >
        {/* ── Header ── */}
        <div className="flex items-center justify-between px-4 py-3 border-b" style={{ borderColor: 'var(--divider-subtle, #333)' }}>
          <div className="flex items-center gap-2">
            <svg className="h-4 w-4" style={{ color: 'var(--accent, #6366f1)' }} viewBox="0 0 16 16" fill="none">
              <circle cx="8" cy="8" r="6.5" stroke="currentColor" strokeWidth="1.5" />
              <path d="M6 6c0-1.1.9-2 2-2s2 .9 2 2c0 .7-.4 1.4-1 1.7V9" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
              <circle cx="8" cy="11" r="0.75" fill="currentColor" />
            </svg>
            <span className="text-sm font-semibold" style={{ color: 'var(--fg, #ccc)' }}>
              {showSummary ? 'Review Answers' : `Question ${currentIndex + 1} of ${questions.length}`}
            </span>
          </div>

          {/* Cancel button */}
          <button
            onClick={handleCancel}
            className="rounded p-1 transition-colors duration-150"
            style={{ color: 'var(--fg-muted, #666)' }}
            title="Cancel questions"
          >
            <svg className="h-4 w-4" viewBox="0 0 16 16" fill="none">
              <path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            </svg>
          </button>
        </div>

        {/* ── Step dots ── */}
        <StepDots
          total={questions.length}
          current={showSummary ? questions.length : currentIndex}
          onDotClick={i => {
            setShowSummary(false);
            setCurrentIndex(i);
          }}
        />

        {/* ── Content area ── */}
        <div className="px-4 pb-3">
          {showSummary ? (
            <SummaryPage
              questions={questions}
              answers={answers}
              onEdit={handleEdit}
            />
          ) : (
            <>
              {/* Question text */}
              <h3
                className="mb-3 text-sm font-medium leading-relaxed"
                style={{ color: 'var(--fg, #ccc)' }}
              >
                {currentQuestion.text}
              </h3>

              {/* Options */}
              <div className="space-y-2">
                {currentQuestion.type === 'single-select' &&
                  currentQuestion.options.map(option => (
                    <RadioOption
                      key={option.label}
                      option={option}
                      selected={getSelectedLabels(currentQuestion.id).includes(option.label)}
                      onSelect={() => handleSingleSelect(option.label)}
                      onChatAbout={handleChatAbout}
                      questionId={currentQuestion.id}
                    />
                  ))}

                {currentQuestion.type === 'multi-select' &&
                  currentQuestion.options.map(option => (
                    <CheckboxOption
                      key={option.label}
                      option={option}
                      selected={getSelectedLabels(currentQuestion.id).includes(option.label)}
                      onToggle={() => handleMultiToggle(option.label)}
                      onChatAbout={handleChatAbout}
                      questionId={currentQuestion.id}
                    />
                  ))}

                {currentQuestion.type === 'text' && (
                  <textarea
                    value={(answers[currentQuestion.id] as string) ?? ''}
                    onChange={e => setAnswer(currentQuestion.id, e.target.value)}
                    className="w-full rounded-lg border p-3 text-sm outline-none resize-none"
                    style={{
                      backgroundColor: 'var(--input-bg, #111)',
                      borderColor: 'var(--input-border, #444)',
                      color: 'var(--fg, #ccc)',
                      fontFamily: 'var(--font-body)',
                      minHeight: '80px',
                    }}
                    placeholder="Type your answer..."
                  />
                )}
              </div>
            </>
          )}
        </div>

        {/* ── Navigation buttons ── */}
        <div
          className="flex items-center justify-between border-t px-4 py-3"
          style={{ borderColor: 'var(--divider-subtle, #333)' }}
        >
          {/* Left side: Back */}
          <button
            onClick={goBack}
            disabled={isFirst && !showSummary}
            className="
              rounded-lg px-3 py-2 text-xs font-medium transition-all duration-150
              active:scale-[0.97]
              disabled:opacity-30 disabled:cursor-not-allowed
            "
            style={{
              color: 'var(--fg-secondary, #999)',
              border: '1px solid var(--border, #333)',
            }}
          >
            Back
          </button>

          {/* Right side: Skip + Next/Submit */}
          <div className="flex items-center gap-2">
            {!showSummary && (
              <button
                onClick={skip}
                className="
                  rounded-lg px-3 py-2 text-xs font-medium transition-all duration-150
                  active:scale-[0.97]
                "
                style={{ color: 'var(--fg-muted, #666)' }}
              >
                Skip
              </button>
            )}

            {showSummary ? (
              <button
                onClick={handleSubmit}
                className="
                  rounded-lg px-4 py-2 text-xs font-semibold text-white transition-all duration-150
                  active:scale-[0.97]
                  hover:brightness-110
                "
                style={{ backgroundColor: 'var(--success, #22c55e)' }}
              >
                Submit
              </button>
            ) : (
              <button
                onClick={goNext}
                disabled={!hasAnswer && currentQuestion.type !== 'text'}
                className="
                  rounded-lg px-4 py-2 text-xs font-semibold text-white transition-all duration-150
                  active:scale-[0.97]
                  hover:brightness-110
                  disabled:opacity-40 disabled:cursor-not-allowed
                "
                style={{ backgroundColor: 'var(--accent, #6366f1)' }}
              >
                {isLast ? 'Review' : 'Next'}
              </button>
            )}
          </div>
        </div>
      </div>
    );
  }
  ```

- [ ] **12.2 Wire QuestionWizard to chatStore**

  In the message rendering flow (e.g., `MessageList.tsx` or `App.tsx`), add a conditional render for questions:

  ```tsx
  import { useChatStore } from '@/stores/chatStore';
  import { QuestionWizard } from '@/components/agent/QuestionWizard';

  // Inside the component render:
  const questions = useChatStore(s => s.questions);
  const activeQuestionIndex = useChatStore(s => s.activeQuestionIndex);

  // After the message list / plan card, before input:
  {questions && questions.length > 0 && (
    <QuestionWizard
      questions={questions}
      activeIndex={activeQuestionIndex}
    />
  )}
  ```

  This renders the QuestionWizard when `chatStore.questions` is non-null (set by `showQuestions()` bridge call).

- [ ] **12.3 Verify question wizard**

  ```bash
  cd agent/webview && npm run dev
  ```

  In the browser console:
  1. Run `__mock.simulateQuestions()` (or equivalent mock method)
  2. Verify step dots appear with correct count, current dot highlighted
  3. Verify question text renders for the first question
  4. **Single-select:** Click a radio option — verify it highlights with accent border
  5. **Multi-select:** Click multiple checkboxes — verify they toggle independently
  6. Click "Chat about this" icon on an option — verify `_chatAboutOption` is called with correct args
  7. Click "Next" — verify navigation to next question and `_questionAnswered` is called
  8. Click "Back" — verify navigation to previous question
  9. Click "Skip" — verify `_questionSkipped` is called and advances to next question
  10. Click a step dot — verify direct navigation to that question
  11. Navigate to last question, click "Review" — verify summary page shows all answers
  12. Click "Edit" on a summary item — verify navigation back to that question and `_editQuestion` is called
  13. Click "Submit" on summary page — verify `_questionsSubmitted` is called
  14. Click cancel (X) button in header — verify `_questionsCancelled` is called
  15. Confirm no TypeScript errors

- [ ] **12.4 Commit**

  ```
  feat(agent): implement QuestionWizard with multi-page navigation, radio/checkbox/text, chat-about, summary, and all 6 bridge callbacks
  ```

---

## Phase 4: Rich Visualizations

### Task 13: RichBlock Wrapper

**Objective:** Create the unified `RichBlock` wrapper component that ALL rich visualizations (mermaid, chart, flow, math, diff, interactive HTML) render inside. Includes skeleton loading, action buttons (Copy, Expand, Open in Tab), configurable max-height with "Show more" gradient, error boundary with retry, settings-awareness, and the `FullscreenOverlay` modal. Also create the `useRichBlock` lazy-loading hook.

**Estimated time:** 25-30 minutes

#### Steps

- [ ] **13.1 Create `hooks/useRichBlock.ts`**

  Create `agent/webview/src/hooks/useRichBlock.ts` — lazy loading hook that wraps `React.lazy` + `Suspense` with error state and retry capability:

  ```typescript
  import { useState, useCallback, useEffect, useRef, ComponentType } from 'react';

  interface UseRichBlockResult<P> {
    Component: ComponentType<P> | null;
    isLoading: boolean;
    error: Error | null;
    retry: () => void;
  }

  /**
   * Lazy-loads a visualization component via dynamic import().
   * Returns loading/error/retry state for the RichBlock wrapper to consume.
   *
   * @param loader - Dynamic import function, e.g. () => import('./MermaidDiagram')
   * @param exportName - Named export to extract (default: 'default')
   */
  export function useRichBlock<P>(
    loader: () => Promise<{ [key: string]: ComponentType<P> }>,
    exportName: string = 'default',
  ): UseRichBlockResult<P> {
    const [Component, setComponent] = useState<ComponentType<P> | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<Error | null>(null);
    const attemptRef = useRef(0);

    const load = useCallback(() => {
      setIsLoading(true);
      setError(null);
      attemptRef.current += 1;

      loader()
        .then((module) => {
          const Comp = module[exportName] as ComponentType<P> | undefined;
          if (!Comp) {
            throw new Error(`Export "${exportName}" not found in module`);
          }
          setComponent(() => Comp);
          setIsLoading(false);
        })
        .catch((err) => {
          setError(err instanceof Error ? err : new Error(String(err)));
          setIsLoading(false);
        });
    }, [loader, exportName]);

    useEffect(() => {
      load();
    }, [load]);

    const retry = useCallback(() => {
      load();
    }, [load]);

    return { Component, isLoading, error, retry };
  }
  ```

- [ ] **13.2 Create `components/common/FullscreenOverlay.tsx`**

  Create `agent/webview/src/components/common/FullscreenOverlay.tsx` — modal overlay with dimmed backdrop, close on Escape/click-outside, scrollable content area:

  ```tsx
  import { useEffect, useRef, useCallback, type ReactNode } from 'react';

  interface FullscreenOverlayProps {
    open: boolean;
    onClose: () => void;
    title?: string;
    children: ReactNode;
  }

  export function FullscreenOverlay({ open, onClose, title, children }: FullscreenOverlayProps) {
    const overlayRef = useRef<HTMLDivElement>(null);
    const contentRef = useRef<HTMLDivElement>(null);

    // Close on Escape key
    useEffect(() => {
      if (!open) return;

      function handleKeyDown(e: KeyboardEvent) {
        if (e.key === 'Escape') {
          e.stopPropagation();
          onClose();
        }
      }

      document.addEventListener('keydown', handleKeyDown);
      return () => document.removeEventListener('keydown', handleKeyDown);
    }, [open, onClose]);

    // Close on click outside content
    const handleBackdropClick = useCallback(
      (e: React.MouseEvent<HTMLDivElement>) => {
        if (e.target === overlayRef.current) {
          onClose();
        }
      },
      [onClose],
    );

    // Prevent body scroll when overlay is open
    useEffect(() => {
      if (open) {
        document.body.style.overflow = 'hidden';
        return () => {
          document.body.style.overflow = '';
        };
      }
    }, [open]);

    if (!open) return null;

    return (
      <div
        ref={overlayRef}
        className="fixed inset-0 z-50 flex items-center justify-center
                   bg-black/60 backdrop-blur-[2px]
                   animate-[fadeIn_200ms_ease-out]"
        onClick={handleBackdropClick}
        role="dialog"
        aria-modal="true"
        aria-label={title ?? 'Expanded view'}
      >
        <div
          ref={contentRef}
          className="relative m-4 flex max-h-[90vh] max-w-[90vw] flex-col
                     overflow-hidden rounded-lg border border-[var(--border)]
                     bg-[var(--bg)] shadow-2xl
                     animate-[overlayIn_200ms_ease-out]"
        >
          {/* Header */}
          <div className="flex items-center justify-between border-b border-[var(--border)] px-4 py-2">
            {title && (
              <span className="text-[12px] font-medium text-[var(--fg-secondary)]">{title}</span>
            )}
            <button
              onClick={onClose}
              className="ml-auto flex h-6 w-6 items-center justify-center rounded
                         text-[var(--fg-muted)] transition-colors duration-150
                         hover:bg-[var(--hover-overlay-strong)] hover:text-[var(--fg)]"
              aria-label="Close"
            >
              <svg width="14" height="14" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5">
                <path d="M3 3l8 8M11 3l-8 8" />
              </svg>
            </button>
          </div>

          {/* Scrollable content */}
          <div className="flex-1 overflow-auto p-4">
            {children}
          </div>
        </div>
      </div>
    );
  }
  ```

  Add the keyframes to `src/index.css`:

  ```css
  @keyframes fadeIn {
    from { opacity: 0; }
    to { opacity: 1; }
  }

  @keyframes overlayIn {
    from { opacity: 0; transform: scale(0.97); }
    to { opacity: 1; transform: scale(1); }
  }
  ```

- [ ] **13.3 Create `components/rich/RichBlock.tsx`**

  Create `agent/webview/src/components/rich/RichBlock.tsx` — the unified wrapper for ALL visualizations:

  ```tsx
  import { useState, useCallback, type ReactNode } from 'react';
  import { useSettingsStore } from '@/stores/settingsStore';
  import { useThemeStore } from '@/stores/themeStore';
  import { FullscreenOverlay } from '@/components/common/FullscreenOverlay';
  import type { VisualizationType } from '@/bridge/types';

  /** Icon + label for each visualization type */
  const TYPE_META: Record<VisualizationType, { label: string; icon: string }> = {
    mermaid:         { label: 'Diagram',       icon: 'M3 3h4v4H3V3zm7 0h4v4h-4V3zm-7 7h4v4H3v-4zm7 0h4v4h-4v-4z' },
    chart:           { label: 'Chart',         icon: 'M3 13V5h2v8H3zm4 0V2h2v11H7zm4 0V7h2v6h-2z' },
    flow:            { label: 'Flow',          icon: 'M2 4h4v3H2V4zm8 0h4v3h-4V4zm-4 6h4v3H6v-3zM4 7v2h1v1H4h2V8.5L5 7H4zm6 0l-1 1.5V10h2v-1H10V7z' },
    math:            { label: 'Math',          icon: 'M3 4l3 8M6 4L3 12M8 7h5M8 9h5' },
    diff:            { label: 'Diff',          icon: 'M3 3h10v10H3V3zm3 3v1h4V6H6zm0 3v1h3V9H6z' },
    interactiveHtml: { label: 'Interactive',   icon: 'M4 3l-3 4.5L4 12M10 3l3 4.5L10 12M8 2L6 13' },
  };

  interface RichBlockProps {
    type: VisualizationType;
    rawSource: string;
    isLoading?: boolean;
    error?: Error | null;
    onRetry?: () => void;
    children: ReactNode;
  }

  export function RichBlock({
    type,
    rawSource,
    isLoading = false,
    error = null,
    onRetry,
    children,
  }: RichBlockProps) {
    const config = useSettingsStore(s => s.visualizations[type]);
    const isDark = useThemeStore(s => s.isDark);

    const [expanded, setExpanded] = useState(config.defaultExpanded);
    const [overlayOpen, setOverlayOpen] = useState(false);
    const [renderRequested, setRenderRequested] = useState(config.autoRender);

    const meta = TYPE_META[type];
    const effectiveMaxHeight = expanded ? undefined : config.maxHeight || undefined;

    // ── Disabled check ──
    if (!config.enabled) return null;

    // ── Auto-render gate ──
    if (!renderRequested) {
      return (
        <div className="my-2 rounded-md border border-[var(--border)] bg-[var(--tool-bg)] p-4 text-center">
          <button
            onClick={() => setRenderRequested(true)}
            className="rounded px-3 py-1.5 text-[12px] font-medium
                       border border-[var(--border)] bg-[var(--bg)]
                       text-[var(--fg-secondary)] transition-colors duration-150
                       hover:bg-[var(--hover-overlay-strong)] hover:text-[var(--fg)]"
          >
            Click to render {meta.label.toLowerCase()}
          </button>
        </div>
      );
    }

    // ── Copy raw source ──
    const handleCopy = useCallback(() => {
      navigator.clipboard.writeText(rawSource);
    }, [rawSource]);

    // ── Open in IDE tab ──
    const handleOpenInTab = useCallback(() => {
      if (window._navigateToFile) {
        // Create a temp identifier for the content
        window._navigateToFile(`__rich_${type}:${rawSource.substring(0, 50)}`);
      }
    }, [type, rawSource]);

    return (
      <>
        <div
          className="my-2 rounded-md border border-[var(--border)] bg-[var(--tool-bg)]
                     overflow-hidden animate-[fadeIn_200ms_ease-out]"
          key={isDark ? 'dark' : 'light'}
        >
          {/* ── Header bar ── */}
          <div className="flex items-center justify-between border-b border-[var(--border)] px-3 py-1.5">
            <div className="flex items-center gap-1.5">
              <svg
                width="14" height="14" viewBox="0 0 14 14"
                fill="none" stroke="currentColor" strokeWidth="1"
                className="text-[var(--fg-muted)]"
              >
                <path d={meta.icon} />
              </svg>
              <span className="text-[10px] font-medium uppercase tracking-wide text-[var(--fg-muted)]">
                {meta.label}
              </span>
            </div>

            {/* Action buttons */}
            <div className="flex items-center gap-1">
              {/* Copy */}
              <button
                onClick={handleCopy}
                className="flex h-6 w-6 items-center justify-center rounded
                           text-[var(--fg-muted)] transition-colors duration-150
                           hover:bg-[var(--hover-overlay-strong)] hover:text-[var(--fg)]"
                title="Copy source"
                aria-label="Copy source"
              >
                <svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.2">
                  <rect x="4" y="4" width="7" height="7" rx="1" />
                  <path d="M8 4V2.5A1.5 1.5 0 006.5 1h-4A1.5 1.5 0 001 2.5v4A1.5 1.5 0 002.5 8H4" />
                </svg>
              </button>

              {/* Expand (fullscreen overlay) */}
              <button
                onClick={() => setOverlayOpen(true)}
                className="flex h-6 w-6 items-center justify-center rounded
                           text-[var(--fg-muted)] transition-colors duration-150
                           hover:bg-[var(--hover-overlay-strong)] hover:text-[var(--fg)]"
                title="Expand"
                aria-label="Expand"
              >
                <svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.2">
                  <path d="M7 1h4v4M5 11H1V7M11 1L7 5M1 11l4-4" />
                </svg>
              </button>

              {/* Open in Tab */}
              <button
                onClick={handleOpenInTab}
                className="flex h-6 w-6 items-center justify-center rounded
                           text-[var(--fg-muted)] transition-colors duration-150
                           hover:bg-[var(--hover-overlay-strong)] hover:text-[var(--fg)]"
                title="Open in tab"
                aria-label="Open in tab"
              >
                <svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.2">
                  <path d="M9 6.5V10a1 1 0 01-1 1H2a1 1 0 01-1-1V4a1 1 0 011-1h3.5M7 1h4v4M11 1L5.5 6.5" />
                </svg>
              </button>
            </div>
          </div>

          {/* ── Content area ── */}
          <div className="relative">
            {/* Loading skeleton */}
            {isLoading && (
              <div className="flex flex-col gap-2 p-4">
                <div className="h-4 w-3/4 animate-pulse rounded bg-[var(--fg-muted)]/10" />
                <div className="h-4 w-1/2 animate-pulse rounded bg-[var(--fg-muted)]/10" />
                <div className="h-32 w-full animate-pulse rounded bg-[var(--fg-muted)]/10" />
              </div>
            )}

            {/* Error state */}
            {error && !isLoading && (
              <div className="p-4">
                <div className="mb-2 text-[12px] text-[var(--error)]">
                  Failed to render {meta.label.toLowerCase()}: {error.message}
                </div>
                <pre className="mb-3 max-h-[200px] overflow-auto rounded border border-[var(--border)]
                                bg-[var(--code-bg)] p-3 font-[var(--font-mono)] text-[11px] text-[var(--fg-secondary)]">
                  {rawSource}
                </pre>
                {onRetry && (
                  <button
                    onClick={onRetry}
                    className="rounded px-3 py-1 text-[12px] font-medium
                               border border-[var(--border)] bg-[var(--bg)]
                               text-[var(--fg-secondary)] transition-colors duration-150
                               hover:bg-[var(--hover-overlay-strong)] hover:text-[var(--fg)]"
                  >
                    Retry
                  </button>
                )}
              </div>
            )}

            {/* Rendered content */}
            {!isLoading && !error && (
              <div
                className="overflow-hidden transition-[max-height] duration-300"
                style={{
                  maxHeight: effectiveMaxHeight ? `${effectiveMaxHeight}px` : undefined,
                }}
              >
                <div className="p-3">
                  {children}
                </div>
              </div>
            )}

            {/* "Show more" gradient fade */}
            {!isLoading && !error && !expanded && effectiveMaxHeight && (
              <div className="absolute bottom-0 left-0 right-0">
                <div className="h-12 bg-gradient-to-t from-[var(--tool-bg)] to-transparent" />
                <div className="flex justify-center bg-[var(--tool-bg)] pb-2">
                  <button
                    onClick={() => setExpanded(true)}
                    className="rounded px-3 py-1 text-[11px] font-medium
                               text-[var(--link)] transition-colors duration-150
                               hover:text-[var(--fg)] hover:underline"
                  >
                    Show more
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>

        {/* ── Fullscreen overlay ── */}
        <FullscreenOverlay
          open={overlayOpen}
          onClose={() => setOverlayOpen(false)}
          title={meta.label}
        >
          {children}
        </FullscreenOverlay>
      </>
    );
  }
  ```

- [ ] **13.4 Verify RichBlock renders**

  ```bash
  cd agent/webview && npm run dev
  ```

  In the browser console, test each RichBlock state:
  1. Import and render `<RichBlock type="mermaid" rawSource="graph TD; A-->B">...</RichBlock>` — verify header bar shows "DIAGRAM" with icon
  2. Click Copy — verify `rawSource` is on the clipboard
  3. Click Expand — verify `FullscreenOverlay` opens with dimmed backdrop, content visible
  4. Press Escape — verify overlay closes
  5. Click outside overlay content — verify overlay closes
  6. Set `settingsStore.updateVisualization('mermaid', { autoRender: false })` — verify "Click to render diagram" button appears instead of content
  7. Click the render button — verify content appears
  8. Set `settingsStore.updateVisualization('mermaid', { enabled: false })` — verify component returns null
  9. Render with `isLoading={true}` — verify skeleton shimmer lines appear
  10. Render with `error={new Error('test')}` — verify raw source shown with "Retry" button
  11. Click "Retry" — verify `onRetry` callback fires
  12. Set `maxHeight: 100` with long content — verify gradient fade and "Show more" button
  13. Click "Show more" — verify content expands fully
  14. Confirm no TypeScript errors

- [ ] **13.5 Commit**

  ```
  feat(agent): implement RichBlock wrapper with skeleton, actions, settings-awareness, error boundary, and FullscreenOverlay
  ```

### Task 14: Code Blocks (Shiki)

**Objective:** Install prompt-kit's CodeBlock component, integrate Shiki for VS Code-quality syntax highlighting with 15 pre-loaded languages and lazy-load for additional languages, and wire into `MarkdownRenderer` as the custom code block renderer. Replaces the placeholder code block from Task 6.

**Estimated time:** 20-25 minutes

#### Steps

- [ ] **14.1 Install prompt-kit CodeBlock**

  Run from `agent/webview/`:

  ```bash
  npx shadcn add "https://prompt-kit.com/c/code-block.json"
  ```

  This copies the CodeBlock component into the project. It provides the header bar with language label and copy button. We will extend it with Shiki highlighting and additional action buttons.

- [ ] **14.2 Create `hooks/useShiki.ts`**

  Create `agent/webview/src/hooks/useShiki.ts` — singleton Shiki highlighter initialization with curated language set:

  ```typescript
  import { useEffect, useState, useRef } from 'react';
  import type { Highlighter, BundledLanguage } from 'shiki';

  /**
   * Pre-loaded languages (~180 KB total).
   * These are bundled at build time — no network fetch at runtime.
   */
  const PRELOADED_LANGUAGES: BundledLanguage[] = [
    'kotlin', 'java', 'python', 'typescript', 'javascript',
    'json', 'yaml', 'xml', 'sql', 'bash',
    'html', 'css', 'go', 'rust', 'markdown',
  ];

  /** Shiki themes matching IDE light/dark */
  const DARK_THEME = 'vitesse-dark';
  const LIGHT_THEME = 'vitesse-light';

  let highlighterPromise: Promise<Highlighter> | null = null;
  let highlighterInstance: Highlighter | null = null;

  /**
   * Returns the singleton Shiki highlighter, creating it on first call.
   * Pre-loads 15 common languages. Additional languages are loaded on demand.
   */
  function getHighlighter(): Promise<Highlighter> {
    if (highlighterInstance) return Promise.resolve(highlighterInstance);
    if (highlighterPromise) return highlighterPromise;

    highlighterPromise = import('shiki').then(async ({ createHighlighter }) => {
      const hl = await createHighlighter({
        themes: [DARK_THEME, LIGHT_THEME],
        langs: PRELOADED_LANGUAGES,
      });
      highlighterInstance = hl;
      return hl;
    });

    return highlighterPromise;
  }

  interface UseShikiResult {
    highlighter: Highlighter | null;
    isLoading: boolean;
    highlight: (code: string, language: string, isDark: boolean) => string;
  }

  /**
   * Hook that provides the Shiki highlighter singleton.
   * Pre-loads 15 languages on first mount. Additional languages
   * are loaded on demand when `highlight()` encounters an unknown language.
   */
  export function useShiki(): UseShikiResult {
    const [highlighter, setHighlighter] = useState<Highlighter | null>(highlighterInstance);
    const [isLoading, setIsLoading] = useState(!highlighterInstance);
    const loadingLanguages = useRef<Set<string>>(new Set());

    useEffect(() => {
      if (highlighterInstance) {
        setHighlighter(highlighterInstance);
        setIsLoading(false);
        return;
      }

      getHighlighter().then(hl => {
        setHighlighter(hl);
        setIsLoading(false);
      });
    }, []);

    /**
     * Highlights code with the given language and theme.
     * If the language is not loaded, returns unhighlighted HTML
     * and triggers an async load for next render.
     */
    function highlight(code: string, language: string, isDark: boolean): string {
      if (!highlighter) {
        // Highlighter not ready — return escaped plain text
        return `<pre><code>${escapeHtml(code)}</code></pre>`;
      }

      const theme = isDark ? DARK_THEME : LIGHT_THEME;
      const loadedLangs = highlighter.getLoadedLanguages();

      if (loadedLangs.includes(language as BundledLanguage)) {
        return highlighter.codeToHtml(code, { lang: language, theme });
      }

      // Language not loaded — try to load it asynchronously
      if (!loadingLanguages.current.has(language)) {
        loadingLanguages.current.add(language);
        highlighter.loadLanguage(language as BundledLanguage)
          .then(() => {
            // Force a re-render by updating state
            setHighlighter(highlighter);
          })
          .catch(() => {
            // Language not available — will fall back to plain text
            loadingLanguages.current.delete(language);
          });
      }

      // Fallback: render as plain text until language loads
      return highlighter.codeToHtml(code, { lang: 'text', theme });
    }

    return { highlighter, isLoading, highlight };
  }

  function escapeHtml(text: string): string {
    return text
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
  }
  ```

- [ ] **14.3 Create `components/markdown/CodeBlock.tsx`**

  Create `agent/webview/src/components/markdown/CodeBlock.tsx` — full code block component with Shiki highlighting, line numbers, copy feedback, and action buttons:

  ```tsx
  import { useState, useCallback, useMemo, memo } from 'react';
  import { useShiki } from '@/hooks/useShiki';
  import { useThemeStore } from '@/stores/themeStore';

  interface CodeBlockProps {
    code: string;
    language: string;
    isStreaming?: boolean;
    showLineNumbers?: boolean;
  }

  export const CodeBlock = memo(function CodeBlock({
    code,
    language,
    isStreaming = false,
    showLineNumbers = false,
  }: CodeBlockProps) {
    const { highlight, isLoading } = useShiki();
    const isDark = useThemeStore(s => s.isDark);
    const [copied, setCopied] = useState(false);
    const [lineNumbers, setLineNumbers] = useState(showLineNumbers);

    // ── Highlighted HTML ──
    const highlightedHtml = useMemo(() => {
      if (isLoading || !code) return '';
      return highlight(code, language || 'text', isDark);
    }, [code, language, isDark, isLoading, highlight]);

    // ── Copy with checkmark feedback ──
    const handleCopy = useCallback(() => {
      navigator.clipboard.writeText(code);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    }, [code]);

    // ── Apply to editor (via bridge) ──
    const handleApply = useCallback(() => {
      if (window._navigateToFile) {
        // Signal the Kotlin side to apply this code block
        window._navigateToFile(`__apply_code:${language}`);
      }
    }, [language]);

    // ── Line number rendering ──
    const codeWithLineNumbers = useMemo(() => {
      if (!lineNumbers || !code) return null;
      const lines = code.split('\n');
      const gutterWidth = String(lines.length).length;
      return lines.map((_, i) => (
        <span
          key={i}
          className="inline-block select-none text-right text-[var(--fg-muted)]/40 pr-3"
          style={{ width: `${gutterWidth + 1}ch` }}
        >
          {i + 1}
        </span>
      ));
    }, [code, lineNumbers]);

    return (
      <div className="relative my-2 rounded-md border border-[var(--border)] bg-[var(--code-bg)] overflow-hidden">
        {/* ── Header ── */}
        <div className="flex items-center justify-between border-b border-[var(--border)] px-3 py-1">
          <span className="text-[10px] font-medium uppercase tracking-wide text-[var(--fg-muted)]">
            {language || 'code'}
          </span>

          <div className="flex items-center gap-1">
            {/* Line numbers toggle */}
            <button
              onClick={() => setLineNumbers(v => !v)}
              className={`flex h-5 w-5 items-center justify-center rounded text-[10px]
                         transition-colors duration-150
                         ${lineNumbers
                           ? 'text-[var(--fg)] bg-[var(--hover-overlay-strong)]'
                           : 'text-[var(--fg-muted)] hover:text-[var(--fg)] hover:bg-[var(--hover-overlay)]'
                         }`}
              title="Toggle line numbers"
              aria-label="Toggle line numbers"
            >
              #
            </button>

            {/* Copy button with checkmark feedback */}
            <button
              onClick={handleCopy}
              className="flex h-5 w-5 items-center justify-center rounded
                         text-[var(--fg-muted)] transition-all duration-150
                         hover:bg-[var(--hover-overlay-strong)] hover:text-[var(--fg)]"
              title={copied ? 'Copied!' : 'Copy code'}
              aria-label={copied ? 'Copied!' : 'Copy code'}
            >
              {copied ? (
                <svg width="12" height="12" viewBox="0 0 12 12" fill="none"
                     stroke="var(--success)" strokeWidth="1.5"
                     className="transition-transform duration-150 scale-110">
                  <path d="M2.5 6.5L5 9l4.5-6" />
                </svg>
              ) : (
                <svg width="12" height="12" viewBox="0 0 12 12" fill="none"
                     stroke="currentColor" strokeWidth="1.2">
                  <rect x="4" y="4" width="7" height="7" rx="1" />
                  <path d="M8 4V2.5A1.5 1.5 0 006.5 1h-4A1.5 1.5 0 001 2.5v4A1.5 1.5 0 002.5 8H4" />
                </svg>
              )}
            </button>

            {/* Apply button */}
            <button
              onClick={handleApply}
              className="flex h-5 items-center gap-0.5 rounded px-1
                         text-[10px] font-medium text-[var(--fg-muted)]
                         transition-colors duration-150
                         hover:bg-[var(--hover-overlay-strong)] hover:text-[var(--fg)]"
              title="Apply to editor"
              aria-label="Apply to editor"
            >
              <svg width="10" height="10" viewBox="0 0 10 10" fill="none"
                   stroke="currentColor" strokeWidth="1.2">
                <path d="M1 5h6M5 3l2 2-2 2" />
              </svg>
              Apply
            </button>
          </div>
        </div>

        {/* ── Code content ── */}
        <div className="overflow-x-auto">
          {isLoading ? (
            /* Skeleton while Shiki loads */
            <div className="flex flex-col gap-1.5 p-3">
              <div className="h-3.5 w-3/4 animate-pulse rounded bg-[var(--fg-muted)]/10" />
              <div className="h-3.5 w-1/2 animate-pulse rounded bg-[var(--fg-muted)]/10" />
              <div className="h-3.5 w-5/6 animate-pulse rounded bg-[var(--fg-muted)]/10" />
            </div>
          ) : (
            <div className="flex">
              {/* Line numbers gutter */}
              {lineNumbers && codeWithLineNumbers && (
                <div className="flex flex-col border-r border-[var(--border)]
                                bg-[var(--code-bg)] py-3 pl-2 font-[var(--font-mono)] text-[12px] leading-[1.6]">
                  {codeWithLineNumbers}
                </div>
              )}

              {/* Highlighted code */}
              <div
                className="flex-1 p-3 font-[var(--font-mono)] text-[12px] leading-[1.6]
                           [&_pre]:m-0 [&_pre]:bg-transparent [&_pre]:p-0
                           [&_code]:bg-transparent"
                dangerouslySetInnerHTML={{ __html: highlightedHtml }}
              />
            </div>
          )}

          {/* Streaming skeleton for open code fence */}
          {isStreaming && (
            <div className="border-t border-[var(--border)]/30 px-3 py-2">
              <span className="inline-block h-3 w-24 animate-pulse rounded bg-[var(--fg-muted)]/20" />
            </div>
          )}
        </div>
      </div>
    );
  });
  ```

- [ ] **14.4 Wire CodeBlock into MarkdownRenderer**

  Edit `agent/webview/src/components/markdown/MarkdownRenderer.tsx` — replace the placeholder block code rendering with the Shiki-powered `CodeBlock`. Update the `code` component override:

  ```tsx
  // Add import at top:
  import { CodeBlock } from '@/components/markdown/CodeBlock';

  // Replace the existing block code branch in the `code` component:
  code({ className, children, ...props }) {
    const isBlock = className?.startsWith('language-');
    if (isBlock) {
      const language = className?.replace('language-', '') ?? '';
      const codeString = String(children).replace(/\n$/, '');
      return (
        <CodeBlock
          code={codeString}
          language={language}
          isStreaming={isStreaming}
        />
      );
    }
    // Inline code (unchanged)
    return (
      <code
        className="rounded bg-[var(--code-bg)] px-1 py-0.5 font-[var(--font-mono,'JetBrains_Mono',monospace)] text-[12px]"
        {...props}
      >
        {children}
      </code>
    );
  },
  ```

- [ ] **14.5 Verify Shiki code blocks**

  ```bash
  cd agent/webview && npm run dev
  ```

  In the browser console:
  1. Send a message containing a Kotlin code block — verify syntax highlighting with VS Code-quality colors
  2. Send a Python code block — verify different language highlighting
  3. Send a JSON code block — verify JSON highlighting
  4. Send a code block with an unknown language (e.g., `haskell`) — verify it renders as plain text initially, then re-renders with highlighting after async language load
  5. Click the Copy button — verify code is on clipboard and checkmark appears for ~1.5s
  6. Click the `#` button — verify line numbers toggle on/off
  7. Click "Apply" — verify `_navigateToFile` is called
  8. During streaming, verify skeleton shimmer shows for open code fences
  9. Switch theme (dark/light) — verify Shiki re-highlights with the matching theme
  10. Confirm no TypeScript errors

- [ ] **14.6 Commit**

  ```
  feat(agent): integrate Shiki syntax highlighting with 15 pre-loaded languages, copy feedback, line numbers, and Apply button
  ```

### Task 15: Mermaid Diagrams + Flow Diagrams

**Objective:** Create `MermaidDiagram` and `FlowDiagram` components that lazy-load their respective libraries (mermaid, dagre) via dynamic `import()`, render inside `RichBlock`, support zoom/pan interactions, and respond to IDE theme changes.

**Estimated time:** 25-30 minutes

#### Steps

- [ ] **15.1 Create `components/rich/MermaidDiagram.tsx`**

  Create `agent/webview/src/components/rich/MermaidDiagram.tsx` — lazy-loads mermaid, renders inside `RichBlock`, supports zoom/pan and theme switching:

  ```tsx
  import { useEffect, useRef, useState, useCallback, memo } from 'react';
  import { RichBlock } from './RichBlock';
  import { useThemeStore } from '@/stores/themeStore';

  interface MermaidDiagramProps {
    source: string;
  }

  let mermaidModule: typeof import('mermaid') | null = null;
  let mermaidLoadPromise: Promise<typeof import('mermaid')> | null = null;
  let diagramCounter = 0;

  function loadMermaid(): Promise<typeof import('mermaid')> {
    if (mermaidModule) return Promise.resolve(mermaidModule);
    if (mermaidLoadPromise) return mermaidLoadPromise;
    mermaidLoadPromise = import('mermaid').then(m => {
      mermaidModule = m;
      return m;
    });
    return mermaidLoadPromise;
  }

  export const MermaidDiagram = memo(function MermaidDiagram({ source }: MermaidDiagramProps) {
    const containerRef = useRef<HTMLDivElement>(null);
    const isDark = useThemeStore(s => s.isDark);

    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<Error | null>(null);
    const [svgHtml, setSvgHtml] = useState('');

    // ── Zoom/pan state ──
    const [transform, setTransform] = useState({ scale: 1, x: 0, y: 0 });
    const isPanning = useRef(false);
    const panStart = useRef({ x: 0, y: 0 });

    // ── Render diagram ──
    const render = useCallback(async () => {
      setIsLoading(true);
      setError(null);

      try {
        const mermaid = await loadMermaid();

        mermaid.default.initialize({
          startOnLoad: false,
          theme: isDark ? 'dark' : 'default',
          securityLevel: 'strict',
          fontFamily: 'var(--font-mono)',
        });

        const id = `mermaid-${++diagramCounter}`;
        const { svg } = await mermaid.default.render(id, source);
        setSvgHtml(svg);
        setIsLoading(false);
      } catch (err) {
        setError(err instanceof Error ? err : new Error(String(err)));
        setIsLoading(false);
      }
    }, [source, isDark]);

    useEffect(() => {
      render();
    }, [render]);

    // ── Zoom via scroll ──
    const handleWheel = useCallback((e: React.WheelEvent) => {
      e.preventDefault();
      setTransform(prev => {
        const delta = e.deltaY > 0 ? 0.9 : 1.1;
        const newScale = Math.max(0.25, Math.min(4, prev.scale * delta));
        return { ...prev, scale: newScale };
      });
    }, []);

    // ── Pan via drag ──
    const handleMouseDown = useCallback((e: React.MouseEvent) => {
      if (e.button !== 0) return;
      isPanning.current = true;
      panStart.current = { x: e.clientX - transform.x, y: e.clientY - transform.y };
    }, [transform]);

    const handleMouseMove = useCallback((e: React.MouseEvent) => {
      if (!isPanning.current) return;
      setTransform(prev => ({
        ...prev,
        x: e.clientX - panStart.current.x,
        y: e.clientY - panStart.current.y,
      }));
    }, []);

    const handleMouseUp = useCallback(() => {
      isPanning.current = false;
    }, []);

    // ── Reset zoom ──
    const handleDoubleClick = useCallback(() => {
      setTransform({ scale: 1, x: 0, y: 0 });
    }, []);

    return (
      <RichBlock
        type="mermaid"
        rawSource={source}
        isLoading={isLoading}
        error={error}
        onRetry={render}
      >
        <div
          ref={containerRef}
          className="cursor-grab overflow-hidden active:cursor-grabbing"
          onWheel={handleWheel}
          onMouseDown={handleMouseDown}
          onMouseMove={handleMouseMove}
          onMouseUp={handleMouseUp}
          onMouseLeave={handleMouseUp}
          onDoubleClick={handleDoubleClick}
        >
          <div
            className="transition-transform duration-75 origin-center
                       [&_svg]:max-w-full [&_svg]:h-auto"
            style={{
              transform: `translate(${transform.x}px, ${transform.y}px) scale(${transform.scale})`,
            }}
            dangerouslySetInnerHTML={{ __html: svgHtml }}
          />
        </div>

        {/* Zoom indicator */}
        {transform.scale !== 1 && (
          <div className="absolute bottom-2 right-2 rounded bg-[var(--bg)]/80 px-2 py-0.5
                          text-[10px] text-[var(--fg-muted)] border border-[var(--border)]">
            {Math.round(transform.scale * 100)}%
          </div>
        )}
      </RichBlock>
    );
  });
  ```

- [ ] **15.2 Create `components/rich/FlowDiagram.tsx`**

  Create `agent/webview/src/components/rich/FlowDiagram.tsx` — lazy-loads dagre, parses JSON config `{nodes, edges, direction}`, renders SVG with animated edges and hover tooltips:

  ```tsx
  import { useEffect, useRef, useState, useCallback, memo } from 'react';
  import { RichBlock } from './RichBlock';
  import { useThemeStore } from '@/stores/themeStore';

  interface FlowNode {
    id: string;
    label: string;
    tooltip?: string;
    color?: string;
  }

  interface FlowEdge {
    from: string;
    to: string;
    label?: string;
  }

  interface FlowConfig {
    nodes: FlowNode[];
    edges: FlowEdge[];
    direction?: 'TB' | 'LR' | 'BT' | 'RL';
  }

  interface FlowDiagramProps {
    config: FlowConfig;
    rawSource: string;
  }

  let dagreModule: typeof import('dagre') | null = null;
  let dagreLoadPromise: Promise<typeof import('dagre')> | null = null;

  function loadDagre(): Promise<typeof import('dagre')> {
    if (dagreModule) return Promise.resolve(dagreModule);
    if (dagreLoadPromise) return dagreLoadPromise;
    dagreLoadPromise = import('dagre').then(m => {
      dagreModule = m;
      return m;
    });
    return dagreLoadPromise;
  }

  interface LayoutNode extends FlowNode {
    x: number;
    y: number;
    width: number;
    height: number;
  }

  interface LayoutEdge extends FlowEdge {
    points: Array<{ x: number; y: number }>;
  }

  export const FlowDiagram = memo(function FlowDiagram({ config, rawSource }: FlowDiagramProps) {
    const isDark = useThemeStore(s => s.isDark);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<Error | null>(null);
    const [layoutNodes, setLayoutNodes] = useState<LayoutNode[]>([]);
    const [layoutEdges, setLayoutEdges] = useState<LayoutEdge[]>([]);
    const [svgSize, setSvgSize] = useState({ width: 0, height: 0 });
    const [hoveredNode, setHoveredNode] = useState<string | null>(null);

    // ── Zoom/pan state ──
    const [transform, setTransform] = useState({ scale: 1, x: 0, y: 0 });
    const isPanning = useRef(false);
    const panStart = useRef({ x: 0, y: 0 });

    // ── Layout with dagre ──
    const layout = useCallback(async () => {
      setIsLoading(true);
      setError(null);

      try {
        const dagre = await loadDagre();
        const g = new dagre.default.graphlib.Graph();
        g.setGraph({
          rankdir: config.direction ?? 'TB',
          marginx: 20,
          marginy: 20,
          ranksep: 50,
          nodesep: 30,
        });
        g.setDefaultEdgeLabel(() => ({}));

        const nodeWidth = 140;
        const nodeHeight = 40;

        for (const node of config.nodes) {
          g.setNode(node.id, { label: node.label, width: nodeWidth, height: nodeHeight });
        }

        for (const edge of config.edges) {
          g.setEdge(edge.from, edge.to, { label: edge.label ?? '' });
        }

        dagre.default.layout(g);

        const lnodes: LayoutNode[] = config.nodes.map(node => {
          const n = g.node(node.id);
          return { ...node, x: n.x, y: n.y, width: n.width, height: n.height };
        });

        const ledges: LayoutEdge[] = config.edges.map(edge => {
          const e = g.edge(edge.from, edge.to);
          return { ...edge, points: e.points };
        });

        const graph = g.graph();
        setSvgSize({ width: (graph.width ?? 400) + 40, height: (graph.height ?? 300) + 40 });
        setLayoutNodes(lnodes);
        setLayoutEdges(ledges);
        setIsLoading(false);
      } catch (err) {
        setError(err instanceof Error ? err : new Error(String(err)));
        setIsLoading(false);
      }
    }, [config]);

    useEffect(() => {
      layout();
    }, [layout]);

    // ── Zoom/pan handlers ──
    const handleWheel = useCallback((e: React.WheelEvent) => {
      e.preventDefault();
      setTransform(prev => {
        const delta = e.deltaY > 0 ? 0.9 : 1.1;
        const newScale = Math.max(0.25, Math.min(4, prev.scale * delta));
        return { ...prev, scale: newScale };
      });
    }, []);

    const handleMouseDown = useCallback((e: React.MouseEvent) => {
      if (e.button !== 0) return;
      isPanning.current = true;
      panStart.current = { x: e.clientX - transform.x, y: e.clientY - transform.y };
    }, [transform]);

    const handleMouseMove = useCallback((e: React.MouseEvent) => {
      if (!isPanning.current) return;
      setTransform(prev => ({
        ...prev,
        x: e.clientX - panStart.current.x,
        y: e.clientY - panStart.current.y,
      }));
    }, []);

    const handleMouseUp = useCallback(() => { isPanning.current = false; }, []);

    const fgColor = isDark ? '#cccccc' : '#333333';
    const borderColor = isDark ? '#444444' : '#cccccc';
    const accentColor = isDark ? '#6366f1' : '#4f46e5';
    const nodeBg = isDark ? '#2a2a2a' : '#f8f8f8';

    // ── Build edge path from dagre points ──
    function edgePath(points: Array<{ x: number; y: number }>): string {
      if (points.length < 2) return '';
      let d = `M${points[0].x},${points[0].y}`;
      for (let i = 1; i < points.length; i++) {
        d += ` L${points[i].x},${points[i].y}`;
      }
      return d;
    }

    return (
      <RichBlock
        type="flow"
        rawSource={rawSource}
        isLoading={isLoading}
        error={error}
        onRetry={layout}
      >
        <div
          className="cursor-grab overflow-hidden active:cursor-grabbing"
          onWheel={handleWheel}
          onMouseDown={handleMouseDown}
          onMouseMove={handleMouseMove}
          onMouseUp={handleMouseUp}
          onMouseLeave={handleMouseUp}
        >
          <svg
            width={svgSize.width}
            height={svgSize.height}
            className="max-w-full"
            style={{
              transform: `translate(${transform.x}px, ${transform.y}px) scale(${transform.scale})`,
              transformOrigin: 'center',
            }}
          >
            <defs>
              <marker id="arrowhead" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto">
                <path d="M0,0 L8,3 L0,6 Z" fill={accentColor} />
              </marker>
            </defs>

            {/* Edges with animated dashes */}
            {layoutEdges.map((edge, i) => (
              <g key={`edge-${i}`}>
                <path
                  d={edgePath(edge.points)}
                  fill="none"
                  stroke={accentColor}
                  strokeWidth="1.5"
                  markerEnd="url(#arrowhead)"
                  strokeDasharray="6,3"
                  className="animate-[flowDash_2s_linear_infinite]"
                />
                {edge.label && edge.points.length >= 2 && (
                  <text
                    x={(edge.points[0].x + edge.points[edge.points.length - 1].x) / 2}
                    y={(edge.points[0].y + edge.points[edge.points.length - 1].y) / 2 - 8}
                    textAnchor="middle"
                    fill={fgColor}
                    fontSize="10"
                    opacity="0.7"
                  >
                    {edge.label}
                  </text>
                )}
              </g>
            ))}

            {/* Nodes */}
            {layoutNodes.map(node => (
              <g
                key={node.id}
                onMouseEnter={() => setHoveredNode(node.id)}
                onMouseLeave={() => setHoveredNode(null)}
                className="cursor-pointer"
              >
                <rect
                  x={node.x - node.width / 2}
                  y={node.y - node.height / 2}
                  width={node.width}
                  height={node.height}
                  rx="6"
                  fill={node.color ?? nodeBg}
                  stroke={hoveredNode === node.id ? accentColor : borderColor}
                  strokeWidth={hoveredNode === node.id ? '2' : '1'}
                  className="transition-all duration-150"
                />
                <text
                  x={node.x}
                  y={node.y + 4}
                  textAnchor="middle"
                  fill={fgColor}
                  fontSize="12"
                  fontFamily="var(--font-body)"
                >
                  {node.label}
                </text>

                {/* Tooltip on hover */}
                {hoveredNode === node.id && node.tooltip && (
                  <foreignObject
                    x={node.x - 80}
                    y={node.y - node.height / 2 - 32}
                    width="160"
                    height="28"
                  >
                    <div className="rounded bg-[var(--bg)] border border-[var(--border)]
                                    px-2 py-1 text-center text-[10px] text-[var(--fg-secondary)]
                                    shadow-md">
                      {node.tooltip}
                    </div>
                  </foreignObject>
                )}
              </g>
            ))}
          </svg>
        </div>
      </RichBlock>
    );
  });
  ```

  Add the flow dash animation keyframes to `src/index.css`:

  ```css
  @keyframes flowDash {
    from { stroke-dashoffset: 18; }
    to { stroke-dashoffset: 0; }
  }
  ```

- [ ] **15.3 Verify mermaid diagram**

  ```bash
  cd agent/webview && npm install mermaid dagre @types/dagre && npm run dev
  ```

  In the browser console:
  1. Trigger a message with a ````mermaid` code fence containing `graph TD; A-->B; B-->C` — verify diagram renders inside RichBlock
  2. Scroll to zoom — verify zoom changes smoothly, zoom percentage indicator shows
  3. Drag to pan — verify diagram moves with cursor
  4. Double-click — verify zoom resets to 100%
  5. Click "Expand" in header — verify fullscreen overlay shows the full diagram
  6. Switch theme — verify mermaid re-renders with matching theme (dark/light)
  7. Render with invalid mermaid syntax — verify error state shows raw source + "Retry" button
  8. Check RichBlock header shows "DIAGRAM" label with icon
  9. Click Copy — verify mermaid source is on clipboard
  10. Confirm no TypeScript errors

- [ ] **15.4 Verify flow diagram**

  In the browser console:
  1. Trigger a message with a ````flow` code fence containing `{"nodes":[{"id":"a","label":"Start"},{"id":"b","label":"Process"},{"id":"c","label":"End"}],"edges":[{"from":"a","to":"b"},{"from":"b","to":"c"}]}`
  2. Verify dagre layout renders nodes with connecting edges
  3. Verify animated dashes on edges (flowing pattern)
  4. Hover a node — verify border highlights and tooltip shows (if tooltip configured)
  5. Zoom/pan works same as mermaid
  6. Check RichBlock header shows "FLOW" label
  7. Confirm no TypeScript errors

- [ ] **15.5 Commit**

  ```
  feat(agent): implement MermaidDiagram and FlowDiagram with lazy-load, zoom/pan, theme-aware rendering, and RichBlock integration
  ```

### Task 16: Charts + Math

**Objective:** Create `ChartView` (Chart.js canvas with hover tooltips, legend toggle, responsive resize) and `MathBlock` (KaTeX inline/block rendering with "Copy as LaTeX" button). Both lazy-load their libraries and render inside `RichBlock`.

**Estimated time:** 20-25 minutes

#### Steps

- [ ] **16.1 Create `components/rich/ChartView.tsx`**

  Create `agent/webview/src/components/rich/ChartView.tsx` — lazy-loads Chart.js, renders canvas inside `RichBlock`, supports theme-aware colors and responsive resize:

  ```tsx
  import { useEffect, useRef, useState, useCallback, memo } from 'react';
  import { RichBlock } from './RichBlock';
  import { useThemeStore } from '@/stores/themeStore';

  interface ChartViewProps {
    configJson: string;
  }

  let chartJsModule: typeof import('chart.js') | null = null;
  let chartJsLoadPromise: Promise<typeof import('chart.js')> | null = null;

  function loadChartJs(): Promise<typeof import('chart.js')> {
    if (chartJsModule) return Promise.resolve(chartJsModule);
    if (chartJsLoadPromise) return chartJsLoadPromise;
    chartJsLoadPromise = import('chart.js').then(m => {
      // Register all components for flexibility
      m.Chart.register(
        m.CategoryScale, m.LinearScale,
        m.BarController, m.BarElement,
        m.LineController, m.LineElement, m.PointElement,
        m.PieController, m.ArcElement,
        m.DoughnutController,
        m.RadarController, m.RadialLinearScale,
        m.Title, m.Tooltip, m.Legend, m.Filler,
      );
      chartJsModule = m;
      return m;
    });
    return chartJsLoadPromise;
  }

  export const ChartView = memo(function ChartView({ configJson }: ChartViewProps) {
    const canvasRef = useRef<HTMLCanvasElement>(null);
    const chartRef = useRef<InstanceType<typeof import('chart.js').Chart> | null>(null);
    const containerRef = useRef<HTMLDivElement>(null);
    const isDark = useThemeStore(s => s.isDark);

    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<Error | null>(null);

    const render = useCallback(async () => {
      setIsLoading(true);
      setError(null);

      try {
        const { Chart } = await loadChartJs();

        // Destroy previous chart instance
        if (chartRef.current) {
          chartRef.current.destroy();
          chartRef.current = null;
        }

        if (!canvasRef.current) return;

        const config = JSON.parse(configJson);

        // Apply theme-aware defaults
        const fgColor = isDark ? '#cccccc' : '#333333';
        const gridColor = isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.08)';

        Chart.defaults.color = fgColor;
        Chart.defaults.borderColor = gridColor;

        chartRef.current = new Chart(canvasRef.current, {
          ...config,
          options: {
            ...config.options,
            responsive: true,
            maintainAspectRatio: true,
            plugins: {
              ...config.options?.plugins,
              tooltip: { enabled: true, ...config.options?.plugins?.tooltip },
              legend: {
                display: true,
                labels: { color: fgColor },
                ...config.options?.plugins?.legend,
              },
            },
          },
        });

        setIsLoading(false);
      } catch (err) {
        setError(err instanceof Error ? err : new Error(String(err)));
        setIsLoading(false);
      }
    }, [configJson, isDark]);

    useEffect(() => {
      render();
      return () => {
        if (chartRef.current) {
          chartRef.current.destroy();
          chartRef.current = null;
        }
      };
    }, [render]);

    // ── Responsive resize via ResizeObserver ──
    useEffect(() => {
      if (!containerRef.current) return;
      const observer = new ResizeObserver(() => {
        if (chartRef.current) {
          chartRef.current.resize();
        }
      });
      observer.observe(containerRef.current);
      return () => observer.disconnect();
    }, []);

    return (
      <RichBlock
        type="chart"
        rawSource={configJson}
        isLoading={isLoading}
        error={error}
        onRetry={render}
      >
        <div ref={containerRef} className="relative w-full">
          <canvas ref={canvasRef} />
        </div>
      </RichBlock>
    );
  });
  ```

- [ ] **16.2 Create `components/rich/MathBlock.tsx`**

  Create `agent/webview/src/components/rich/MathBlock.tsx` — lazy-loads KaTeX, renders inline/block math, includes "Copy as LaTeX" button:

  ```tsx
  import { useEffect, useState, useCallback, useMemo, memo } from 'react';
  import { RichBlock } from './RichBlock';

  interface MathBlockProps {
    latex: string;
    displayMode?: boolean; // true = block ($$...$$), false = inline ($...$)
  }

  let katexModule: typeof import('katex') | null = null;
  let katexLoadPromise: Promise<typeof import('katex')> | null = null;
  let katexCssLoaded = false;

  function loadKaTeX(): Promise<typeof import('katex')> {
    if (katexModule) return Promise.resolve(katexModule);
    if (katexLoadPromise) return katexLoadPromise;

    katexLoadPromise = import('katex').then(m => {
      katexModule = m;

      // Load KaTeX CSS (bundled in dist/fonts/ by Vite)
      if (!katexCssLoaded) {
        import('katex/dist/katex.min.css');
        katexCssLoaded = true;
      }

      return m;
    });

    return katexLoadPromise;
  }

  export const MathBlock = memo(function MathBlock({
    latex,
    displayMode = true,
  }: MathBlockProps) {
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<Error | null>(null);
    const [renderedHtml, setRenderedHtml] = useState('');
    const [copied, setCopied] = useState(false);

    const render = useCallback(async () => {
      setIsLoading(true);
      setError(null);

      try {
        const katex = await loadKaTeX();
        const html = katex.default.renderToString(latex, {
          displayMode,
          throwOnError: false,
          strict: false,
          trust: false,
        });
        setRenderedHtml(html);
        setIsLoading(false);
      } catch (err) {
        setError(err instanceof Error ? err : new Error(String(err)));
        setIsLoading(false);
      }
    }, [latex, displayMode]);

    useEffect(() => {
      render();
    }, [render]);

    const handleCopyLatex = useCallback(() => {
      navigator.clipboard.writeText(latex);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    }, [latex]);

    // For inline math, render without the full RichBlock wrapper
    if (!displayMode) {
      return (
        <span
          className="inline align-middle"
          dangerouslySetInnerHTML={{ __html: renderedHtml }}
        />
      );
    }

    return (
      <RichBlock
        type="math"
        rawSource={latex}
        isLoading={isLoading}
        error={error}
        onRetry={render}
      >
        <div className="flex flex-col items-center">
          <div
            className="text-[var(--fg)] overflow-x-auto max-w-full py-2"
            dangerouslySetInnerHTML={{ __html: renderedHtml }}
          />

          {/* Copy as LaTeX button */}
          <button
            onClick={handleCopyLatex}
            className="mt-1 flex items-center gap-1 rounded px-2 py-0.5
                       text-[10px] font-medium text-[var(--fg-muted)]
                       transition-colors duration-150
                       hover:bg-[var(--hover-overlay)] hover:text-[var(--fg)]"
          >
            {copied ? (
              <>
                <svg width="10" height="10" viewBox="0 0 12 12" fill="none"
                     stroke="var(--success)" strokeWidth="1.5">
                  <path d="M2.5 6.5L5 9l4.5-6" />
                </svg>
                Copied!
              </>
            ) : (
              <>
                <svg width="10" height="10" viewBox="0 0 12 12" fill="none"
                     stroke="currentColor" strokeWidth="1.2">
                  <rect x="4" y="4" width="7" height="7" rx="1" />
                  <path d="M8 4V2.5A1.5 1.5 0 006.5 1h-4A1.5 1.5 0 001 2.5v4A1.5 1.5 0 002.5 8H4" />
                </svg>
                Copy LaTeX
              </>
            )}
          </button>
        </div>
      </RichBlock>
    );
  });
  ```

- [ ] **16.3 Install Chart.js and KaTeX**

  ```bash
  cd agent/webview && npm install chart.js katex && npm install -D @types/katex
  ```

  Configure KaTeX font bundling — add to `vite.config.ts`:

  ```typescript
  // Inside the build config, add to assetsInclude or use publicDir for fonts:
  // KaTeX fonts are referenced by katex.min.css via @font-face.
  // Vite's CSS handling will automatically resolve and bundle these
  // when katex/dist/katex.min.css is imported.
  ```

  No additional Vite config is needed because Vite automatically processes CSS `@font-face` `url()` references when the CSS is imported via `import 'katex/dist/katex.min.css'`. The fonts will be emitted to `dist/assets/`.

- [ ] **16.4 Verify chart rendering**

  ```bash
  cd agent/webview && npm run dev
  ```

  In the browser console:
  1. Trigger `appendChart('{"type":"bar","data":{"labels":["A","B","C"],"datasets":[{"label":"Data","data":[10,20,15]}]}}')` — verify bar chart renders inside RichBlock
  2. Hover over bars — verify tooltips appear
  3. Click legend item — verify dataset toggles visibility
  4. Resize the browser window — verify chart resizes responsively
  5. Switch theme — verify chart colors update (text, grid lines)
  6. Check RichBlock header shows "CHART" label
  7. Click Copy — verify JSON config is on clipboard
  8. Click Expand — verify chart shows in fullscreen overlay
  9. Confirm no TypeScript errors

- [ ] **16.5 Verify math rendering**

  In the browser console:
  1. Trigger a message containing `$$E = mc^2$$` — verify block math renders centered with proper formatting
  2. Trigger a message containing inline `$\alpha + \beta$` — verify inline math renders without RichBlock wrapper
  3. Click "Copy LaTeX" — verify raw LaTeX string is on clipboard with checkmark feedback
  4. Trigger invalid LaTeX `$$\invalid{$$` — verify error fallback shows raw source
  5. Verify KaTeX fonts load correctly (characters render as math symbols, not fallback)
  6. Check RichBlock header shows "MATH" label (block mode only)
  7. Confirm no TypeScript errors

- [ ] **16.6 Commit**

  ```
  feat(agent): implement ChartView with Chart.js and MathBlock with KaTeX, lazy-loaded with RichBlock integration
  ```

### Task 17: Diffs + ANSI + Interactive HTML

**Objective:** Create `DiffHtml` (diff2html side-by-side viewer with per-hunk Accept/Reject), `AnsiOutput` (ANSI escape code rendering via ansi_up), and `InteractiveHtml` (sandboxed iframe for custom visualizations). Update `MarkdownRenderer` to detect and route special code fence languages to the appropriate rich components.

**Estimated time:** 25-30 minutes

#### Steps

- [ ] **17.1 Create `components/rich/DiffHtml.tsx`**

  Create `agent/webview/src/components/rich/DiffHtml.tsx` — lazy-loads diff2html, renders side-by-side diff with per-hunk actions:

  ```tsx
  import { useEffect, useState, useCallback, memo } from 'react';
  import { RichBlock } from './RichBlock';

  interface DiffHtmlProps {
    diffSource: string;
    filePath?: string;
    onAcceptHunk?: (hunkIndex: number) => void;
    onRejectHunk?: (hunkIndex: number) => void;
  }

  let diff2htmlModule: typeof import('diff2html') | null = null;
  let diff2htmlLoadPromise: Promise<typeof import('diff2html')> | null = null;
  let diff2htmlCssLoaded = false;

  function loadDiff2Html(): Promise<typeof import('diff2html')> {
    if (diff2htmlModule) return Promise.resolve(diff2htmlModule);
    if (diff2htmlLoadPromise) return diff2htmlLoadPromise;

    diff2htmlLoadPromise = import('diff2html').then(m => {
      diff2htmlModule = m;

      if (!diff2htmlCssLoaded) {
        import('diff2html/bundles/css/diff2html.min.css');
        diff2htmlCssLoaded = true;
      }

      return m;
    });

    return diff2htmlLoadPromise;
  }

  export const DiffHtml = memo(function DiffHtml({
    diffSource,
    filePath,
    onAcceptHunk,
    onRejectHunk,
  }: DiffHtmlProps) {
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<Error | null>(null);
    const [renderedHtml, setRenderedHtml] = useState('');
    const [hunkCount, setHunkCount] = useState(0);

    const render = useCallback(async () => {
      setIsLoading(true);
      setError(null);

      try {
        const diff2html = await loadDiff2Html();

        const diffFiles = diff2html.parse(diffSource);
        const hunkTotal = diffFiles.reduce((acc, f) => acc + f.blocks.length, 0);
        setHunkCount(hunkTotal);

        const html = diff2html.html(diffFiles, {
          drawFileList: false,
          matching: 'lines',
          outputFormat: 'side-by-side',
          renderNothingWhenEmpty: false,
        });

        setRenderedHtml(html);
        setIsLoading(false);
      } catch (err) {
        setError(err instanceof Error ? err : new Error(String(err)));
        setIsLoading(false);
      }
    }, [diffSource]);

    useEffect(() => {
      render();
    }, [render]);

    return (
      <RichBlock
        type="diff"
        rawSource={diffSource}
        isLoading={isLoading}
        error={error}
        onRetry={render}
      >
        {/* File path header */}
        {filePath && (
          <div className="mb-2 flex items-center gap-1.5">
            <svg width="12" height="12" viewBox="0 0 12 12" fill="none"
                 stroke="var(--fg-muted)" strokeWidth="1.2">
              <path d="M2 2h5l1 1h2v7H2V2z" />
            </svg>
            <span className="font-[var(--font-mono)] text-[11px] text-[var(--link)]">
              {filePath}
            </span>
          </div>
        )}

        {/* Diff content */}
        <div
          className="overflow-x-auto rounded border border-[var(--border)]
                     text-[12px] font-[var(--font-mono)]
                     [&_.d2h-wrapper]:bg-transparent
                     [&_.d2h-file-header]:hidden
                     [&_.d2h-code-line-ctn]:whitespace-pre
                     [&_.d2h-del]:bg-[var(--diff-rem-bg)] [&_.d2h-del]:text-[var(--diff-rem-fg)]
                     [&_.d2h-ins]:bg-[var(--diff-add-bg)] [&_.d2h-ins]:text-[var(--diff-add-fg)]"
          dangerouslySetInnerHTML={{ __html: renderedHtml }}
        />

        {/* Per-hunk action buttons */}
        {(onAcceptHunk || onRejectHunk) && hunkCount > 0 && (
          <div className="mt-2 flex flex-wrap gap-2">
            {Array.from({ length: hunkCount }, (_, i) => (
              <div key={i} className="flex items-center gap-1 rounded border border-[var(--border)]
                                      bg-[var(--bg)] px-2 py-1">
                <span className="text-[10px] text-[var(--fg-muted)]">Hunk {i + 1}</span>
                {onAcceptHunk && (
                  <button
                    onClick={() => onAcceptHunk(i)}
                    className="flex h-5 items-center gap-0.5 rounded px-1.5
                               text-[10px] font-medium text-[var(--success)]
                               transition-colors duration-150
                               hover:bg-[var(--success)]/10"
                  >
                    <svg width="10" height="10" viewBox="0 0 12 12" fill="none"
                         stroke="currentColor" strokeWidth="1.5">
                      <path d="M2.5 6.5L5 9l4.5-6" />
                    </svg>
                    Accept
                  </button>
                )}
                {onRejectHunk && (
                  <button
                    onClick={() => onRejectHunk(i)}
                    className="flex h-5 items-center gap-0.5 rounded px-1.5
                               text-[10px] font-medium text-[var(--error)]
                               transition-colors duration-150
                               hover:bg-[var(--error)]/10"
                  >
                    <svg width="10" height="10" viewBox="0 0 14 14" fill="none"
                         stroke="currentColor" strokeWidth="1.5">
                      <path d="M3 3l8 8M11 3l-8 8" />
                    </svg>
                    Reject
                  </button>
                )}
              </div>
            ))}
          </div>
        )}
      </RichBlock>
    );
  });
  ```

- [ ] **17.2 Create `components/rich/AnsiOutput.tsx`**

  Create `agent/webview/src/components/rich/AnsiOutput.tsx` — always-loaded (ansi_up is 3KB), parses ANSI escape codes to styled spans:

  ```tsx
  import { useMemo, memo } from 'react';
  import AnsiUp from 'ansi_up';

  interface AnsiOutputProps {
    text: string;
  }

  const ansiUp = new AnsiUp();
  ansiUp.use_classes = false; // Use inline styles for portability

  export const AnsiOutput = memo(function AnsiOutput({ text }: AnsiOutputProps) {
    const html = useMemo(() => {
      return ansiUp.ansi_to_html(text);
    }, [text]);

    return (
      <div className="my-2 rounded-md border border-[var(--border)] overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-[var(--border)]
                        bg-[var(--code-bg)] px-3 py-1">
          <span className="text-[10px] font-medium uppercase tracking-wide text-[var(--fg-muted)]">
            Terminal Output
          </span>
          <button
            onClick={() => navigator.clipboard.writeText(text)}
            className="flex h-5 w-5 items-center justify-center rounded
                       text-[var(--fg-muted)] transition-colors duration-150
                       hover:bg-[var(--hover-overlay-strong)] hover:text-[var(--fg)]"
            title="Copy raw text"
            aria-label="Copy raw text"
          >
            <svg width="12" height="12" viewBox="0 0 12 12" fill="none"
                 stroke="currentColor" strokeWidth="1.2">
              <rect x="4" y="4" width="7" height="7" rx="1" />
              <path d="M8 4V2.5A1.5 1.5 0 006.5 1h-4A1.5 1.5 0 001 2.5v4A1.5 1.5 0 002.5 8H4" />
            </svg>
          </button>
        </div>

        {/* ANSI content */}
        <pre
          className="max-h-[400px] overflow-auto bg-[#1a1a1a] p-3
                     font-[var(--font-mono)] text-[12px] leading-[1.6] text-[#cccccc]"
          dangerouslySetInnerHTML={{ __html: html }}
        />
      </div>
    );
  });
  ```

- [ ] **17.3 Create `components/rich/InteractiveHtml.tsx`**

  Create `agent/webview/src/components/rich/InteractiveHtml.tsx` — sandboxed iframe for custom HTML visualizations with IDE theme CSS variable injection:

  ```tsx
  import { useRef, useEffect, useState, memo } from 'react';
  import { RichBlock } from './RichBlock';
  import { useThemeStore } from '@/stores/themeStore';

  interface InteractiveHtmlProps {
    htmlContent: string;
    height?: number;
  }

  export const InteractiveHtml = memo(function InteractiveHtml({
    htmlContent,
    height = 300,
  }: InteractiveHtmlProps) {
    const iframeRef = useRef<HTMLIFrameElement>(null);
    const cssVariables = useThemeStore(s => s.cssVariables);
    const isDark = useThemeStore(s => s.isDark);
    const [isLoaded, setIsLoaded] = useState(false);

    // ── Build theme CSS to inject into iframe ──
    useEffect(() => {
      if (!iframeRef.current || !isLoaded) return;

      const iframe = iframeRef.current;
      const doc = iframe.contentDocument;
      if (!doc) return;

      // Inject CSS variables into iframe's :root
      let styleTag = doc.getElementById('ide-theme-vars');
      if (!styleTag) {
        styleTag = doc.createElement('style');
        styleTag.id = 'ide-theme-vars';
        doc.head.appendChild(styleTag);
      }

      const cssVarDecls = Object.entries(cssVariables)
        .map(([key, value]) => `--${key}: ${value};`)
        .join('\n  ');

      styleTag.textContent = `:root {\n  ${cssVarDecls}\n  color-scheme: ${isDark ? 'dark' : 'light'};\n}`;
    }, [cssVariables, isDark, isLoaded]);

    // ── Create iframe content with sandbox ──
    const srcDoc = `
      <!doctype html>
      <html>
        <head>
          <meta charset="UTF-8" />
          <style>
            body {
              margin: 0;
              padding: 8px;
              font-family: system-ui, sans-serif;
              font-size: 13px;
              color: var(--fg, #cccccc);
              background: transparent;
            }
          </style>
        </head>
        <body>${htmlContent}</body>
      </html>
    `;

    return (
      <RichBlock
        type="interactiveHtml"
        rawSource={htmlContent}
      >
        <iframe
          ref={iframeRef}
          srcDoc={srcDoc}
          sandbox="allow-scripts"
          className="w-full border-0 rounded"
          style={{ height: `${height}px` }}
          onLoad={() => setIsLoaded(true)}
          title="Interactive visualization"
        />
      </RichBlock>
    );
  });
  ```

- [ ] **17.4 Update MarkdownRenderer to route special code fences**

  Edit `agent/webview/src/components/markdown/MarkdownRenderer.tsx` — update the `code` component to detect special languages and route to the appropriate rich component:

  ```tsx
  // Add imports at top:
  import { MermaidDiagram } from '@/components/rich/MermaidDiagram';
  import { ChartView } from '@/components/rich/ChartView';
  import { FlowDiagram } from '@/components/rich/FlowDiagram';
  import { MathBlock } from '@/components/rich/MathBlock';
  import { DiffHtml } from '@/components/rich/DiffHtml';
  import { InteractiveHtml } from '@/components/rich/InteractiveHtml';

  // Update the code component inside ReactMarkdown's `components` prop:
  code({ className, children, ...props }) {
    const isBlock = className?.startsWith('language-');
    if (isBlock) {
      const language = className?.replace('language-', '') ?? '';
      const codeString = String(children).replace(/\n$/, '');

      // ── Route special languages to rich components ──
      switch (language) {
        case 'mermaid':
          return <MermaidDiagram source={codeString} />;

        case 'chart':
          return <ChartView configJson={codeString} />;

        case 'flow': {
          try {
            const flowConfig = JSON.parse(codeString);
            return <FlowDiagram config={flowConfig} rawSource={codeString} />;
          } catch {
            // Invalid JSON — fall through to regular code block
            break;
          }
        }

        case 'math':
          return <MathBlock latex={codeString} displayMode={true} />;

        case 'diff':
          return <DiffHtml diffSource={codeString} />;

        case 'html-interactive':
          return <InteractiveHtml htmlContent={codeString} />;
      }

      // ── Default: Shiki-highlighted code block ──
      return (
        <CodeBlock
          code={codeString}
          language={language}
          isStreaming={isStreaming}
        />
      );
    }

    // Inline code (unchanged)
    return (
      <code
        className="rounded bg-[var(--code-bg)] px-1 py-0.5 font-[var(--font-mono,'JetBrains_Mono',monospace)] text-[12px]"
        {...props}
      >
        {children}
      </code>
    );
  },
  ```

  Also add inline math detection. After the existing `components` prop in `ReactMarkdown`, add a `text` or custom `p` component that detects `$...$` inline math:

  ```tsx
  // Inside the components prop, add a paragraph handler for inline math:
  p({ children, ...props }) {
    // Check if children contain inline math patterns
    if (typeof children === 'string' && children.includes('$')) {
      const parts = children.split(/(\$[^$]+\$)/g);
      return (
        <p {...props}>
          {parts.map((part, i) => {
            if (part.startsWith('$') && part.endsWith('$') && part.length > 2) {
              const latex = part.slice(1, -1);
              return <MathBlock key={i} latex={latex} displayMode={false} />;
            }
            return <span key={i}>{part}</span>;
          })}
        </p>
      );
    }
    return <p {...props}>{children}</p>;
  },
  ```

- [ ] **17.5 Install diff2html**

  ```bash
  cd agent/webview && npm install diff2html
  ```

  Note: `ansi_up` is already in `package.json` from Task 1 scaffolding.

- [ ] **17.6 Verify diff rendering**

  ```bash
  cd agent/webview && npm run dev
  ```

  In the browser console:
  1. Send a message with a ````diff` code fence containing a unified diff — verify side-by-side diff renders inside RichBlock
  2. Verify additions highlighted with `--diff-add-bg` colors
  3. Verify removals highlighted with `--diff-rem-bg` colors
  4. Check per-hunk Accept/Reject buttons render when callbacks are provided
  5. Click Copy in RichBlock header — verify raw diff source is on clipboard
  6. Click Expand — verify diff shows in fullscreen overlay
  7. Confirm no TypeScript errors

- [ ] **17.7 Verify ANSI output**

  In the browser console:
  1. Trigger `appendAnsiOutput('\x1b[32mSuccess:\x1b[0m Build completed in \x1b[1;33m12.5s\x1b[0m')` — verify colored output renders
  2. Verify green "Success:" text and bold yellow "12.5s"
  3. Verify dark background and monospace font
  4. Verify scrollable for long output
  5. Click Copy — verify raw text (with ANSI codes stripped) is on clipboard
  6. Confirm no TypeScript errors

- [ ] **17.8 Verify interactive HTML**

  In the browser console:
  1. Send a message with a ````html-interactive` code fence containing `<button onclick="document.body.style.background='red'">Click</button>` — verify iframe renders inside RichBlock
  2. Click the button inside the iframe — verify it works (sandboxed with `allow-scripts`)
  3. Verify iframe has fixed height with no overflow issues
  4. Verify theme CSS variables are injected into iframe
  5. Click Expand — verify iframe shows in fullscreen overlay
  6. Confirm no TypeScript errors

- [ ] **17.9 Verify MarkdownRenderer routing**

  Send a single long message that includes multiple special code fences:
  1. Regular Kotlin code block — verify Shiki highlighting
  2. `mermaid` block — verify MermaidDiagram renders
  3. `chart` block — verify ChartView renders
  4. `diff` block — verify DiffHtml renders
  5. `math` block — verify MathBlock renders
  6. Inline `$E=mc^2$` — verify inline KaTeX renders
  7. `html-interactive` block — verify InteractiveHtml renders
  8. All blocks should coexist in the same message without layout issues
  9. Confirm no TypeScript errors

- [ ] **17.10 Commit**

  ```
  feat(agent): implement DiffHtml, AnsiOutput, InteractiveHtml, and wire MarkdownRenderer special code fence routing
  ```

### Task 18: Visualization Settings UI

**Objective:** Create the `VisualizationSettings` React component for per-type visualization configuration (enabled, autoRender, defaultExpanded, maxHeight). Add the corresponding fields to `PluginSettings.kt` on the Kotlin side. Add a "Visualizations" section to the AI & Advanced settings page. Wire persistence between `settingsStore`, the JCEF bridge, and `PluginSettings`.

**Estimated time:** 25-30 minutes

#### Steps

- [ ] **18.1 Create `components/common/VisualizationSettings.tsx`**

  Create `agent/webview/src/components/common/VisualizationSettings.tsx` — per-type settings panel with toggles, number inputs, and reset:

  ```tsx
  import { useCallback, memo } from 'react';
  import { useSettingsStore } from '@/stores/settingsStore';
  import type { VisualizationType, VisualizationConfig } from '@/bridge/types';

  /** Human-readable labels for visualization types */
  const TYPE_LABELS: Record<VisualizationType, string> = {
    mermaid: 'Mermaid Diagrams',
    chart: 'Charts (Chart.js)',
    flow: 'Flow Diagrams (dagre)',
    math: 'Math (KaTeX)',
    diff: 'Diff Viewer (diff2html)',
    interactiveHtml: 'Interactive HTML',
  };

  const ALL_TYPES: VisualizationType[] = [
    'mermaid', 'chart', 'flow', 'math', 'diff', 'interactiveHtml',
  ];

  interface ToggleProps {
    checked: boolean;
    onChange: (checked: boolean) => void;
    label: string;
    id: string;
  }

  function Toggle({ checked, onChange, label, id }: ToggleProps) {
    return (
      <label htmlFor={id} className="flex items-center gap-2 cursor-pointer select-none">
        <button
          id={id}
          role="switch"
          aria-checked={checked}
          onClick={() => onChange(!checked)}
          className={`relative inline-flex h-4 w-7 items-center rounded-full
                     transition-colors duration-200
                     ${checked
                       ? 'bg-[var(--accent,#6366f1)]'
                       : 'bg-[var(--fg-muted)]/30'
                     }`}
        >
          <span
            className={`inline-block h-3 w-3 rounded-full bg-white shadow-sm
                       transition-transform duration-200
                       ${checked ? 'translate-x-3.5' : 'translate-x-0.5'}`}
          />
        </button>
        <span className="text-[12px] text-[var(--fg-secondary)]">{label}</span>
      </label>
    );
  }

  function TypeSettings({ type }: { type: VisualizationType }) {
    const config = useSettingsStore(s => s.visualizations[type]);
    const updateVisualization = useSettingsStore(s => s.updateVisualization);
    const resetVisualization = useSettingsStore(s => s.resetVisualization);

    const update = useCallback(
      (partial: Partial<VisualizationConfig>) => {
        updateVisualization(type, partial);

        // Sync to Kotlin via bridge
        if (window._openSettings) {
          // The bridge will be used to persist settings —
          // for now, settingsStore is the source of truth in the React app
        }
      },
      [type, updateVisualization],
    );

    return (
      <div className={`rounded-md border border-[var(--border)] bg-[var(--bg)] p-3
                       ${!config.enabled ? 'opacity-50' : ''}`}>
        {/* Type header with enable toggle */}
        <div className="flex items-center justify-between mb-2">
          <span className="text-[13px] font-medium text-[var(--fg)]">
            {TYPE_LABELS[type]}
          </span>
          <Toggle
            id={`viz-${type}-enabled`}
            checked={config.enabled}
            onChange={(enabled) => update({ enabled })}
            label=""
          />
        </div>

        {/* Settings (only shown when enabled) */}
        {config.enabled && (
          <div className="flex flex-col gap-2 pl-1">
            <Toggle
              id={`viz-${type}-autorender`}
              checked={config.autoRender}
              onChange={(autoRender) => update({ autoRender })}
              label="Auto-render (uncheck to require click)"
            />

            <Toggle
              id={`viz-${type}-expanded`}
              checked={config.defaultExpanded}
              onChange={(defaultExpanded) => update({ defaultExpanded })}
              label="Expanded by default"
            />

            {/* Max height input */}
            <div className="flex items-center gap-2">
              <label
                htmlFor={`viz-${type}-maxheight`}
                className="text-[12px] text-[var(--fg-secondary)]"
              >
                Max height (px):
              </label>
              <input
                id={`viz-${type}-maxheight`}
                type="number"
                min="0"
                max="2000"
                step="50"
                value={config.maxHeight}
                onChange={(e) => update({ maxHeight: parseInt(e.target.value, 10) || 0 })}
                className="w-20 rounded border border-[var(--input-border)]
                           bg-[var(--input-bg)] px-2 py-0.5
                           text-[12px] text-[var(--fg)]
                           focus:border-[var(--accent,#6366f1)] focus:outline-none"
              />
              <span className="text-[10px] text-[var(--fg-muted)]">0 = unlimited</span>
            </div>

            {/* Reset button */}
            <button
              onClick={() => resetVisualization(type)}
              className="mt-1 self-start rounded px-2 py-0.5
                         text-[10px] font-medium text-[var(--fg-muted)]
                         border border-[var(--border)]
                         transition-colors duration-150
                         hover:bg-[var(--hover-overlay)] hover:text-[var(--fg)]"
            >
              Reset to defaults
            </button>
          </div>
        )}
      </div>
    );
  }

  export const VisualizationSettings = memo(function VisualizationSettings() {
    const resetAll = useSettingsStore(s => s.resetAll);

    return (
      <div className="flex flex-col gap-3">
        <div className="flex items-center justify-between">
          <h3 className="text-[14px] font-semibold text-[var(--fg)]">
            Visualization Settings
          </h3>
          <button
            onClick={resetAll}
            className="rounded px-2 py-1 text-[11px] font-medium
                       text-[var(--fg-muted)] border border-[var(--border)]
                       transition-colors duration-150
                       hover:bg-[var(--hover-overlay)] hover:text-[var(--fg)]"
          >
            Reset all to defaults
          </button>
        </div>

        <p className="text-[12px] text-[var(--fg-muted)] leading-relaxed">
          Configure how rich visualizations render in agent messages.
          Disabled types will be shown as raw source code.
        </p>

        {ALL_TYPES.map(type => (
          <TypeSettings key={type} type={type} />
        ))}
      </div>
    );
  });
  ```

- [ ] **18.2 Add visualization settings fields to `PluginSettings.kt`**

  Edit `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt` — add visualization configuration fields:

  ```kotlin
  // Add inside the PluginSettings state class:

  // ── Visualization settings ──
  var vizMermaidEnabled: Boolean = true
  var vizMermaidAutoRender: Boolean = true
  var vizMermaidDefaultExpanded: Boolean = false
  var vizMermaidMaxHeight: Int = 300

  var vizChartEnabled: Boolean = true
  var vizChartAutoRender: Boolean = true
  var vizChartDefaultExpanded: Boolean = false
  var vizChartMaxHeight: Int = 300

  var vizFlowEnabled: Boolean = true
  var vizFlowAutoRender: Boolean = true
  var vizFlowDefaultExpanded: Boolean = false
  var vizFlowMaxHeight: Int = 300

  var vizMathEnabled: Boolean = true
  var vizMathAutoRender: Boolean = true
  var vizMathDefaultExpanded: Boolean = true
  var vizMathMaxHeight: Int = 0

  var vizDiffEnabled: Boolean = true
  var vizDiffAutoRender: Boolean = true
  var vizDiffDefaultExpanded: Boolean = true
  var vizDiffMaxHeight: Int = 400

  var vizInteractiveHtmlEnabled: Boolean = true
  var vizInteractiveHtmlAutoRender: Boolean = true
  var vizInteractiveHtmlDefaultExpanded: Boolean = false
  var vizInteractiveHtmlMaxHeight: Int = 500
  ```

- [ ] **18.3 Add "Visualizations" section to `AiAdvancedConfigurable.kt`**

  Edit `core/src/main/kotlin/com/workflow/orchestrator/core/settings/AiAdvancedConfigurable.kt` — add a "Visualizations" collapsible group with per-type settings rows:

  ```kotlin
  // Add inside createPanel() or the DSL panel builder:

  // ── Visualizations ──
  group("Visualizations") {
      row {
          label("Configure how rich visualizations render in the agent chat.")
      }

      // Mermaid Diagrams
      collapsibleGroup("Mermaid Diagrams") {
          row { checkBox("Enabled", settings::vizMermaidEnabled) }
          row { checkBox("Auto-render", settings::vizMermaidAutoRender) }
          row { checkBox("Expanded by default", settings::vizMermaidDefaultExpanded) }
          row {
              label("Max height (px):")
              intTextField(settings::vizMermaidMaxHeight, range = 0..2000)
          }
      }

      // Charts
      collapsibleGroup("Charts (Chart.js)") {
          row { checkBox("Enabled", settings::vizChartEnabled) }
          row { checkBox("Auto-render", settings::vizChartAutoRender) }
          row { checkBox("Expanded by default", settings::vizChartDefaultExpanded) }
          row {
              label("Max height (px):")
              intTextField(settings::vizChartMaxHeight, range = 0..2000)
          }
      }

      // Flow Diagrams
      collapsibleGroup("Flow Diagrams (dagre)") {
          row { checkBox("Enabled", settings::vizFlowEnabled) }
          row { checkBox("Auto-render", settings::vizFlowAutoRender) }
          row { checkBox("Expanded by default", settings::vizFlowDefaultExpanded) }
          row {
              label("Max height (px):")
              intTextField(settings::vizFlowMaxHeight, range = 0..2000)
          }
      }

      // Math
      collapsibleGroup("Math (KaTeX)") {
          row { checkBox("Enabled", settings::vizMathEnabled) }
          row { checkBox("Auto-render", settings::vizMathAutoRender) }
          row { checkBox("Expanded by default", settings::vizMathDefaultExpanded) }
          row {
              label("Max height (px):")
              intTextField(settings::vizMathMaxHeight, range = 0..2000)
          }
      }

      // Diff
      collapsibleGroup("Diff Viewer (diff2html)") {
          row { checkBox("Enabled", settings::vizDiffEnabled) }
          row { checkBox("Auto-render", settings::vizDiffAutoRender) }
          row { checkBox("Expanded by default", settings::vizDiffDefaultExpanded) }
          row {
              label("Max height (px):")
              intTextField(settings::vizDiffMaxHeight, range = 0..2000)
          }
      }

      // Interactive HTML
      collapsibleGroup("Interactive HTML") {
          row { checkBox("Enabled", settings::vizInteractiveHtmlEnabled) }
          row { checkBox("Auto-render", settings::vizInteractiveHtmlAutoRender) }
          row { checkBox("Expanded by default", settings::vizInteractiveHtmlDefaultExpanded) }
          row {
              label("Max height (px):")
              intTextField(settings::vizInteractiveHtmlMaxHeight, range = 0..2000)
          }
      }
  }
  ```

- [ ] **18.4 Wire settings persistence: settingsStore <-> bridge <-> PluginSettings**

  Create or update the bridge to sync visualization settings between the React app and Kotlin. Add to `agent/webview/src/bridge/jcef-bridge.ts`:

  ```typescript
  // Add a new Kotlin-to-JS bridge function for loading saved settings:
  window.applyVisualizationSettings = (settingsJson: string) => {
    try {
      const settings = JSON.parse(settingsJson) as Record<VisualizationType, VisualizationConfig>;
      const store = useSettingsStore.getState();
      for (const [type, config] of Object.entries(settings)) {
        store.updateVisualization(type as VisualizationType, config);
      }
    } catch (e) {
      console.error('Failed to apply visualization settings:', e);
    }
  };
  ```

  Add a JS-to-Kotlin bridge call for persisting changes. In `settingsStore.ts`, update `updateVisualization` to notify Kotlin:

  ```typescript
  updateVisualization(type: VisualizationType, config: Partial<VisualizationConfig>) {
    set(state => ({
      visualizations: {
        ...state.visualizations,
        [type]: { ...state.visualizations[type], ...config },
      },
    }));

    // Persist to Kotlin side
    const fullConfig = { ...get().visualizations[type], ...config };
    if (window._openSettings) {
      // Use a dedicated bridge method once available.
      // For now, the settings are applied on next panel load
      // via applyVisualizationSettings() called from AgentCefPanel.
    }
  },
  ```

  On the Kotlin side, `AgentCefPanel` should call `applyVisualizationSettings()` after page load with the current `PluginSettings` values serialized as JSON.

- [ ] **18.5 Verify visualization settings UI**

  ```bash
  cd agent/webview && npm run dev
  ```

  In the browser console:
  1. Render `<VisualizationSettings />` in a test panel — verify all 6 visualization types listed
  2. Toggle "Enabled" off for Mermaid — verify the section dims (opacity)
  3. Toggle "Enabled" back on — verify settings reappear
  4. Uncheck "Auto-render" for Charts — verify `settingsStore.visualizations.chart.autoRender === false`
  5. Send a chart message — verify "Click to render chart" button appears instead of chart
  6. Change "Max height" to 150 for Flow — verify `settingsStore.visualizations.flow.maxHeight === 150`
  7. Click "Reset to defaults" on a single type — verify settings revert
  8. Click "Reset all to defaults" — verify all types revert
  9. Confirm no TypeScript errors

- [ ] **18.6 Verify Kotlin settings page** (manual, after running `./gradlew runIde`)

  1. Open Settings > Tools > Workflow Orchestrator > AI & Advanced
  2. Verify "Visualizations" group appears with collapsible sections for each type
  3. Toggle checkboxes — verify state persists after Apply
  4. Change max height values — verify they persist
  5. Close and reopen settings — verify values are preserved

- [ ] **18.7 Commit**

  ```
  feat(agent): implement VisualizationSettings UI, add per-type viz config to PluginSettings, wire settings persistence via bridge
  ```

---

## Phase 5: Polish & Cutover

### Task 19: Animations & Transitions

**Objective:** Create `config/animation-constants.ts` with all durations and easing values from the spec. Create `src/styles/animations.css` with all keyframes and transition classes. Apply animations to every component from the spec's animation table. Add `prefers-reduced-motion` support. All animations use `transform` and `opacity` only (GPU-accelerated).

**Estimated time:** 25-30 minutes

#### Steps

- [ ] **19.1 Create `config/animation-constants.ts`**

  Create `agent/webview/src/config/animation-constants.ts` — all durations, easing curves, and delay values referenced by CSS and components:

  ```typescript
  /**
   * Animation constants matching the design spec's animation table.
   * CSS keyframes reference these values via CSS custom properties.
   * Components use these for programmatic animation control (e.g., stagger delays).
   */

  /** Easing curves */
  export const EASING = {
    /** Standard ease-out for entrances */
    easeOut: 'cubic-bezier(0.0, 0.0, 0.2, 1)',
    /** Ease-in for exits */
    easeIn: 'cubic-bezier(0.4, 0.0, 1, 1)',
    /** Material Design standard curve — used for tool card expand */
    materialStandard: 'cubic-bezier(0.4, 0, 0.2, 1)',
    /** Linear ease for simple transitions */
    ease: 'ease',
    /** Step function for cursor blink */
    stepEnd: 'step-end',
    /** Smooth shimmer sweep */
    easeInOut: 'ease-in-out',
  } as const;

  /** Durations in milliseconds */
  export const DURATION = {
    /** Message enter animation */
    messageEnter: 220,
    /** Tool card expand/collapse */
    toolCardExpand: 300,
    /** Thinking shimmer cycle (infinite) */
    thinkingShimmer: 1800,
    /** Streaming cursor blink (infinite) */
    streamingCursor: 530,
    /** Rich block fade-in after skeleton resolves */
    richBlockEnter: 200,
    /** Status icon crossfade between states */
    statusIconTransition: 200,
    /** Overlay open animation */
    overlayOpen: 200,
    /** Overlay close animation (faster than open) */
    overlayClose: 150,
    /** Scroll button slide-in */
    scrollButton: 200,
    /** Button press tactile feedback */
    buttonPress: 80,
    /** Smooth scroll to bottom */
    smoothScroll: 300,
  } as const;

  /** Stagger delays */
  export const STAGGER = {
    /** Delay between consecutive message enter animations */
    messageBetween: 40,
  } as const;

  /** Transform values */
  export const TRANSFORM = {
    /** Message enter: start offset */
    messageEnterY: '8px',
    /** Overlay open: start scale */
    overlayOpenScale: '0.97',
    /** Button press: active scale */
    buttonPressScale: '0.97',
  } as const;
  ```

- [ ] **19.2 Create `src/styles/animations.css`**

  Create `agent/webview/src/styles/animations.css` — all keyframes, animation classes, and transition utilities:

  ```css
  /*
   * Animation keyframes and utility classes.
   * All animations use transform and opacity only (GPU-accelerated).
   * All respect prefers-reduced-motion.
   */

  /* === Keyframes === */

  @keyframes message-enter {
    from {
      opacity: 0;
      transform: translateY(8px);
    }
    to {
      opacity: 1;
      transform: translateY(0);
    }
  }

  @keyframes thinking-shimmer {
    0% {
      background-position: -200% 0;
    }
    100% {
      background-position: 200% 0;
    }
  }

  @keyframes streaming-cursor-blink {
    0%, 100% {
      opacity: 1;
    }
    50% {
      opacity: 0;
    }
  }

  @keyframes overlay-open {
    from {
      opacity: 0;
      transform: scale(0.97);
    }
    to {
      opacity: 1;
      transform: scale(1);
    }
  }

  @keyframes scroll-button-enter {
    from {
      opacity: 0;
      transform: translateY(8px);
    }
    to {
      opacity: 1;
      transform: translateY(0);
    }
  }

  /* === Animation classes === */

  .animate-message-enter {
    animation: message-enter 220ms cubic-bezier(0.0, 0.0, 0.2, 1) both;
  }

  .animate-thinking-shimmer {
    background: linear-gradient(
      90deg,
      var(--thinking-bg) 25%,
      var(--border) 50%,
      var(--thinking-bg) 75%
    );
    background-size: 200% 100%;
    animation: thinking-shimmer 1800ms ease-in-out infinite;
  }

  .animate-streaming-cursor {
    animation: streaming-cursor-blink 530ms step-end infinite;
  }

  .animate-overlay-open {
    animation: overlay-open 200ms cubic-bezier(0.0, 0.0, 0.2, 1) both;
  }

  .animate-overlay-close {
    opacity: 0;
    transition: opacity 150ms cubic-bezier(0.4, 0.0, 1, 1);
  }

  .animate-scroll-button {
    animation: scroll-button-enter 200ms ease both;
  }

  /* === Transition classes === */

  .transition-tool-expand {
    transition: max-height 300ms cubic-bezier(0.4, 0, 0.2, 1),
                opacity 300ms cubic-bezier(0.4, 0, 0.2, 1);
    will-change: max-height;
  }

  .transition-rich-block-enter {
    transition: opacity 200ms cubic-bezier(0.0, 0.0, 0.2, 1);
  }

  .transition-status-icon {
    transition: opacity 200ms ease;
  }

  /* === Interactive feedback === */

  .press-feedback:active {
    transform: scale(0.97);
    transition: transform 80ms ease;
  }

  /* === Focus ring (accessibility) === */

  .focus-ring:focus-visible {
    outline: 2px solid var(--accent-read);
    outline-offset: 2px;
  }

  /* === Reduced motion === */

  @media (prefers-reduced-motion: reduce) {
    *,
    *::before,
    *::after {
      animation-duration: 0.01ms !important;
      animation-iteration-count: 1 !important;
      transition-duration: 0.01ms !important;
      scroll-behavior: auto !important;
    }
  }
  ```

- [ ] **19.3 Import animations.css in main.tsx**

  In `agent/webview/src/main.tsx`, add the import alongside the existing Tailwind import:

  ```typescript
  import './styles/animations.css';
  ```

- [ ] **19.4 Apply message enter animation to `MessageCard`**

  In `agent/webview/src/components/chat/MessageCard.tsx`, add the `animate-message-enter` class to the root element. Support stagger delay for consecutive messages:

  ```tsx
  // Add to MessageCard props
  interface MessageCardProps {
    // ... existing props
    /** Index in the message list, used for stagger delay */
    enterDelay?: number;
  }

  // In the component render:
  <div
    className={cn('animate-message-enter', /* existing classes */)}
    style={{ animationDelay: enterDelay ? `${enterDelay * 40}ms` : undefined }}
  >
  ```

  In `MessageList.tsx`, pass `enterDelay` only for newly added messages (not on initial load or scroll-back):

  ```tsx
  // Track the count at last render to determine which messages are "new"
  const prevCountRef = useRef(messages.length);

  // Only stagger messages added since last render
  const isNewMessage = index >= prevCountRef.current;
  <MessageCard
    enterDelay={isNewMessage ? index - prevCountRef.current : 0}
    // ... other props
  />
  ```

- [ ] **19.5 Apply tool card expand transition**

  In `agent/webview/src/components/agent/ToolCallCard.tsx`, add `transition-tool-expand` to the collapsible content container:

  ```tsx
  <div
    className={cn('transition-tool-expand overflow-hidden', {
      'max-h-0 opacity-0': !isExpanded,
      'max-h-[2000px] opacity-100': isExpanded,
    })}
  >
    {/* Tool call input/output content */}
  </div>
  ```

- [ ] **19.6 Apply thinking shimmer**

  In `agent/webview/src/components/agent/ThinkingBlock.tsx`, apply `animate-thinking-shimmer` to the loading indicator bar when the thinking block is still streaming:

  ```tsx
  {isStreaming && (
    <div className="animate-thinking-shimmer h-1 w-full rounded-full" />
  )}
  ```

- [ ] **19.7 Apply overlay open/close animations**

  In `agent/webview/src/components/common/FullscreenOverlay.tsx`, manage open/close animation states:

  ```tsx
  const [isClosing, setIsClosing] = useState(false);

  const handleClose = useCallback(() => {
    setIsClosing(true);
    setTimeout(() => {
      onClose();
      setIsClosing(false);
    }, 150); // matches DURATION.overlayClose
  }, [onClose]);

  return (
    <div className={cn(
      'fixed inset-0 z-50 flex items-center justify-center',
      isClosing ? 'animate-overlay-close' : 'animate-overlay-open',
    )}>
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/50"
        onClick={handleClose}
      />
      {/* Content */}
      <div className="relative z-10 max-h-[90vh] max-w-[90vw] overflow-auto rounded-lg bg-[var(--bg)] border border-[var(--border)]">
        {children}
      </div>
    </div>
  );
  ```

- [ ] **19.8 Apply rich block enter transition**

  In `agent/webview/src/components/rich/RichBlock.tsx`, add `transition-rich-block-enter` that triggers when the skeleton resolves to actual content:

  ```tsx
  <div
    className={cn('transition-rich-block-enter', {
      'opacity-0': isLoading,
      'opacity-100': !isLoading,
    })}
  >
    {isLoading ? <Skeleton /> : children}
  </div>
  ```

- [ ] **19.9 Apply status icon crossfade**

  In `agent/webview/src/components/agent/StatusIndicator.tsx` (or wherever tool call status icons render), add `transition-status-icon`:

  ```tsx
  <span className="transition-status-icon inline-flex">
    {status === 'pending' && <PendingIcon className="opacity-100" />}
    {status === 'running' && <RunningIcon className="opacity-100" />}
    {status === 'completed' && <CompletedIcon className="opacity-100" />}
    {status === 'error' && <ErrorIcon className="opacity-100" />}
  </span>
  ```

- [ ] **19.10 Apply scroll button animation**

  In `agent/webview/src/components/common/ScrollButton.tsx`, add `animate-scroll-button` when the button appears:

  ```tsx
  {showScrollButton && (
    <button
      className="animate-scroll-button press-feedback fixed bottom-20 right-4 ..."
      onClick={scrollToBottom}
    >
      {/* Down arrow icon */}
    </button>
  )}
  ```

- [ ] **19.11 Apply button press feedback globally**

  Add `press-feedback` class to all interactive buttons across components. Update the shared button base classes (if using a `Button` component or Tailwind `@apply`):

  - `ToolCallCard` expand/collapse button
  - `RichBlock` action buttons (Copy, Expand, Open in Tab)
  - `FullscreenOverlay` close button
  - `ScrollButton`
  - `PromptInput` send button
  - `ApprovalGate` approve/deny buttons
  - `PlanCard` approve/revise buttons
  - `FeedbackBar` thumbs up/down buttons

- [ ] **19.12 Verify all animations**

  ```bash
  cd agent/webview && npm run dev
  ```

  In the browser:
  1. Send a message — verify message slides up and fades in (220ms)
  2. Send multiple messages rapidly — verify stagger delay (40ms between each)
  3. Trigger a tool call — expand/collapse the card, verify smooth max-height transition (300ms)
  4. Trigger thinking — verify shimmer bar animates (1800ms sweep)
  5. Start streaming — verify cursor blinks (530ms)
  6. Render a rich block — verify fade-in after skeleton (200ms)
  7. Click expand on a rich block — verify overlay open animation (scale + opacity, 200ms)
  8. Close overlay — verify faster close (150ms)
  9. Scroll up — verify scroll button slides in from bottom
  10. Click any button — verify press scale feedback (80ms)
  11. Enable `prefers-reduced-motion: reduce` in browser DevTools — verify all animations are effectively instant
  12. Verify no layout shift (CLS) — all animations use transform/opacity only
  13. Confirm no TypeScript errors

- [ ] **19.13 Commit**

  ```
  feat(agent): add CSS animations and transitions — message enter, tool expand, thinking shimmer, overlay open/close, scroll button, button press, prefers-reduced-motion support
  ```

---

### Task 20: Accessibility

**Objective:** Implement full keyboard navigation, ARIA attributes, focus management, and screen reader announcements across all components. Ensure the chat UI is usable with keyboard-only and screen reader workflows.

**Estimated time:** 30-35 minutes

#### Steps

- [ ] **20.1 Add `lang="en"` to root HTML**

  In `agent/webview/index.html`, add the `lang` attribute to the `<html>` element:

  ```html
  <html lang="en">
  ```

- [ ] **20.2 Add ARIA attributes to ChatContainer**

  In `agent/webview/src/components/chat/ChatContainer.tsx`, add ARIA roles to the message list container:

  ```tsx
  <div
    ref={scrollContainerRef}
    role="log"
    aria-live="polite"
    aria-label="Agent chat messages"
    className="flex-1 overflow-y-auto"
  >
    {messages.map((msg, i) => (
      <MessageCard
        key={msg.id}
        role="article"
        aria-label={`${msg.role === 'user' ? 'User' : 'Agent'} message`}
        // ... other props
      />
    ))}

    {/* Active streaming message */}
    {activeStream && (
      <MessageCard
        role="article"
        aria-label="Agent message"
        aria-busy={activeStream.isStreaming}
        // ... streaming props
      />
    )}
  </div>
  ```

- [ ] **20.3 Add ARIA attributes to ToolCallCard**

  In `agent/webview/src/components/agent/ToolCallCard.tsx`:

  ```tsx
  <div
    role="region"
    aria-label={`Tool: ${toolName}`}
    aria-expanded={isExpanded}
    className={/* existing classes */}
  >
    <button
      onClick={toggleExpand}
      aria-expanded={isExpanded}
      aria-controls={`tool-content-${toolId}`}
      className="press-feedback focus-ring w-full text-left ..."
    >
      {/* Tool header with status */}
    </button>
    <div id={`tool-content-${toolId}`} role="region">
      {/* Expandable content */}
    </div>
  </div>
  ```

- [ ] **20.4 Add ARIA attributes to ThinkingBlock**

  In `agent/webview/src/components/agent/ThinkingBlock.tsx`:

  ```tsx
  <div
    role="region"
    aria-label="Agent reasoning"
    aria-expanded={isExpanded}
  >
    <button
      onClick={toggleExpand}
      aria-expanded={isExpanded}
      aria-controls={`thinking-content-${id}`}
      className="press-feedback focus-ring w-full text-left ..."
    >
      Thinking...
    </button>
    <div id={`thinking-content-${id}`}>
      {/* Thinking content */}
    </div>
  </div>
  ```

- [ ] **20.5 Add ARIA attributes to PlanCard**

  In `agent/webview/src/components/agent/PlanCard.tsx`:

  ```tsx
  <div role="region" aria-label="Execution plan">
    {plan.steps.map((step) => (
      <div
        key={step.id}
        role="listitem"
        aria-label={`Step ${step.number}: ${step.title}`}
      >
        <button
          aria-expanded={expandedSteps.has(step.id)}
          aria-controls={`plan-step-${step.id}`}
          className="press-feedback focus-ring ..."
        >
          {/* Step header */}
        </button>
        <div id={`plan-step-${step.id}`}>
          {/* Step details */}
        </div>
      </div>
    ))}
  </div>
  ```

- [ ] **20.6 Add ARIA to icon-only buttons**

  Add `aria-label` to every icon-only button across all components. These are buttons that have no visible text label:

  | Component | Button | `aria-label` |
  |-----------|--------|-------------|
  | `CodeBlock` | Copy button | `"Copy code"` |
  | `RichBlock` | Expand button | `"Expand visualization"` |
  | `RichBlock` | Open in tab button | `"Open in editor tab"` |
  | `RichBlock` | Copy button | `"Copy content"` |
  | `FullscreenOverlay` | Close button | `"Close overlay"` |
  | `ScrollButton` | Scroll down | `"Scroll to bottom"` |
  | `PromptInput` | Send button | `"Send message"` |
  | `PromptInput` | Cancel button | `"Cancel task"` |
  | `FeedbackBar` | Thumbs up | `"Helpful"` |
  | `FeedbackBar` | Thumbs down | `"Not helpful"` |
  | `SessionMetrics` | Close button | `"Close session metrics"` |
  | `MessageCard` | Copy button | `"Copy message"` |

  Example:

  ```tsx
  <button aria-label="Copy code" className="press-feedback focus-ring ...">
    <CopyIcon />
  </button>
  ```

- [ ] **20.7 Add ARIA to FullscreenOverlay**

  In `agent/webview/src/components/common/FullscreenOverlay.tsx`:

  ```tsx
  <div
    role="dialog"
    aria-modal="true"
    aria-label={ariaLabel ?? 'Expanded view'}
    className={/* existing animation classes */}
  >
  ```

- [ ] **20.8 Add ARIA to MentionAutocomplete dropdown**

  In `agent/webview/src/components/input/MentionAutocomplete.tsx`:

  ```tsx
  <ul
    role="listbox"
    aria-label="Mention suggestions"
    id="mention-listbox"
  >
    {results.map((item, index) => (
      <li
        key={item.id}
        role="option"
        id={`mention-option-${index}`}
        aria-selected={index === activeIndex}
        className={cn('focus-ring ...', {
          'bg-[var(--tool-bg)]': index === activeIndex,
        })}
      >
        {item.label}
      </li>
    ))}
  </ul>
  ```

  On the input element, add `aria-activedescendant`:

  ```tsx
  <input
    aria-autocomplete="list"
    aria-controls="mention-listbox"
    aria-activedescendant={
      showMentions ? `mention-option-${activeIndex}` : undefined
    }
  />
  ```

- [ ] **20.9 Add ARIA to status messages**

  In `agent/webview/src/components/common/Toast.tsx` (or wherever toasts/status messages render):

  ```tsx
  <div role="alert" aria-live="assertive">
    {message}
  </div>
  ```

  For tool call status indicator in `StatusIndicator.tsx`:

  ```tsx
  <span role="status" aria-label={`Tool ${toolName}: ${status}`}>
    {/* Status icon */}
  </span>
  ```

- [ ] **20.10 Implement keyboard navigation — Tab order**

  Ensure natural tab order flows through all interactive elements. In the root `App.tsx` or `ChatContainer.tsx`, the DOM order should be:

  1. Session header actions (New Chat, Settings)
  2. Message list (interactive elements within messages tab in document order)
  3. Input bar and its actions
  4. Floating elements (ScrollButton)

  Add `tabIndex={0}` only where needed (most elements are natively focusable). Add `tabIndex={-1}` to elements that should receive programmatic focus but not tab focus.

- [ ] **20.11 Implement Escape cascading**

  Create `agent/webview/src/hooks/useEscapeHandler.ts` — centralized Escape key handler with cascading priority:

  ```typescript
  import { useEffect, useCallback } from 'react';
  import { useChatStore } from '@/stores/chatStore';

  /**
   * Escape key cascading priority:
   * 1. Close FullscreenOverlay (if open)
   * 2. Collapse expanded tool card (if any expanded)
   * 3. Close MentionAutocomplete dropdown (if open)
   * 4. Focus input bar
   */
  export function useEscapeHandler() {
    const handleEscape = useCallback((e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;

      // Priority 1: Close overlay
      const overlay = document.querySelector('[role="dialog"][aria-modal="true"]');
      if (overlay) {
        // Overlay's own close handler will fire via its keydown listener
        return;
      }

      // Priority 2: Collapse any expanded tool card
      const expandedTool = document.querySelector('[aria-expanded="true"][aria-label^="Tool:"]');
      if (expandedTool) {
        const button = expandedTool.querySelector('button[aria-expanded="true"]');
        if (button instanceof HTMLElement) {
          button.click();
          return;
        }
      }

      // Priority 3: Close mention dropdown
      const mentionList = document.querySelector('[role="listbox"]');
      if (mentionList) {
        // The MentionAutocomplete component handles this via its own keydown
        return;
      }

      // Priority 4: Focus input
      const input = document.querySelector<HTMLTextAreaElement>('[data-chat-input]');
      input?.focus();
    }, []);

    useEffect(() => {
      document.addEventListener('keydown', handleEscape);
      return () => document.removeEventListener('keydown', handleEscape);
    }, [handleEscape]);
  }
  ```

  Call `useEscapeHandler()` in `App.tsx`.

- [ ] **20.12 Implement arrow key navigation for dropdowns**

  In `MentionAutocomplete.tsx`, handle arrow keys:

  ```tsx
  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (!showMentions) return;

    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault();
        setActiveIndex(prev => Math.min(prev + 1, results.length - 1));
        break;
      case 'ArrowUp':
        e.preventDefault();
        setActiveIndex(prev => Math.max(prev - 1, 0));
        break;
      case 'Enter':
        e.preventDefault();
        if (results[activeIndex]) {
          selectMention(results[activeIndex]);
        }
        break;
      case 'Escape':
        e.preventDefault();
        setShowMentions(false);
        break;
    }
  }, [showMentions, activeIndex, results, selectMention]);
  ```

  Apply same pattern to model selector dropdown if it uses a custom listbox.

- [ ] **20.13 Implement Ctrl/Cmd+K shortcut**

  In `App.tsx`, add a global keyboard shortcut to focus the input bar:

  ```tsx
  useEffect(() => {
    const handleGlobalKeys = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        const input = document.querySelector<HTMLTextAreaElement>('[data-chat-input]');
        input?.focus();
      }
    };
    document.addEventListener('keydown', handleGlobalKeys);
    return () => document.removeEventListener('keydown', handleGlobalKeys);
  }, []);
  ```

- [ ] **20.14 Implement focus management for overlays**

  In `FullscreenOverlay.tsx`, add focus trapping:

  ```tsx
  import { useEffect, useRef, useCallback } from 'react';

  // Store the element that had focus before the overlay opened
  const previousFocusRef = useRef<HTMLElement | null>(null);
  const overlayRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    // Save current focus and move to overlay
    previousFocusRef.current = document.activeElement as HTMLElement;

    // Focus the first focusable element in the overlay
    const firstFocusable = overlayRef.current?.querySelector<HTMLElement>(
      'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
    );
    firstFocusable?.focus();

    return () => {
      // Restore focus on close
      previousFocusRef.current?.focus();
    };
  }, []);

  // Trap focus within overlay
  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'Escape') {
      handleClose();
      return;
    }

    if (e.key !== 'Tab') return;

    const focusableElements = overlayRef.current?.querySelectorAll<HTMLElement>(
      'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
    );
    if (!focusableElements?.length) return;

    const first = focusableElements[0];
    const last = focusableElements[focusableElements.length - 1];

    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  }, [handleClose]);
  ```

- [ ] **20.15 Auto-focus input after sending message**

  In `PromptInput.tsx`, after the message is sent, refocus the textarea:

  ```tsx
  const inputRef = useRef<HTMLTextAreaElement>(null);

  const handleSend = useCallback(() => {
    if (!text.trim()) return;
    sendMessage(text, mentions);
    setText('');
    setMentions([]);
    // Refocus input after send
    requestAnimationFrame(() => {
      inputRef.current?.focus();
    });
  }, [text, mentions, sendMessage]);
  ```

  Add `data-chat-input` attribute for the global Ctrl/Cmd+K shortcut:

  ```tsx
  <textarea
    ref={inputRef}
    data-chat-input
    // ... other props
  />
  ```

- [ ] **20.16 Add screen reader announcements**

  Create `agent/webview/src/components/common/ScreenReaderAnnouncer.tsx` — a visually hidden live region for status announcements:

  ```tsx
  import { useEffect, useState, useCallback, memo } from 'react';

  const announcements: string[] = [];
  let listeners: Array<(msg: string) => void> = [];

  /** Call this from anywhere to announce to screen readers */
  export function announce(message: string) {
    listeners.forEach(fn => fn(message));
  }

  /** Render once in App.tsx — provides the live region */
  export const ScreenReaderAnnouncer = memo(function ScreenReaderAnnouncer() {
    const [message, setMessage] = useState('');

    useEffect(() => {
      const handler = (msg: string) => {
        // Clear and re-set to ensure announcement even if same text
        setMessage('');
        requestAnimationFrame(() => setMessage(msg));
      };
      listeners.push(handler);
      return () => {
        listeners = listeners.filter(fn => fn !== handler);
      };
    }, []);

    return (
      <div
        role="status"
        aria-live="polite"
        aria-atomic="true"
        className="sr-only"
        style={{
          position: 'absolute',
          width: '1px',
          height: '1px',
          padding: 0,
          margin: '-1px',
          overflow: 'hidden',
          clip: 'rect(0, 0, 0, 0)',
          whiteSpace: 'nowrap',
          border: 0,
        }}
      >
        {message}
      </div>
    );
  });
  ```

  Add announcements in key locations:

  ```tsx
  // In chatStore.ts — when tool call starts
  addToolCall(name, args, status) {
    // ... existing logic
    announce(`Tool ${name} started`);
  },

  // In chatStore.ts — when tool call completes
  updateToolCall(name, status, result, duration) {
    // ... existing logic
    announce(`Tool ${name} ${status === 'error' ? 'failed' : 'completed'}`);
  },

  // In chatStore.ts — when session completes
  completeSession(info) {
    // ... existing logic
    announce(`Session ${info.status}. ${info.tokensUsed} tokens used.`);
  },

  // In chatStore.ts — on error
  // When appendStatus is called with type 'ERROR':
  announce(`Error: ${message}`);
  ```

  Add `<ScreenReaderAnnouncer />` to `App.tsx`:

  ```tsx
  function App() {
    return (
      <ThemeProvider>
        <ScreenReaderAnnouncer />
        <ChatContainer />
      </ThemeProvider>
    );
  }
  ```

- [ ] **20.17 Add focus-ring to all interactive elements**

  The `focus-ring` class is defined in `animations.css` (step 19.2). Add it to all buttons, links, and interactive elements. For components using a shared `Button` or `IconButton` wrapper, add it once at the wrapper level. For inline buttons, add `focus-ring` alongside `press-feedback`:

  ```tsx
  // Pattern for all interactive elements:
  className="press-feedback focus-ring ..."
  ```

  Ensure the Tailwind config includes `sr-only` utility (Tailwind v4 includes it by default).

- [ ] **20.18 Verify accessibility**

  ```bash
  cd agent/webview && npm run dev
  ```

  In the browser:
  1. **Tab navigation:** Press Tab repeatedly — verify focus moves through: header actions -> message interactive elements -> input bar -> scroll button. Focus ring (2px outline) visible on each element.
  2. **Escape cascading:** Open an overlay -> press Escape -> overlay closes. Expand a tool card -> press Escape -> card collapses. With mention dropdown open -> press Escape -> dropdown closes. With nothing open -> press Escape -> input focused.
  3. **Arrow keys:** Trigger `@` mention -> use arrow keys to navigate results -> Enter to select.
  4. **Ctrl/Cmd+K:** From any point in the page, press Ctrl+K -> input bar focused.
  5. **Overlay focus trap:** Open fullscreen overlay -> Tab -> verify focus stays within overlay -> Escape -> verify focus returns to the element that opened the overlay.
  6. **Auto-focus:** Send a message -> verify input is automatically refocused.
  7. **Screen reader:** Enable VoiceOver (macOS) or screen reader emulator -> verify announcements for: tool started, tool completed, session completed, errors.
  8. **ARIA audit:** Open Chrome DevTools Accessibility panel -> verify no violations for roles, labels, or live regions.
  9. Confirm no TypeScript errors

- [ ] **20.19 Commit**

  ```
  feat(agent): add accessibility — keyboard navigation, ARIA attributes, focus management, screen reader announcements, Escape cascading, Ctrl+K shortcut
  ```

---

### Task 21: Gradle Integration & Editor Tab Popout

**Objective:** Update `CefResourceSchemeHandler.kt` to serve files from `webview/dist/`. Verify the Gradle `buildWebview` task works with the full React build. Create the "Open in editor tab" feature for rich block popout. Update `AgentCefPanel.kt` to load the React app and fix `GlobalScope` usage.

**Estimated time:** 25-30 minutes

#### Steps

- [ ] **21.1 Update `CefResourceSchemeHandler.kt`**

  In `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/CefResourceSchemeHandler.kt`, update the resource resolution to serve from `webview/dist/`:

  ```kotlin
  // Change the default page and resource prefix:

  // BEFORE:
  // val path = url.removePrefix(BASE_URL).takeIf { it.isNotBlank() } ?: "agent-chat.html"
  // val resourcePath = "webview/$path"

  // AFTER:
  val path = url.removePrefix(BASE_URL).takeIf { it.isNotBlank() } ?: "index.html"
  val resourcePath = "webview/dist/$path"
  ```

  Add MIME types for `.woff2` fonts (required for KaTeX):

  ```kotlin
  // In the MIME type map, add:
  "woff2" to "font/woff2",
  "woff" to "font/woff",
  "ttf" to "font/ttf",
  ```

  Ensure the handler serves all code-split chunk filenames correctly. Vite generates filenames like `assets/mermaid-abc123.js`, `assets/katex-def456.js`, etc. The existing path-based resolution (`webview/dist/$path`) handles these natively since the classloader resolves `webview/dist/assets/mermaid-abc123.js` from the JAR.

- [ ] **21.2 Verify Gradle `buildWebview` task**

  In `agent/build.gradle.kts`, verify the `buildWebview` task exists and is wired correctly:

  ```kotlin
  tasks.register<Exec>("buildWebview") {
      workingDir = file("webview")
      commandLine("npm", "run", "build")
  }

  tasks.named("processResources") {
      dependsOn("buildWebview")
  }
  ```

  If the task doesn't exist yet (it was defined in Task 1), create it now.

- [ ] **21.3 Add entries to `.gitignore`**

  Ensure these entries exist in `agent/.gitignore` (or the root `.gitignore`):

  ```gitignore
  # React webview build artifacts and dependencies
  agent/webview/node_modules/
  agent/src/main/resources/webview/dist/
  ```

  The `dist/` directory is a build artifact generated by `npm run build` — it should not be committed.

- [ ] **21.4 Create `AgentVisualizationTab.kt`**

  Create `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentVisualizationTab.kt` — opens a rich visualization in an IDE editor tab using JBCefBrowser:

  ```kotlin
  package com.workflow.orchestrator.agent.ui

  import com.intellij.openapi.fileEditor.FileEditorManager
  import com.intellij.openapi.project.Project
  import com.intellij.openapi.vfs.VirtualFile
  import com.intellij.testFramework.LightVirtualFile
  import com.intellij.openapi.fileEditor.FileEditor
  import com.intellij.openapi.fileEditor.FileEditorProvider
  import com.intellij.openapi.fileEditor.FileEditorPolicy
  import com.intellij.openapi.fileEditor.FileEditorState
  import com.intellij.openapi.fileTypes.FileType
  import com.intellij.openapi.util.Disposer
  import com.intellij.openapi.util.Key
  import com.intellij.ui.jcef.JBCefBrowser
  import java.beans.PropertyChangeListener
  import javax.swing.JComponent

  /**
   * Opens a rich visualization (mermaid, chart, diff, etc.) in a dedicated
   * editor tab using JCEF. Called from the React chat UI when user clicks
   * "Open in Tab" on a RichBlock.
   */
  object AgentVisualizationTab {

      private val VIZ_TYPE_KEY = Key.create<String>("AGENT_VIZ_TYPE")
      private val VIZ_CONTENT_KEY = Key.create<String>("AGENT_VIZ_CONTENT")

      /**
       * Open a visualization in a new editor tab.
       * @param project The current project
       * @param type Visualization type: "mermaid", "chart", "flow", "math", "diff", "html"
       * @param content The raw content (mermaid source, chart JSON, diff text, etc.)
       */
      fun open(project: Project, type: String, content: String) {
          val fileName = "Agent ${type.replaceFirstChar { it.uppercase() }} Visualization"
          val file = LightVirtualFile(fileName, AgentVizFileType, "")
          file.putUserData(VIZ_TYPE_KEY, type)
          file.putUserData(VIZ_CONTENT_KEY, content)

          FileEditorManager.getInstance(project).openFile(file, true)
      }

      /**
       * Custom file type to trigger our FileEditorProvider.
       */
      object AgentVizFileType : FileType {
          override fun getName() = "AgentVisualization"
          override fun getDescription() = "Agent Visualization"
          override fun getDefaultExtension() = "agentviz"
          override fun getIcon() = null
          override fun isBinary() = false
          override fun isReadOnly() = true
      }

      /**
       * FileEditorProvider that creates a JCEF-based editor for visualizations.
       */
      class AgentVizEditorProvider : FileEditorProvider {
          override fun accept(project: Project, file: VirtualFile): Boolean {
              return file.getUserData(VIZ_TYPE_KEY) != null
          }

          override fun createEditor(project: Project, file: VirtualFile): FileEditor {
              val type = file.getUserData(VIZ_TYPE_KEY) ?: "unknown"
              val content = file.getUserData(VIZ_CONTENT_KEY) ?: ""
              return AgentVizEditor(project, type, content)
          }

          override fun getEditorTypeId() = "AgentVisualizationEditor"
          override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
      }

      /**
       * FileEditor implementation that renders a visualization in JBCefBrowser.
       */
      private class AgentVizEditor(
          private val project: Project,
          private val vizType: String,
          private val content: String,
      ) : FileEditor {
          private val browser = JBCefBrowser()

          init {
              // Build a standalone HTML page that renders the visualization
              val html = buildVisualizationHtml(vizType, content)
              browser.loadHTML(html)
          }

          override fun getComponent(): JComponent = browser.component
          override fun getPreferredFocusedComponent(): JComponent = browser.component
          override fun getName() = "Visualization"
          override fun setState(state: FileEditorState) {}
          override fun isModified() = false
          override fun isValid() = true
          override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
          override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
          override fun dispose() {
              Disposer.dispose(browser)
          }
          override fun getFile(): VirtualFile? = null

          private fun buildVisualizationHtml(type: String, content: String): String {
              val escapedContent = content
                  .replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&#39;")

              // The HTML includes the relevant library inline and renders the content.
              // This is a standalone page — it does not depend on the React app.
              return """
              <!DOCTYPE html>
              <html lang="en">
              <head>
                <meta charset="UTF-8">
                <style>
                  body {
                    margin: 0; padding: 16px;
                    background: #1e1e1e; color: #d4d4d4;
                    font-family: 'JetBrains Mono', monospace;
                  }
                  pre { white-space: pre-wrap; word-break: break-word; }
                  #content { width: 100%; height: 100%; }
                </style>
              </head>
              <body>
                <div id="content">
                  <pre>${escapedContent}</pre>
                </div>
                <script>
                  // For mermaid/chart/etc., the React app will pass rendered SVG or
                  // configuration. For MVP, display the raw content.
                  // Enhanced rendering per viz type can be added incrementally.
                </script>
              </body>
              </html>
              """.trimIndent()
          }
      }
  }
  ```

  Register the `FileEditorProvider` in `plugin.xml`:

  ```xml
  <extensions defaultExtensionNs="com.intellij">
      <fileEditorProvider
          implementation="com.workflow.orchestrator.agent.ui.AgentVisualizationTab$AgentVizEditorProvider" />
  </extensions>
  ```

- [ ] **21.5 Add bridge function for editor tab popout**

  In `agent/webview/src/bridge/jcef-bridge.ts`, add the `_openInEditorTab` bridge function:

  ```typescript
  // Add to the bridge registration
  export function openInEditorTab(type: string, content: string): void {
    const bridge = (window as any)._openInEditorTab;
    if (bridge) {
      bridge(JSON.stringify({ type, content }));
    } else {
      console.warn('[bridge] _openInEditorTab not available — running in browser mode');
    }
  }
  ```

  In `AgentCefPanel.kt`, register the new `JBCefJSQuery`:

  ```kotlin
  private val openInEditorTabQuery = JBCefJSQuery.create(browser).also { query ->
      query.addHandler { payload ->
          try {
              val json = JSONObject(payload)
              val type = json.getString("type")
              val content = json.getString("content")
              invokeLater {
                  AgentVisualizationTab.open(project, type, content)
              }
          } catch (e: Exception) {
              LOG.warn("Failed to open visualization in editor tab", e)
          }
          null
      }
  }
  ```

  Inject the bridge in `injectBridges()`:

  ```kotlin
  browser.cefBrowser.executeJavaScript(
      "window._openInEditorTab = function(payload) { ${openInEditorTabQuery.inject("payload")} };",
      browser.cefBrowser.url, 0
  )
  ```

- [ ] **21.6 Wire "Open in Tab" button in RichBlock**

  In `agent/webview/src/components/rich/RichBlock.tsx`, import and call the bridge:

  ```tsx
  import { openInEditorTab } from '@/bridge/jcef-bridge';

  // In the RichBlock header action buttons:
  <button
    aria-label="Open in editor tab"
    className="press-feedback focus-ring ..."
    onClick={() => openInEditorTab(type, rawContent)}
  >
    {/* External link / popout icon */}
  </button>
  ```

- [ ] **21.7 Update `AgentCefPanel.kt` page URL**

  In `AgentCefPanel.kt`, update the page URL to load the React build output:

  ```kotlin
  // BEFORE:
  // private const val PAGE_URL = "http://workflow-agent/agent-chat.html"

  // AFTER:
  private const val PAGE_URL = "http://workflow-agent/dist/index.html"
  ```

  All bridge initialization (`injectBridges()`, `applyCurrentTheme()`, etc.) remains identical — the React app exposes the same global functions.

- [ ] **21.8 Fix `GlobalScope` usage in `AgentCefPanel.kt`**

  Replace `GlobalScope.launch` in the `searchMentionsQuery` handler with a project-scoped `CoroutineScope`:

  ```kotlin
  // Add to AgentCefPanel class:
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  // In searchMentionsQuery handler, replace:
  // GlobalScope.launch(Dispatchers.IO) { ... }
  // With:
  scope.launch { ... }

  // In dispose(), add:
  override fun dispose() {
      scope.cancel()
      // ... existing dispose logic
  }
  ```

- [ ] **21.9 Verify Gradle build end-to-end**

  ```bash
  cd agent/webview && npm install && npm run build
  ```

  Verify:
  1. `agent/src/main/resources/webview/dist/index.html` exists
  2. `agent/src/main/resources/webview/dist/assets/` contains JS/CSS chunks
  3. Code-split chunks exist: `mermaid-*.js`, `katex-*.js`, `chartjs-*.js`, `dagre-*.js`, `diff2html-*.js`
  4. Font files exist if KaTeX is included

  Then run the full Gradle build:

  ```bash
  ./gradlew :agent:test
  ./gradlew verifyPlugin
  ./gradlew buildPlugin
  ```

  Verify all tests pass and the plugin ZIP is generated.

- [ ] **21.10 Commit**

  ```
  feat(agent): update CefResourceSchemeHandler for React dist, add editor tab popout for visualizations, fix GlobalScope to project-scoped coroutine, wire Gradle buildWebview task
  ```

---

### Task 22: Cutover & Cleanup

**Objective:** Delete all legacy files (old HTML, JS libraries, Swing fallback panel). Simplify `AgentDashboardPanel.kt` to JCEF-only. Run full build and test suite. Update documentation in `agent/CLAUDE.md` and root `CLAUDE.md`.

**Estimated time:** 20-25 minutes

#### Steps

- [ ] **22.1 Delete old HTML files**

  Delete the legacy monolithic HTML files that are now replaced by the React app:

  ```bash
  rm agent/src/main/resources/webview/agent-chat.html
  rm agent/src/main/resources/webview/agent-plan.html
  ```

- [ ] **22.2 Delete old JS library directory**

  Delete the entire `lib/` directory containing vendored JS libraries (marked, prism, purify, ansi_up, mermaid, katex, chart, dagre, diff2html and all sub-files). These are now bundled by Vite from `node_modules`:

  ```bash
  rm -rf agent/src/main/resources/webview/lib/
  ```

- [ ] **22.3 Delete `RichStreamingPanel.kt`**

  Delete the Swing fallback panel that is no longer needed (JCEF-only target):

  ```bash
  rm agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/RichStreamingPanel.kt
  ```

  Search for any imports or references to `RichStreamingPanel` in other Kotlin files and remove them:

  ```bash
  grep -r "RichStreamingPanel" agent/src/main/kotlin/ --include="*.kt" -l
  ```

  For each file found, remove the import and any usage.

- [ ] **22.4 Simplify `AgentDashboardPanel.kt`**

  In `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt`:

  - Remove the JCEF availability check (`JBCefApp.isSupported()` or similar)
  - Remove the fallback logic that creates `RichStreamingPanel` when JCEF is unavailable
  - Remove any `RichStreamingPanel` imports
  - Simplify to always create `AgentCefPanel` directly

  The panel should look something like:

  ```kotlin
  // BEFORE (with fallback):
  // val chatPanel = if (JBCefApp.isSupported()) {
  //     AgentCefPanel(project, disposable)
  // } else {
  //     RichStreamingPanel(project)
  // }

  // AFTER (JCEF only):
  val chatPanel = AgentCefPanel(project, disposable)
  ```

  Remove any conditional logic, fallback messages, or degraded-mode flags.

- [ ] **22.5 Build the React app**

  ```bash
  cd agent/webview && npm run build
  ```

  Verify output:
  1. `agent/src/main/resources/webview/dist/index.html` exists and is well-formed
  2. `agent/src/main/resources/webview/dist/assets/` contains hashed JS/CSS chunks
  3. No old files remain in `agent/src/main/resources/webview/` (except `dist/`)

- [ ] **22.6 Run test suite**

  ```bash
  ./gradlew :agent:test
  ```

  All existing agent tests must pass. If any tests reference `RichStreamingPanel`, `agent-chat.html`, or the old `lib/` directory, update them:
  - Tests that check for `agent-chat.html` should check for `dist/index.html`
  - Tests that reference `RichStreamingPanel` should be updated or removed
  - Tests that check resource serving should verify `webview/dist/` paths

- [ ] **22.7 Run plugin verification**

  ```bash
  ./gradlew verifyPlugin
  ```

  Fix any API compatibility issues flagged by the verification.

- [ ] **22.8 Run full build**

  ```bash
  ./gradlew buildPlugin
  ```

  Verify the installable ZIP is generated and contains the `webview/dist/` files in the JAR.

- [ ] **22.9 Update `agent/CLAUDE.md`**

  Add a "Chat UI" section to `agent/CLAUDE.md` documenting the React app architecture:

  ```markdown
  ## Chat UI

  The agent chat interface is a React 18 + TypeScript + Tailwind CSS v4 application,
  built with Vite, served via JCEF (embedded Chromium).

  ### Source
  - React source: `agent/webview/src/`
  - Build output: `agent/src/main/resources/webview/dist/` (git-ignored)
  - Entry point: `agent/webview/src/main.tsx`

  ### Build Commands
  ```
  cd agent/webview
  npm install          # Install dependencies (first time)
  npm run dev          # Vite dev server on localhost:5173 (standalone browser testing)
  npm run build        # Production build -> webview/dist/
  ```

  The Gradle `buildWebview` task runs `npm run build` automatically as part of
  `./gradlew buildPlugin`.

  ### Architecture
  - **State:** Zustand stores (`chatStore`, `settingsStore`, `themeStore`)
  - **Bridge:** `bridge/jcef-bridge.ts` — 26 JS-to-Kotlin + 44 Kotlin-to-JS bridge functions
  - **Markdown:** react-markdown + remark-gfm + rehype-raw
  - **Syntax:** Shiki (15 preloaded languages, lazy-load others)
  - **Visualizations:** Mermaid, Chart.js, dagre, KaTeX, diff2html — all lazy-loaded via dynamic import
  - **Virtual scroll:** @tanstack/react-virtual at 100+ messages
  - **Animations:** CSS-only (no framer-motion), prefers-reduced-motion supported

  ### Key Files
  | File | Purpose |
  |------|---------|
  | `AgentCefPanel.kt` | JCEF panel, bridge registration, theme injection |
  | `CefResourceSchemeHandler.kt` | Serves React build from JAR via custom scheme |
  | `AgentDashboardPanel.kt` | Tool window tab creation (JCEF only, no fallback) |
  | `AgentVisualizationTab.kt` | Opens rich viz in IDE editor tab |
  | `webview/src/bridge/jcef-bridge.ts` | JS<->Kotlin bridge protocol |
  | `webview/src/stores/chatStore.ts` | Message state, streaming, tool calls |
  ```

- [ ] **22.10 Update root `CLAUDE.md`**

  In the root `CLAUDE.md`, update the agent module description in the module table:

  ```markdown
  | `:agent` | Cody CLI agent (JSON-RPC), AI fixes, commit messages, context enrichment, React chat UI (Vite + JCEF) |
  ```

  Add `npm` build commands to the Build & Verify section:

  ```markdown
  cd agent/webview && npm run dev   # Standalone chat UI development
  cd agent/webview && npm run build # Production build (auto-runs via Gradle)
  ```

- [ ] **22.11 Final verification in IDE**

  ```bash
  ./gradlew runIde
  ```

  In the sandbox IDE:
  1. Open the Workflow tool window -> Agent tab
  2. Verify the React chat UI loads (not the old HTML)
  3. Send a message — verify streaming, tool calls, thinking blocks work
  4. Trigger a rich visualization — verify it renders with skeleton loading
  5. Click "Open in Tab" on a rich block — verify it opens in an editor tab
  6. Switch IDE theme (light/dark) — verify colors update live
  7. Check keyboard navigation — Tab, Escape, Ctrl+K all work
  8. Open Settings > Tools > Workflow Orchestrator > AI & Advanced — verify Visualizations section

- [ ] **22.12 Commit**

  ```
  feat(agent): complete React chat UI cutover — delete legacy HTML/JS/Swing fallback, simplify to JCEF-only, update documentation
  ```

---

**Plan complete.** Tasks 1-22 cover the full migration from the monolithic 4,374-line HTML file to a React 18 + TypeScript + Tailwind CSS application. After Task 22, the old UI code is deleted, the React app is the sole chat interface, and all documentation reflects the new architecture.
