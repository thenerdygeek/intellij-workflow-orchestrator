---
name: Plugin ZIP trim — shipped v0.83.35-alpha
description: Trimmed plugin ZIP from 87 MB to 61 MB via Tika parser exclusion, BouncyCastle dedup, sqlite-jdbc native trim, kotlin-stdlib leak fix, dead webview/lib delete, and Shiki tree-shake. Shipped 2026-05-01 on refactor/cleanup-perf-caching.
type: project
originSessionId: 4080f059-82dc-4318-a36e-a9456837fb3a
---
**Outcome (shipped 2026-05-01 as v0.83.35-alpha — commits `7ed22c30`, `91794423`, `478dabee`):** plugin ZIP went 87 MB → 61 MB (−26 MB, −30%). Below 65 MB target; just over the 60 MB stretch goal.

**Why:** binary document reader landed (commit `30927db7`), pulling all of Tika `tika-parsers-standard-package` into the ZIP and growing it 33 → 87 MB. IDE launch lag was visible.

**What actually shipped (with lessons):**

1. **Tika trim** — excluded mail/news/cad/code/audiovideo/apple/webarchive/image/ocr/pkg modules + their transitives (junrar, netpreserve, rometools, james, drewnoakes, xmpcore, jbig2, jai-imageio, juniversalchardet, uwyn, picocli). **KEPT against the original plan: `jackcess` and `tika-parser-xml-module`** — both have eager class-verification linkage from kept Tika modules (JackcessParser inner class, miscoffice's RTF/ODT parsers). Excluding them triggers `NoClassDefFoundError` at TikaConfig construction.
2. **BouncyCastle jdk15on dedup** via top-level `configurations.all` exclude in `document/build.gradle.kts`. Saves ~7 MB.
3. **sqlite-jdbc native trim** in `automation/build.gradle.kts` via custom `trimmedSqliteJar` Jar task. The original plan said "drop sqlite-jdbc"; **THAT WAS WRONG** — sqlite is load-bearing for `:automation`'s `TagHistoryService` and `QueueService` (internal storage, not user-facing). Same for postgresql (`:agent` `db_query`) and jts-core (Tabula PDF table extraction). Trim natives to 6 platforms (Mac/Linux/Windows × x86_64/aarch64) instead. 13.5 MB → 3.8 MB.
4. **kotlin-stdlib leak** — `kotlin.stdlib.default.dependency = false` in gradle.properties only stops the Kotlin Gradle plugin's auto-add; transitive pulls (OkHttp 4.12.0 → kotlin-stdlib-jdk8) still bundle 1.7 MB. Plugin-distribution-configuration-scoped exclude in root `build.gradle.kts` drops it without breaking `kotlinCompilerClasspath`.
5. **Dead `webview/lib/` tree deleted** — entire 5.7 MB pre-Vite asset directory was unreachable from runtime (`CefResourceSchemeHandler:97` only serves `webview/dist/$path`). Tests pinned the dead surface.
6. **Webview Shiki tree-shake** — switched from `shiki` (358-grammar bundle) to `shiki/core` with 33 explicit dynamic imports. Languages shipped cover Spring Boot, DevOps infra, SDK, test automation. PlantUML intentionally absent (no Shiki grammar). Asset count 358 → 109. Code blocks in stripped languages render as plain text.

**How to apply (next time you face dependency bloat):**
- **Always grep before excluding.** The original plan was authored without `grep -rn "import org.sqlite\|import org.postgresql"`. Three load-bearing direct deps were almost dropped.
- **Tika `loadErrorHandler="THROW"` + missing class = brittle.** Removing a parser module also requires removing its `<parser-exclude>` line from `tika-config.xml`. Otherwise TikaConfig fails to load. (Trap caught: the OCR exclude pointed at `TesseractOCRParser` after we removed `tika-parser-ocr-module`.)
- **JVM class verification eager-resolves implements/extends declarations.** That's why `jackcess` and `tika-parser-xml-module` could not be cut despite having no direct usage in our code — Tika's modules carry inner classes that implement those types.
- **Multi-arch native JARs are trimmable via Gradle Jar tasks.** Pattern is reusable for any future native-bundle dep (jansi, BoringSSL, etc.): detached configuration resolves the upstream artifact, custom Jar task copies-and-excludes paths, `implementation(files(taskRef))` wires the trimmed result into the plugin.

**Remaining gap to <60 MB stretch goal: ~1 MB.** Easy targets considered and rejected:
- Lucide tree-shake (would save ~300 KB) — `agent/webview/src/sandbox-main.ts:832` spreads all Lucide icons into the artifact-renderer scope; load-bearing for documented `render_artifact` "all icons by name" capability. Same trap pattern as the SQLite plan.
- Dropping Mermaid (2.9 MB single chunk) would lose ` ```mermaid ` diagram blocks.
- BouncyCastle jdk18on (8.9 MB) — required for encrypted-PDF detection in PDFBox 3.0.5.

**Post-ship incident & safety net (v0.83.36-alpha, commit `4cfd56ec`).** The trimmed JAR shipped in v0.83.35-alpha included merged `slf4j-api` classes — `LinkageError` for `org.slf4j.LoggerFactory` / `StaticLoggerBinder` triggered on first `Class.forName("org.sqlite.JDBC")` at IDE open. Root cause: `sqliteJdbcUpstream(libs.sqlite.jdbc)` was a default (transitive) configuration, so `zipTree(it)` over the resolution merged `slf4j-api.jar` into the fat JAR. Fix: `isTransitive = false` on the upstream resolvable configuration. Regression pinned by `automation/src/test/kotlin/.../TrimmedSqliteJarInvariantsTest.kt` (commit `dce347c9`) — three JUnit assertions: zero `org/slf4j/*` entries; `org/sqlite/JDBC.class` present; natives match exactly the 6-path shipped-platform set.

**Generalized lesson — custom artifact rewrite (Layer 3 trim):**
- Always set `isTransitive = false` on the resolvable configuration that feeds `zipTree(...)` — otherwise transitive deps get merged into the fat JAR and collide with platform classes.
- Always pair the rewrite task with a JUnit-test invariant on the produced artifact's contents, locating the JAR via `java.class.path`. The test runs as part of `:check` and surfaces regressions before release. Don't try to do this via a Gradle task `doLast` — Gradle 9's configuration cache rejects most natural closures with "cannot serialize Gradle script object references", and the workarounds are uglier than just writing a test.
- Layer-3 trims (rewriting upstream artifacts) are the riskiest of the four trim layers. Layers 0–2 (use fewer deps; transitive `exclude`s; consumer-side tree-shaking like Vite or R8) are normal hygiene; Layer 3 makes you the maintainer of a divergent artifact whose validity must be re-checked on every upstream bump.

**Future improvement — plugin ZIP size budget.** A build-time assertion that the produced ZIP stays under (e.g.) 75 MB would catch dependency creep before release. First attempt with `tasks.named("buildPlugin") { doLast { ... } }` ran into Gradle 9 config-cache constraints. Three viable alternatives noted as TODO in `build.gradle.kts`: separate task with `inputs.files(...)` + primitive-only closure; root-project JUnit test; out-of-Gradle CI script. Tracked but not blocking.
