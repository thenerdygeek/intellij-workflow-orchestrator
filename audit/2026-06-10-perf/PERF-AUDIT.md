# Performance Audit — 2026-06-10

## Fix status

**Wave 1 (`perf/wave1-quick-wins`, 2026-06-10) — FIXED:** P0-4 (`a3f3cc8cb` SmartPoller focus gate), P1-11 (`f78caeacd` Insights single load + backoff), P1-6 (`a1dfc222e` lazy monitor flush loop), P1-7 (`69f2bd0f3` lazy BackgroundPool supervisor), P1-9 + B14 (`6f37dd707` handover watcher take()), P1-10 (`8fedfe530` Haiku phrase activity gate), P2-1 + B12 (`0b2a3b2e2` stream batcher timers), B1 (`8f87daf6e` AgentConfigLoader app-level lifecycle). **Wave 2 (`perf/wave2-persistence-cadence`, 2026-06-10) — FIXED:** P0-1 + B17 + B3 + B2 (`fe3e7b38e` throttled partial persistence + atomic last-partial + snapshot lock; production wiring in `f04d5bbd0`), P1-4 + B15 (`e663bf7d6` incremental index totals + boundary flush), P1-1 (`9aeacbf42` burst-coalesced api writes), P1-2 (`f04d5bbd0` CharSequence parser + '>' gate), P1-3 (`c2cde1b28` two-row LCS + affix trim + per-file diff cache), P1-5 (`cd0848f7d` tool-def token memo), B6 + B18 + P2-4 (`054c272a2` spill collision counter + fsync + tail-ring). **DEFERRED with rationale:** P2-5 (persisted ui_messages segmenting risks resume compat; webview caps at 1000 — revisit post-profiler), P2-6 partial (AtomicFileWriter Thread.sleep retry is Windows-AV-only ≤300ms; CoverageTool scopes dispose correctly; the FileLock acquire remains blocking — acceptable at the new ≤1Hz cadence).

**Wave 3 (`perf/waves3-6`, 2026-06-11) — FIXED:** P0-2 + B22 + P2-16 (`04ec37724` manualChunks merging dropped → lazy-chunk boundaries restored + `shiki.ts` doc contract agrees again, sandbox static lucide import fixed, dead `ToolCallView.tsx` deleted; comment accuracy `61565ef2e`), P0-3 + P1-13 + P1-14 + P2-13 + P2-14 + B9 + B10 (`7fe5bd3dc` sub-agent side-channel stream buffers keyed by agentId, stable `renderItem` ToolCall identities, memoized ChatFooter, `selectActiveSubAgents` narrowed, ChatView off the 60 Hz `streamingText` subscription, `showApproval` flushes in-flight thinking, tool-drain path goes through `capMessages`; review fixes `4c25bff5e` killed-sub-agent output preserved + stream slices reset on session transitions + thinking order/live tokens), P1-15 + P1-16 + P2-15 + P2-17 + B8 (`4ccdda198` per-key terminal selectors + tail-bounded ANSI, virtualized HistoryView + memoized SessionCard, tool-output cap at finalize, prefers-reduced-motion honored, end-of-stream scroll uses `renderItems` index; review fixes `2c0878a6c` output cap on all four commit paths + Virtuoso slot hoisting).

**Wave 4 (`perf/waves3-6`, 2026-06-11) — FIXED:** P0-6 + B4 (`94232cb3b` lazy extension tabs + Content-scoped JCEF disposal — no eager Chromium, no browser/controller leak on tab rebuild, contentManagerListener registered once; cross-track test pin `62f590a72`), P0-7 + P1-17 + P2-18 (`186b2f9f3` history click off EDT mirroring `showHistory`, single-pass JS escaper replaces the 8 sequential replace passes, attachment-exists check made async), P1-12 + P2-2 + P2-11 + B7 (`d16854de0` thinking + sub-agent deltas routed through 16 ms batchers, mirror replay log → bounded `ReplayRing` with O(1) add + newest-kept eviction (fixes the inverted cap) + unconditional recording, Kotlin-side tool-window visibility signal pushed into the page (`window.__wfToolWindowVisible` + `wf-visibility-change`), `showSession` generation guard). **P2-3 — ACCEPTED:** each editor-tab mirror owns its JBCefBrowser by design (JCEF can't share one render surface across the tool window and editor tabs; disposal correct) — documented in `agent/CLAUDE.md`.

**Wave 5 C1 (`perf/waves3-6`, 2026-06-11) — FIXED:** P0-5 (`cf7fcd2cc` observer-gated Bamboo build polling — background-rate until the Build tab attaches, 5 min heartbeat once the focused build is terminal with no observer; review `47b49bb7a` heartbeat instead of full stop so a remotely-triggered NEW build still surfaces and Handover/Automation consumers don't go stale), P1-8 (terminal-state auto-stop built into `PollingSource.isTerminal` transition detection, implemented by `BambooMonitorSource`; review `47b49bb7a` self-stopped monitors mark the pool handle EXITED), B16 (PR poll lifecycle — `stopPolling()` wired to `PrDashboardPanel.dispose()`, restart on re-show), B13 (SmartPoller overlap guard — `setVisible(true)` resume serializes behind the in-flight loop poll via `pollMutex`; `currentBackoff` now `@Volatile`).

**Wave 5 C2 (`perf/waves3-6`, 2026-06-11, `c5e0b8de1`; review fixes `ec2e63131` watcher rescan-on-arm + synchronized start, PCE rethrow in PSI enricher) — FIXED:** P2-7 (digest deferred 4 min past startup + all file IO on Dispatchers.IO), P2-9 (Test-Connection PasswordSafe fallback read moved into the background task), P2-10 (RetryInterceptor cumulative 15s sleep budget; per-attempt caps unchanged), P2-12 partial (persona WatchService lazily armed on first config read; **ModelPricing + handover TemplateStore watchers untouched — shared-watcher consolidation is FUTURE WORK**, deliberately not attempted in this batch), P2-21 (`BranchChangedEventEmitter` + `AutoDetectFileListener` reparented to new `WorkflowPluginDisposable` APP/PROJECT light service; the `AgentTabProvider.kt:69,75` sub-item is closed-by-B4 — at HEAD the file's only two `Disposer.register` calls (lines 59, 81) already parent to the Content-scoped dashboard that the B4 self-parenting fix introduced, and lines 69/75 are comments), P2-22 + B19 (PsiContextEnricher single readAction, file resolved once, pure-data snapshot — no PSI element crosses the boundary), P2-24 (EventBus emit log INFO → DEBUG). **P2-8 — REJECTED after reading the cascade:** the two 5s HTTP lookups are strictly sequential by data dependency (`LatestBuildLookup` consumes the chain key `ChainKeyResolver` produces); nothing independent to parallelize. The async-fill alternative (emit now, patch `focusBuild` in a later second emission) was also rejected — it would violate the single-merged-emission invariant (§4.4) that `computeFocusForPr`'s KDoc preserves, and the lookups are 5s-bounded — documented in `computeFocusForPr`.

**Wave 5 C3 (`perf/waves3-6`, 2026-06-11, `0aa732646`; test pins `ea186c82b`) — FIXED:** P2-19 (`DocumentArtifactStore.slice()` now does a ranged char read — the index stores CHAR/UTF-16 offsets while content.md is UTF-8, so the read happens in char space via `Reader.skip`, never byte-seek; window length comes from `meta.contentLength`; `search()` keeps its full-text scan but serves repeat scans from a single-slot `SoftReference` memo keyed by (path, contentHash) — pinned by `DocumentArtifactStoreRangedReadTest`). **P2-23 — SKIPPED (documented):** the doorbell socket binds per open project by design — cross-IDE delegation needs the listener armed before the FIRST inbound ring, and the always-bound doorbell is also the `Ping` liveness contract (`TargetStatusResolver.dualProbeStatus` reads an unbound doorbell as "IDE not running", so lazy binding would mis-classify running IDEs with inbound OFF). Cost is one parked accept-thread + one fd per project. Acceptable; revisit only if profiling shows otherwise.

**Wave 6 (`perf/waves3-6`, 2026-06-11) — FIXED:** P1-18 + P1-19 + B21 + B11 + P2-20 sonar slice (`ddaca24b7` per-project/per-file header cache `CoverageFileHeaderCache` invalidated via `SonarDataService.clearLineCoverageCache` + project close (partially-resolved headers never cached), one-marker-per-line leaf gate `CoverageMarkerEmitGate`, allocation-free colored issue renderer (cached borders/badges/parsed dates), coverage table model equality gate + pre-formatted cell cache, 300 ms debounced search preserving selection/preview via `CoverageFilterSelection`), P1-20 + P1-21 + B20 + P2-20 pullrequest/automation slice (`99f1a3d6c` rubber-stamp finding/comment/file renderers, MonitorPanel one-EDT-hop coalesced apply per poll cycle (`mergePolledEntries`) + structural-equality detail-pane skip, LAF-safe `LafFontCache`/`RendererFonts` replacing the stale static font metrics), B5 + B23 + P2-20 bamboo/handover/jira slice (`f65d18bf3` artifact Download/Open made clickable via renderer hit-test, ManualStageDialog debounced resize, StageListPanel cached snapshot, ChecksTab checklist-flag equality gate, rubber-stamp CopyrightFixCard renderer, CurrentWorkSection listener-once + branch-picker readAction off EDT, hoisted JiraSearchContributorFactory renderer + cached monospace fonts, stale runBlocking inventories corrected; review fixes `3edaa0a90` deterministic nested layout in artifact hit-test, delegate to tested resolver, left-click guard, immediate first measure, popup isShowing guard).

**No findings remain OPEN.** Every P0/P1/P2/B item above has a recorded disposition (FIXED / DEFERRED / PARTIAL / ACCEPTED / SKIPPED / REJECTED). Open remainders carried forward as future work: P2-5 (deferred), P2-6 FileLock acquire (partial), P2-12 ModelPricing + handover TemplateStore watcher consolidation (partial). Wave map: `docs/superpowers/plans/2026-06-10-perf-campaign-overview.md`.

Full-plugin performance audit (emphasis `:agent`). Six parallel code-reading passes: agent backend, JCEF bridge (Kotlin), React webview, `:core`, background/periodic work, feature-module Swing UI. All findings verified against source unless marked SUSPECTED. No fixes applied at audit time — see the Fix status ledger above for dispositions.

**Target hardware:** must be usable on Intel i3. Symptom: plugin "takes a toll" even on Core Ultra 7.

---

## The story (why it lags)

Four compounding mechanisms, in order of impact:

1. **Write-amplified persistence in the streaming hot path.** Every SSE chunk re-serializes and rewrites the *entire* `ui_messages.json` (atomic tmp+rename), awaited inline in `onChunk` → disk I/O back-pressures the stream; O(session) per chunk → O(n²) per response. Same shape (per-message full rewrite) for `api_conversation_history.json`, the 1 Hz `sessions.json` index rewrite under a cross-process FileLock, and an LCS diff with ~100 MB DP arrays after every write tool.
2. **The webview pays ~8 MB of eager JS at startup and re-renders the whole visible chat at 60 fps during sub-agent streams.** `manualChunks` in vite.config merges all lazily-imported chunks (shiki 3.1 MB, mermaid 2.9 MB, lucide 590 KB, recharts, d3) into static deps of main. Sub-agent deltas rebuild the entire `messages[]` array identity per 16 ms batch.
3. **Background work never truly idles.** SmartPoller's focus check is a tautology (`lastFocusedFrame != null` is always true) so the 4× unfocused slowdown is dead plugin-wide; Bamboo build polling auto-starts at project open with no UI consumer; a 200 ms monitor-flush loop and a 500 ms background-pool supervisor run forever from `AgentService` construction even with zero monitors/processes; an LLM call fires every 30 s during runs just for a cosmetic status phrase.
4. **Chromium spawns eagerly and leaks on tab rebuild.** Extension tabs (Agent, Insights) bypass the lazy-tab mechanism → JCEF (~150-250 MB) spawns when the Workflow tool window opens even if Agent is never clicked. `AgentDashboardPanel` is not `Disposable`, so every "Refresh All Tabs" / settings change spawns a NEW Chromium + AgentController while the old ones live (and keep collecting EventBus events) until project close.

---

## P0 — user-visible lag/freeze, fix first

| # | Finding | Where |
|---|---|---|
| P0-1 | **Full-file `ui_messages.json` rewrite on EVERY SSE chunk**, awaited in `onChunk` (O(n²)/response, GBs of SSD writes on long sessions). Fix: debounce persistence (250–500 ms) + flush at stream end. | `AgentLoop.kt:1116-1132` → `MessageStateHandler.kt:124-129,487-498` |
| P0-2 | **Webview `manualChunks` defeats all lazy loading** — shiki/mermaid/lucide/recharts/d3/roughjs (~8 MB) become static deps of main, modulepreloaded at startup. Verified in built `dist/`. Also `sandbox-main.ts:18` `import * as LucideIcons`. Fix: remove/scope the manualChunks rules, fix sandbox import, verify `dist/index.html` preloads. | `agent/webview/vite.config.ts:41-52`, `src/lib/shiki.ts:8-10` |
| P0-3 | **Sub-agent streaming rebuilds entire `messages[]` per 16 ms batch** → new array identity → ChatView renderItems re-walk → every visible row re-renders at up to 60 fps (compounded by P1-13 unstable props). Fix: side-channel stream buffers keyed by agentId (mirror main agent's `streamingText`), commit on finalize. | `chatStore.ts:2273-2303,2310,2091,2110` |
| P0-4 | **SmartPoller `isIdeFocused()` is a tautology** (`lastFocusedFrame != null`) — focus-aware 4× slowdown is dead for every poller in the product. Fix: real activity check (`ApplicationManager.getApplication().isActive` / focused-window) + consider a deep-idle tier. | `core/polling/SmartPoller.kt:118-128` |
| P0-5 | **BuildMonitorService polls Bamboo forever from project startup with no UI consumer** — focusBuild auto-seed starts a 30 s poller; `visible` defaults `true` so even tab-gating never applies. Fix: poll only with an observer; flip `visible` default. | `bamboo/service/BuildMonitorService.kt:127-143,184-201` |
| P0-6 | **Eager Chromium spawn + browser/controller leak on tab rebuild.** Extension tabs bypass `LazyTabPlaceholder`; `AgentDashboardPanel` is not `Disposable` so `content.setDisposer` never wires; `buildTabs()` rerun (Refresh All Tabs / settings change) spawns a new browser+controller per rerun, old ones survive to project close with live EventBus collectors. Also `addContentManagerListener` accumulates per rebuild. Fix: lazy extension tabs + Content-scoped disposal + register listener once. | `core/toolwindow/WorkflowToolWindowFactory.kt:293-294,332-352`, `agent/ui/AgentTabProvider.kt:44-75`, `AgentDashboardPanel.kt:26-28` |
| P0-7 | **`showSession` (history click) does disk read + JSON decode + full re-encode + 8-pass JS escape synchronously on EDT** → visible freeze on large sessions. (`showHistory()` does it right via Dispatchers.IO.) Fix: mirror showHistory. | `AgentController.kt:4103-4143,4344-4352`, `AgentCefPanel.kt:1683-1685`, `util/JsEscape.kt` |

## P1 — significant waste

### Agent backend (streaming/persistence hot path)
| # | Finding | Where |
|---|---|---|
| P1-1 | `api_conversation_history.json` fully re-serialized + rewritten per message (O(n²)/session, hundreds of MB cumulative writes). Fix: append-only JSONL + compaction, or debounce. | `MessageStateHandler.kt:138-161,500-507` |
| P1-2 | Per-chunk **full re-parse of accumulated text** (`AssistantMessageParser.parse` stateless scan; `matchTagEndingAt` tests ~110 tags per char; even skip-path copies whole buffer via `toString()`; fresh `Regex` compiled per call). ~10⁹ char comparisons for a 100 KB response; ×5 with sub-agents. Fix: incremental parser state, CharSequence, tag trie, hoist regexes. | `AgentLoop.kt:1021-1037,1093`, `core/ai/AssistantMessageParser.kt:32-163,178,203` |
| P1-3 | **LCS aggregate diff after every write tool**: full-matrix DP (`Array(a+1){IntArray(b+1)}` — 5000-line file ≈ 100 MB int[][]) over EVERY file the session ever touched, re-read from disk each time. Fix: diff only the written file, cache by (mtime,len), Myers/histogram diff. | `AgentController.kt:2984-2990` → `SessionCheckpointStore.kt:94-129`, `DiffCalculator.kt:48-58` |
| P1-4 | `sessions.json` global index: 1 Hz read-modify-pretty-rewrite of up to 500 entries under blocking cross-process `FileLock` during streaming, plus `apiHistory.sumOf{}` per call. Fix: update at turn boundaries; incremental token totals. | `MessageStateHandler.kt:487-498,553-624,941-944` |
| P1-5 | Tool definitions JSON-serialized every loop iteration just to estimate tokens (~60 KB × 200 iterations × sub-agents). Fix: memoize on tool-set identity. | `AgentLoop.kt:964-968`, `core/ai/TokenEstimator.kt:33-37`, `SubagentRunner.kt:256` |

### Background work that never idles
| # | Finding | Where |
|---|---|---|
| P1-6 | `AgentMonitorCoordinator` 200 ms flush loop runs forever from construction, even with 0 monitors (5 wakeups/s, defeats CPU idle states). Fix: lazy start on first monitor, park when empty; coalesce window is 2000 ms → 200 ms is 10× oversampled. | `agent/monitor/AgentMonitorCoordinator.kt:82-90` |
| P1-7 | `BackgroundPool` 500 ms supervisor runs forever from `AgentService` init even with 0 background processes (`stopSupervisor()` only called from tests). Fix: start on first handle, stop when empty. | `agent/tools/background/BackgroundPool.kt:142-152,183` |
| P1-8 | Agent monitor sources never auto-stop on terminal state (merged PR / finished build keeps HTTP-polling until session switch; dormancy stops wakes, not sources). Fix: terminality signal from `diff()`; `lifecycle: until_exit|timeout`. | `agent/monitor/PollingSource.kt:18,50`, `MonitorManager` |
| P1-9 | `HandoverTemplateStore` WatchService loop uses `poll(100ms)` busy loop — 10 wakeups/s forever once Handover Share tab built. Fix: blocking `take()` (close() already interrupts it) or ≥5 s timeout. | `handover/service/HandoverTemplateStore.kt:408-465` |
| P1-10 | **Haiku phrase generator fires a full LLM round-trip every 30 s of run time**, including while suspended on approval/question. Fix: pause while awaiting input; gate on activity; opt-in. | `AgentController.kt:4985-5018`, `observability/HaikuPhraseGenerator.kt:153+` |
| P1-11 | Insights poller returns constant `true` (backoff dead, polls 30 s flat) AND each tick triple-parses `sessions.json` (`getTodayStats`+`getWeekStats`+`getSessions` each call `loadSessions`). Fix: load once, real change signal. | `core/toolwindow/insights/InsightsPanel.kt:48-59`, `core/services/InsightsServiceImpl.kt:13-29` |

### JCEF bridge + webview render path
| # | Finding | Where |
|---|---|---|
| P1-12 | Thinking deltas + sub-agent stream deltas bypass `StreamBatcher` → one `executeJavaScript` (+ one `invokeLater`) per SSE chunk, ×5 parallel sub-agents. Fix: route through 16 ms coalescers keyed by agentId. | `AgentController.kt:2547,2824,2830,2740`, `AgentCefPanel.kt:1438-1457` |
| P1-13 | `renderItem` allocates fresh `ToolCall[]` objects per invocation → defeats `ToolCallChain`/`ToolCallItem` memo → re-runs `JSON.parse(tc.args)` etc. per visible row per render. Fix: derive in `renderItems` useMemo / WeakMap by toolCallData identity. | `ChatView.tsx:91-104`, `ToolCallChain.tsx:54-65,312,427` |
| P1-14 | `ChatFooter` re-renders per token batch and rebuilds `Array.from(activeToolCalls.values())` fresh → active tool chain re-renders ~60×/s during prose streaming. Fix: useMemo. | `ChatFooter.tsx:36,61,115` |
| P1-15 | Terminal output: `TerminalContent` subscribes to the ENTIRE `toolOutputStreams` map; expanded+running terminal re-runs `split('\n')` + `stripAnsi` ×2 + `ansi_to_html` over full accumulated buffer per chunk (O(n²)); `run_command` is auto-expanded. Fix: per-key selector, memoize, incremental/tail-only highlight. | `ToolCallChain.tsx:274-275,342`, `components/ui/tool-ui/terminal.tsx:53-70,112` |
| P1-16 | HistoryView session list NOT virtualized, `SessionCard` not memoized, handlers re-created per render — all N sessions render as DOM; search keystroke re-renders all. Fix: virtualize + memo + useCallback. | `HistoryView.tsx:280-295,45-71,28-34`, `SessionCard.tsx:50` |
| P1-17 | Whole-session JSON pushed as ONE `executeJavaScript` string with 8 sequential `String.replace` escape passes (8 full copies of multi-MB string). Fix: single-pass escaper; or serve session state via the `http://workflow-agent` scheme handler and `fetch()` it. | `AgentController.kt:4344-4352`, `AgentCefPanel.kt:1683-1685`, `util/JsEscape.kt` |

### Feature-module Swing
| # | Finding | Where |
|---|---|---|
| P1-18 | `CoverageLineMarkerProvider`: settings lookup + repo resolution + 2× `LocalFileSystem.findFileByPath` + git-branch lookup for every first-child element on every highlighting pass. Fix: per-FILE cache (CachedValuesManager), per-element = map lookup only. | `sonar/ui/CoverageLineMarkerProvider.kt:20-64` |
| P1-19 | `IssueListCellRenderer`: `<html>` label + fresh CompoundBorder per cell per paint, list can be 500+ issues. Fix: ColoredListCellRenderer + cached borders. | `sonar/ui/IssueListPanel.kt:412-425,479-481` |
| P1-20 | `FindingRowRenderer` + `CommentRowRenderer`: whole component tree (2 panels + ~6 labels + deriveFont + 500-600-char HTML parse) allocated per cell per paint; comments list repaints on a 30 s poll. Fix: rubber-stamp pattern + SimpleColoredComponent. | `pullrequest/ui/FindingRowRenderer.kt:26-46`, `CommentRowRenderer.kt:34-71` |
| P1-21 | `MonitorPanel`: one `invokeLater` + full list re-apply PER pollable entry per 15 s tick; `showRunDetail` unconditionally tears down + rebuilds the detail pane even when selection unchanged. Fix: coalesce per cycle; skip when entry unchanged. | `automation/ui/MonitorPanel.kt:334-346,444-455,467-468` |

## P2 — minor / polish

| # | Finding | Where |
|---|---|---|
| P2-1 | `StreamBatcher.append` posts a no-op `invokeLater` per chunk; `flushIfNeeded` doesn't stop timer on empty buffer; `PerToolStreamBatcher.flush(id)` never stops it → 60 Hz EDT timer can tick indefinitely. | `StreamBatcher.kt:36-42,58-63`, `PerToolStreamBatcher.kt:66-91` |
| P2-2 | Mirror replay log: `CopyOnWriteArrayList` append per stream flush (full array copy per add), retains entire conversation in heap even with zero mirrors; cap inverted (keeps OLDEST 5000). | `AgentDashboardPanel.kt:70-103,567-570` |
| P2-3 | Multiple Chromium instances by design: editor chat tab, visualization, plan editor, api-docs, tool-docs each own a JBCefBrowser (~150-250 MB each). Disposal correct; consider reuse/limit. | `AgentChatEditorTab.kt:56-71` et al. |
| P2-4 | `ToolOutputSpiller` materializes large output 3-4× (redact copy, `lines()` split, joins); AgentLoop holds content/processed/truncated concurrently → 1 MB output exists ~5× transiently. | `ToolOutputSpiller.kt:38-62`, `AgentLoop.kt:2098-2123` |
| P2-5 | `ui_messages.json` + in-memory list grow unbounded within a session (compaction only touches apiHistory). | `MessageStateHandler.kt:47,97-102` |
| P2-6 | Blocking on coroutine threads: `AtomicFileWriter` `Thread.sleep` retry under session mutex; blocking `FileLock` under two mutexes; `CoverageTool` ad-hoc scope per test-finish event. | `AtomicFileWriter.kt:76`, `MessageStateHandler.kt:941-944`, `CoverageTool.kt:564,586` |
| P2-7 | WeeklyDigest runs full 7-day collection + REAL LLM call during project startup (Mondays); `reportExistsForThisWeek` file IO before IO context. | `core/insights/WeeklyDigestStartupActivity.kt:38-69` |
| P2-8 | Startup focus cascade: up to 2 sequential 5 s-bounded HTTP lookups under `cascadeMutex` at project open. Parallelize / fill async. | `core/workflow/WorkflowContextService.kt:338-358,487-505` |
| P2-9 | "Test Connection" PasswordSafe fallback read on EDT (Windows first-access = seconds) when pre-warm hasn't landed. | `core/settings/ConnectionsConfigurable.kt:250,350` |
| P2-10 | RetryInterceptor worst case 3×10 s `Thread.sleep` holds OkHttp/IO threads ≈30 s under sustained 429s. | `core/http/RetryInterceptor.kt:60-73` |
| P2-11 | Webview 1 s tickers (UsageIndicator bridge poll, BackgroundIndicator, ThinkingView, etc.) — `document.hidden` gate likely never true in embedded JCEF → keep renderer awake when tool window hidden. SUSPECTED. Fix: Kotlin-side toolWindowShown/Hidden signal. | `UsageIndicator.tsx:51-54` + family |
| P2-12 | Three separate `WatchService` instances (personas, pricing, handover); on macOS each is an internal stat-polling thread. Consolidate / lazy-start. | `AgentConfigLoader.kt:376-394`, `ModelPricing.kt:205`, `HandoverTemplateStore.kt:409` |
| P2-13 | `selectActiveSubAgents` rebuilds a Map over all messages on every store change. | `chatStore.ts:2717-2725`, `ChatView.tsx:27` |
| P2-14 | `ChatView` subscribes to `streamingText` (60 Hz) only to detect stream end. | `ChatView.tsx:26,37-57` |
| P2-15 | Continuous animations while busy (shimmer, wave loader, per-tool indeterminate bars, pulse) — cumulative constant paint on i3. Honor prefers-reduced-motion / pause when hidden. | `WorkingIndicator.tsx:274-275` et al. |
| P2-16 | `ToolCallView.tsx` is 388 lines of dead code (only its tests import it) containing worst-of-class patterns (100 ms live timer, 4-5 JSON.parse per render). Delete. | `ToolCallView.tsx` |
| P2-17 | Large tool outputs retained unbounded in store (`appendToolOutput` concat) + rendered as one text node when expanded; truncate at finalize (full content is on disk via spiller). | `chatStore.ts:1896-1903`, `ToolCallChain.tsx:252-258` |
| P2-18 | `AttachmentStore.readBlocking` (dir scan + read) inside EDT bridge handler per attached file. | `AgentCefPanel.kt:702-720` |
| P2-19 | `DocumentArtifactStore.slice()/search()` re-read the ENTIRE content.md from disk per call (tens of MB for big PDFs); index already has offsets → ranged reads or soft-ref memo. | `document/service/DocumentArtifactStore.kt:155-161,256-257` |
| P2-20 | Swing nits: `ManualStageDialog` HTML cells + full-table re-measure per resize event; `StageDetailPanel.ArtifactCellRenderer` border realloc per render; `CoverageTablePanel` unconditional `fireTableDataChanged` + per-keystroke model rebuild + per-cell `File(...).name`/format; `ChecksTab` removeAll per HandoverState emission; `IssueListPanel.applyFilters` unconditional component swap; `CurrentWorkSection` full rebuild + `runReadAction` on EDT in mouse handler; `JiraSearchContributorFactory` fresh JBLabel per cell; monospace `Font(...)` allocations in renderers; `StageListPanel` O(n) snapshot per mouse-move; `PrDetailPanel.FileCellRenderer` removeAll per render; `CopyrightCellRenderer` per-render allocation. | see audit details (feature-modules pass) |
| P2-21 | Disposables parented directly to Project/Application (hot-reload hygiene): `BranchChangedEventEmitter.kt:30`, `AutoDetectFileListener.kt:39`, `AgentTabProvider.kt:69,75`. | — |
| P2-22 | `PsiContextEnricher`: 5 sequential read actions, file resolved twice, no caching (also correctness: PSI element used across read actions). Single readAction snapshot. | `core/psi/PsiContextEnricher.kt:25-49` |
| P2-23 | Doorbell socket binds per open project regardless of delegation use (1 parked thread + fd each). | `DelegationDoorbellService.kt:166-178` |
| P2-24 | EventBus `emit()` logs at INFO per event. | `core/events/EventBus.kt:23` |

---

## Bugs noticed (functional — fix separately from perf)

### High-priority
| # | Bug | Where |
|---|---|---|
| B1 | **`AgentConfigLoader` app-wide singleton is disposed by the first project that closes** — `Disposer.register(projectScopedService, configLoader)` sets `disposed=true` permanently; project B loses personas + hot-reload, watcher refuses restart. | `AgentService.kt:399-405`, `AgentConfigLoader.kt:207-213,374,567-574` |
| B2 | Race/CME: `updateSessionPlanMode`/`updateSessionDelegationMetadata` call `updateGlobalIndex()` WITHOUT the session mutex while it iterates `apiHistory`/`uiMessages` that other coroutines mutate under the mutex. | `MessageStateHandler.kt:518-523,537-540,561-563` |
| B3 | `getClineMessages()`/`getApiConversationHistory()` snapshot `.toList()` outside the mutex → CME risk from streaming hot path vs controller threads. | `MessageStateHandler.kt:94-95` |
| B4 | Stale `AgentController`s keep collecting EventBus events into detached browsers after tab rebuild (perf P0-6's functional half). | `WorkflowToolWindowFactory.kt:293`, `AgentTabProvider.kt:69-75` |
| B5 | **Artifact "Download"/"Open" buttons are painted but unclickable** — live JButtons inside a JList renderer (rubber stamp), no mouse forwarding on `artifactsList`. | `bamboo/ui/StageDetailPanel.kt:648-682` |

### Medium
| # | Bug | Where |
|---|---|---|
| B6 | Spill-file name collision: `toolName-epochSecond` — two spills same second silently overwrite; earlier spillPath points at later content. | `ToolOutputSpiller.kt:43` |
| B7 | Mirror replay cap inverted — late-opened editor mirror replays OLDEST 5000 events, silently missing the newest conversation. | `AgentDashboardPanel.kt:99-103` |
| B8 | ChatView end-of-stream scroll uses `messages.length-1` but Virtuoso indexes `renderItems` (tool groups collapsed) → scrolls wrong row / no-ops exactly in tool-heavy sessions. Fix: `renderItems.length-1`. | `ChatView.tsx:43-53,63-84` |
| B9 | `showApproval` silently discards an in-flight thinking block (every other drain path flushes it into messages as REASONING). | `chatStore.ts:1826` |
| B10 | `appendToken`'s tool-drain branch bypasses `capMessages` → 1000-message cap can be exceeded. | `chatStore.ts:938-949` |
| B11 | Coverage search resets selection + blanks preview on every keystroke even when selection still matches. | `CoverageTablePanel.kt:272-276` |
| B12 | StreamBatcher 60 Hz timer can keep ticking with empty buffer if an error path skips flush/clear (SUSPECTED on error paths; main paths covered). | `StreamBatcher.kt:38-69` |
| B13 | `SmartPoller.setVisible(true)` fires `action()` concurrently with the poll loop (duplicate overlapping HTTP refresh); `currentBackoff` non-volatile mutated from two threads. | `SmartPoller.kt:107-114` |
| B14 | HandoverTemplateStore watcher exits for ALL dirs when one watched dir becomes invalid (`key.reset()==false → break`). | `HandoverTemplateStore.kt:457` |
| B15 | Global index can stay stale: throttled skip sets `globalIndexDirty=true` but only `saveBoth()` flushes; `saveApiHistoryInternal` never updates it. | `MessageStateHandler.kt:495-497` |

### Low / suspected
| # | Bug | Where |
|---|---|---|
| B16 | `PrListService.stopPolling()` dead code — PR polling permanent after first PR-tab open. | `PrListService.kt:69` |
| B17 | SUSPECTED: per-chunk `lastIndex` snapshot can target the wrong UI message if another coroutine appends between snapshot and update. | `AgentLoop.kt:1128-1131` |
| B18 | SUSPECTED: `AtomicFileWriter` no `force()` before ATOMIC_MOVE → power loss can leave zero-length target on some filesystems. | `AtomicFileWriter.kt:43-47` |
| B19 | SUSPECTED: `PsiContextEnricher` uses PsiClass across separate read actions → PsiInvalidElementAccessException risk while typing. | `PsiContextEnricher.kt:32-42` |
| B20 | SUSPECTED: `PrListPanel` static `cachedFontMetrics` stale after LAF/scale change. | `PrListPanel.kt:469-479,515-525` |
| B21 | SUSPECTED: `CoverageLineMarkerProvider` registers markers on non-leaf elements; possible duplicate gutter icons per line. | `CoverageLineMarkerProvider.kt:20,91-99` |
| B22 | `lib/shiki.ts` doc contract broken by vite config (root cause of P0-2) — make them agree when fixing. | `shiki.ts:8-10`, `vite.config.ts:41` |
| B23 | Stale docs: pre-Phase-4-Prong-A inventories still cite 5 runBlocking-on-EDT sites in AgentController — all gone (verified). | docs / PHASE-RUN-STATUS.md |

---

## Verified-clean (don't re-flag)

- **All previously known `runBlocking`-on-EDT sites are FIXED**: AgentController (all 5), ConnectionsConfigurable, RepositoriesConfigurable, SetupDialog. No production runBlocking anywhere (pre-commit hook).
- Main prose streaming is delta-only + 16 ms StreamBatcher; message list IS virtualized (react-virtuoso); streaming text held outside `messages[]`; Shiki has LRU cache; collapsed tool outputs unmount; 1000-message UI cap; images go via scheme handler (no base64 over bridge); all ~75 JBCefJSQuery torn down in dispose.
- EventBus is canonical (replay=0, extraBuffer=64, DROP_OLDEST, tryEmit). HttpClientFactory: shared pool + 10 MB cache + documented isolates only. CredentialStore: static 1 h-TTL cache. No periodic process spawning anywhere. No ScheduledExecutorService/Alarm schedulers. Persona hot-reload is a real WatchService (not a 300 ms poll). Token estimate is O(#messages) length-sum, not re-tokenization. SonarIssueAnnotator is a textbook 3-phase ExternalAnnotator. Core startup activities individually cheap (except P2-7/P2-8). QueueService poll loop self-terminates. Most ad-hoc UI scopes properly cancelled.

---

## Suggested fix order (for the upcoming plan)

1. **Quick wins, huge leverage (hours):** P0-4 (one function — throttles every poller), P1-11, P1-6, P1-7, P1-9, P1-10, P2-1 (timer stop), B1.
2. **Streaming/persistence hot path (the "agent feels heavy" core):** P0-1 → P1-1 → P1-4 (one persistence-cadence redesign covers all three), P1-2, P1-3, P1-5.
3. **Webview:** P0-2 (build config only — no code), P0-3 + P1-13 + P1-14 (one render-path PR), P1-15, P1-16, P2-16/P2-17.
4. **Lifecycle/memory:** P0-6 + B4 (lazy tabs + disposal), P0-7, P1-17, P0-5, P1-8.
5. **Swing renderers sweep:** P1-18..P1-21 + P2-20 (mechanical, one PR per module).
6. **Bug-fix wave:** B2/B3/B5/B7/B8/B9/B11/B13/B14 + remaining.
