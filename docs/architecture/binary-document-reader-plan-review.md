# Binary Document Reader Plan — Adversarial Review

**Reviewer:** Independent LLM, 2026-04-30
**Plan reviewed:** `docs/architecture/binary-document-reader-implementation-plan.md` (commit-time content)
**Companion research:** `docs/architecture/binary-document-reader-research.md`
**Verdict:** **fix-then-ship**. Two factual claims in the plan are wrong (Tabula on PDFBox 3.x; Tika standard package having "no native code, no OCR"), and the splice algorithm has a real failure mode the plan dismisses too quickly. None of these block v1, but they all need plan edits before implementation begins.

---

## TL;DR

Direction is correct, library choice is mostly right, but the plan's confidence is misplaced on three load-bearing claims. Tabula 1.0.5 on Maven Central is on **PDFBox 2.0.24**, not PDFBox 3.x — that produces a hard transitive-dependency conflict with Tika 3.2.3 (which is on PDFBox 3.0.5). Tika's `tika-parsers-standard-package` *includes* `tika-parser-ocr-module` (Tesseract bridge) — the plan's claim that the standard package has "no native code, no OCR" is false at the artifact level, only true if you actively exclude or never invoke it. The page-bucket splice algorithm has a documented failure mode (multi-page tables; alternating prose/table on one page) that the plan acknowledges in one line and then ignores. Fix these three items and the plan is shippable; ignore them and v1 will fail accuracy tests on the first real fixture.

---

## Q1: Tabula-java currency

### Verified facts

- Latest published release: **v1.0.5, 2021-08-17** ([release tag](https://github.com/tabulapdf/tabula-java/releases/tag/v1.0.5)). The plan picks this version — it is the only currently-published artifact. There is no 1.0.6.
- `master` HEAD is active: most recent commit **2025-03-19** (`2cdf3b4` "Adjust test ... PDFBox supports the /ActualText feature") and **88154e2** "Update PDFBox" same day. `master` is currently `1.0.6-SNAPSHOT`.
- **CRITICAL:** the published `technology.tabula:tabula:1.0.5` artifact pins **`org.apache.pdfbox:pdfbox:2.0.24`** in its POM (verified by fetching `https://repo1.maven.org/maven2/technology/tabula/tabula/1.0.5/tabula-1.0.5.pom`). `master` pom.xml shows `pdfbox:3.0.4`, but **that is unreleased**.
- Tika 3.2.3's parent POM declares `<pdfbox.version>3.0.5</pdfbox.version>` and `<poi.version>5.4.1</poi.version>` (verified by fetching `tika-parent-3.2.3.pom`).
- Open issue [#589 (2026-03-01)](https://github.com/tabulapdf/tabula-java/issues/589) explicitly asks for a new release to ship the POM bumps — **no maintainer has responded**. Last open issues are users complaining about lattice extraction returning empty results (#586, 2025-04-08). Project is "alive but barely." 181 open issues, 2024 stars, 449 forks.
- No credible newer pure-JVM table-extraction successor exists. "ExtractPDF4J 2.0" surfaced in one [DEV blog post](https://dev.to/mehulimukherjee/the-java-pdf-table-extraction-library-youve-been-waiting-for-4khh) but is unverified third-party software with no release history; not safe. PDFBox itself has no first-class `TableExtractor` API in 3.x. Tabula remains the only real choice.
- Independent benchmark ([Mark Kramer's Medium piece](https://medium.com/@kramermark/i-tested-12-best-in-class-pdf-table-extraction-tools-and-the-results-were-appalling-f8a9991d972e)) cites Tabula at **67.9% accuracy** against TableFormer's 93.6% and pdfplumber as best-in-class for clinical-trial schedules. Camelot scored 73%. The plan's framing of Tabula as "canonical JVM choice" is correct — it's just that the JVM ceiling is meaningfully below Python's.

### Recommendation

Keep `technology.tabula:tabula:1.0.5` — it's the only published JVM choice that can handle ruled spec tables. **But** the plan's risk #5 ("1.0.5 or later which is on PDFBox 3.x — verify in Maven dependency tree") is **factually wrong**. The published artifact pulls **PDFBox 2.0.24** transitively. Mixing it with Tika 3.x's PDFBox 3.0.5 is the actual risk.

Two viable fixes, in priority order:

1. **Exclude PDFBox 2.x transitively from Tabula and force-resolve PDFBox 3.0.5:**
   ```kotlin
   implementation("technology.tabula:tabula:1.0.5") {
       exclude(group = "org.apache.pdfbox")
   }
   constraints {
       implementation("org.apache.pdfbox:pdfbox") { version { strictly("3.0.5") } }
   }
   ```
   This works only because Tabula's source has been updated for PDFBox 3.x on `master`; whether the **1.0.5 bytecode** survives is unverified. Test before committing.
2. **Build Tabula from `master` HEAD as a vendored library** (commit `2cdf3b4`) and pin its hash. Adds CI complexity but eliminates the version mismatch.

If neither holds up, fall back to Tika XHTML's own `<table>` markup (which works for *some* PDFs Tika's PDFParser tags as tables) and accept the lower table fidelity for v1, deferring Tabula to v2.

---

## Q2: Splice algorithm soundness

The plan says: "after each `PageMarker(N)`, insert all tables Tabula found on page N." It calls this "acceptable for v1; v2 can use bbox coordinates." That is too breezy.

### Concrete failure modes

1. **Multi-page tables.** A 2-page bug-tracker table renders as Tabula `Table(page=N, headers=[...], rows=...)` and `Table(page=N+1, headers=[??], rows=...)`. Tabula does NOT join them. Result: two Markdown tables, second one with ambiguous or missing headers. [pdfplumber's docs](https://github.com/jsvine/pdfplumber/discussions/768) explicitly call this out as the hardest table-extraction problem; tabula-java offers no merge logic. This will hit on the very first realistic spec PDF that has a long table.
2. **Alternating prose/table on one page.** A page with `prose-A → Table-1 → prose-B → Table-2 → prose-C` becomes, under the plan's algorithm, `prose-A prose-B prose-C Table-1 Table-2`. This **destroys the prose-to-table reference relationship** ("see Table 1 above" now refers to nothing in context). Memory note `feedback_editor_not_for_repo_branch` already complains about lossy context derivation; this is the same class of bug.
3. **Page with 3 tables.** All three appear at end-of-page. If table 1 is sub-section 3.1's data and table 3 is sub-section 3.3's data, the LLM's attempt to cite "§3.2's table" silently picks the wrong one.
4. **Tabula false-positives.** Tabula's stream mode classifies 3+ aligned text blocks as a "table." Multi-column prose can produce a phantom `Table` block, which then gets spliced in as an end-of-page block of garbage. Risk #3 in the plan acknowledges this generically; it does not connect it to the splice failure surface.
5. **Encrypted/restricted PDF prose-only success.** If Tabula throws on an encrypted PDF but Tika succeeds (different code paths inside PDFBox), the prose extracts cleanly while `tablesByPage` is empty — silent table data loss with no warning.

### Recommendation: hybrid, not "v1 keeps page-bucket"

Replace the splice with a **bounding-box-aware merge** even in v1. Both Tabula and Tika+PDFBox already give you bbox coordinates via the underlying PDFBox `PDPage.getMediaBox()` and Tabula's `Table.getTop()`/`getLeft()`. The cost is one comparator and one sort — maybe 30 lines. The payoff is correct interleaving for case 2 above, which is *the most common case in spec docs.*

Concrete algorithm:

1. Run Tika XHTML in PDFBox-coordinate-aware mode. Each `Paragraph` gets a `(page, yTop, yBottom)` annotation via a custom `PDFTextStripperByArea`-derived handler.
2. Run Tabula. Each `Table` gets `(page, top, bottom)`.
3. Sort the union by `(page, top)`.
4. Suppress phantom Tabula tables: if a `Table` block's bbox overlaps an already-emitted `Paragraph` by >70%, drop it.
5. Continuation detection: if `Table(page=N+1, top<50)` immediately follows `Table(page=N, bottom>pageHeight-50)` AND headers match (or N+1 has no header row), merge rows.

If even that is too much for v1, then **at minimum** the plan must (a) disable Tabula stream-mode by default (lattice only — the plan gestures at this but the code in step 5 falls back to stream automatically), and (b) emit a warning in the Markdown output `<!-- tables on this page may not be in flow order -->` so the LLM can compensate.

---

## Q3: POI on the IntelliJ classpath

### Verified facts

- IntelliJ Platform Gradle Plugin 2.x **does not document Shadow integration**. [Issue #808](https://github.com/JetBrains/intellij-platform-gradle-plugin/issues/808) "Merge all plugin jars into single one for new plugin model" is closed (1.0 milestone), but it concerns the v2 plugin's own JAR layout, not arbitrary fat-jarring of dependencies. There is no first-party guidance on running `shadowJar` and feeding the result to `prepareSandbox`/`buildPlugin`.
- The official [Plugin Class Loaders page](https://plugins.jetbrains.com/docs/intellij/plugin-class-loaders.html) does **not** mention Shadow or relocation as a recommended technique. JetBrains' official guidance is just "each plugin gets its own classloader, that should be enough."
- Real-world precedent for bundling POI in a JetBrains plugin: [SpecificLanguages' MPS+POI write-up](https://specificlanguages.com/posts/2022-03/15-apache-poi-classloader-hell/) explicitly says they did **NOT** use Shadow. They built a custom `UrlClassLoader` via IDEA's `UrlClassLoader.build()` that delegates directly to the system classloader, bypassing the IDE classloader hierarchy. The trade-off: data exchange between the custom-loaded POI code and IDE code is limited to "basic Java types (Strings, Files, Collections)."
- The plugin's own `build.gradle.kts` has a comment: *"Bundling a second SLF4J causes LinkageError due to plugin classloader isolation"* — the codebase is already aware of this exact trap.
- POI 5.4.1 (what Tika 3.2.3 pulls in) depends on `xmlbeans:5.x`. JetBrains' Database plugin and Big Data plugin are alleged to bundle their own POI. I could not verify the exact versions they bundle through public sources, but the [MPS XmlBeans 2 vs 5 collision](https://specificlanguages.com/posts/2022-03/15-apache-poi-classloader-hell/) is documented and analogous.
- `verifyPlugin` (Plugin Verifier) detects **API-level** binary incompatibilities (missing methods, missing classes) at build time. It does **NOT** simulate runtime classloader hierarchies with conflicting transitive versions. Linkage errors from XmlBeans/POI version drift only surface when a user actually has the conflicting JetBrains plugin installed and the codepath fires. This means risk #1's mitigation ("run verifyPlugin against multiple IDEA editions early") is **insufficient** — verifier won't catch it.

### Recommendation

The plan's "use Shadow as fallback" is wishful thinking. Specifically:

1. Shadow + IntelliJ Platform Plugin v2 has **no officially supported integration**. You can run `shadowJar` to produce a relocated artifact, then feed it to `intellijPlatform { ... }` as a local jar dependency, but you'll be the first one documenting the pattern. Budget time for this.
2. The MPS approach (custom `UrlClassLoader.build()` delegating to system classloader) is the **only proven precedent** for bundling POI in a JetBrains plugin. It's heavier than Shadow but it actually works. Plan should explicitly call this out as the fallback if Shadow trips.
3. `<depends optional="true">` for the JetBrains Database plugin would let you detect its presence at install time and warn the user — but only if you can determine the conflicting POI version dynamically. Not a real mitigation, more an early-warning.

**Concrete edit:** Risk #1 in the plan must be upgraded from "Medium/High" to "**High likelihood, High impact**" with the actual mitigation being:

> Run integration test against IDEA Ultimate **with Database Tools and Big Data Tools plugins enabled** (not just verifyPlugin). If LinkageError observed, fall back to UrlClassLoader-based isolation (per [SpecificLanguages MPS pattern](https://specificlanguages.com/posts/2022-03/15-apache-poi-classloader-hell/)). Shadow is a third-tier option only after the UrlClassLoader approach is shown unworkable.

Risk to ship: medium-high. This is the single most likely thing to make v1 break in user reports.

---

## Q4: Risk register additions

The plan's 8 risks miss several real ones:

1. **(NEW, HIGH) Tabula-Tika PDFBox version conflict.** Documented above (Q1). Mitigation: explicit `exclude` + dependency `constraint`.
2. **(NEW, HIGH) Tika ServiceLoader fails inside PluginClassLoader.** Tika's `MimeTypesFactory` and `ServiceLoader` use different classloaders; in plugin contexts this is documented as [TIKA-1145](https://issues.apache.org/jira/browse/TIKA-1145), [pf4j #336](https://github.com/pf4j/pf4j/issues/336), and the [IDEA forum thread on Tika class errors](https://intellij-support.jetbrains.com/hc/en-us/community/posts/360000369580). Symptom: parsers don't get discovered, `AutoDetectParser` returns `EmptyParser`, every extraction silently produces empty text. Mitigation: explicitly construct `TikaConfig` with `Thread.currentThread().contextClassLoader = TikaConfig::class.java.classLoader` around the call site, and explicitly register parsers via `TikaConfig` rather than relying on `META-INF/services` discovery. Test by asserting `parser.getParsers()` is non-empty in the extractor's init block.
3. **(NEW, MEDIUM) Tika standard package DOES include OCR module.** The plan claims the standard package has "no native code, no OCR." Verified false — `tika-parsers-standard-package-3.2.3.pom` explicitly depends on `tika-parser-ocr-module`. The `tika-config.xml` in the plan **does** exclude `TesseractOCRParser` via `<parser-exclude>`, which is correct, but the JAR is still on the classpath (~5 MB), and a misconfigured `TikaConfig` instantiation will silently re-enable it. Mitigation: validate at startup that the parser registry contains zero OCR parsers; assert in test.
4. **(NEW, MEDIUM) log4j-api/slf4j shim collision with IntelliJ Platform.** POI 5.4.1 logs via log4j-api (Tika's pom shows `log4j2.version=2.25.1`). IntelliJ Platform [removed log4j in 2022](https://blog.jetbrains.com/platform/2022/02/removing-log4j-from-the-intellij-platform/) and ships an `slf4j-over-log4j` stub. Bundling log4j-api 2.25.1 into the plugin can produce LinkageError at the SLF4J binding boundary ([SLF4J providers clashing](https://github.com/JetBrains/intellij-platform-gradle-plugin/issues/1740)). Mitigation: exclude `log4j-api`/`log4j-core` transitively and route POI/Tika logs through the platform-provided slf4j. Build-file comment `Bundling a second SLF4J causes LinkageError due to plugin classloader isolation` confirms the codebase already knows this trap exists.
5. **(NEW, MEDIUM) XmlBeans CVE history + size.** `xmlbeans:5.x` is ~3 MB (correct in plan). [CVE-2021-23926](https://nvd.nist.gov/vuln/detail/CVE-2021-23926) (XXE in <2.6.0) is fixed; XmlBeans 5.x has no current public CVE, but it has had four CVEs historically. The hardened tika-config disables external entity resolution, but POI's `XSSFWorkbook` constructor invokes XmlBeans directly, NOT through Tika's pipeline. POI's own XXE protection must be configured at construction (`OPCPackage` open with `IOUtils.setByteArrayMaxOverride`). Mitigation: add explicit POI-side hardening, not rely solely on `tika-config.xml`.
6. **(NEW, MEDIUM) PDFBox JPEG2000 native dependency.** PDFBox 3.x optionally uses `jai-imageio-jpeg2000` for JP2 decoding. Tika's pom **explicitly excludes** this from its production package "for legal reasons (incompatible license), jai-imageio-jpeg2000 is to be used only in the tests." So Tika won't ship it — but PDFs containing JPEG2000 images will fail to render images and may throw `IOException` at extract time, which the plan does catch in `runCatching`. Net effect: scanned PDFs encoded with JPEG2000 (rare but real for old Bamboo build artifacts) will fail rather than partially extract. Document as a known v1 limitation; do not include the native lib (license-incompatible). PDFBox JBIG2 decoding is pure Java per [pdfbox-jbig2](https://github.com/apache/pdfbox-jbig2), so JBIG2 is fine.
7. **(NEW, HIGH) POI/PDFBox/Tabula are NOT thread-safe per shared instance.** Per [POI FAQ](https://poi.apache.org/help/faq.html): "Accessing the same document in multiple threads will not work." Tabula's `ObjectExtractor` holds a `final PDDocument` reference and PDFBox documents are not thread-safe. The plan describes the agent firing concurrent tool calls (8 parallel sub-agents per memory note `reference_cline_subagent_task_delegation`). Two simultaneous `read_document` calls on the **same path** will race and corrupt internal state. Mitigation: per-call instantiation of `XSSFWorkbook`, `Loader.loadPDF()`, and `ObjectExtractor`; never cache. Add a per-path coroutine `Mutex` if the same file is read concurrently. Also: as of POI 5.2.4, call `ThreadLocalUtils.clearAllThreadLocals()` after each extraction to prevent leaks across the agent's worker threads.
8. **(NEW, LOW) Settings hot-reload of `documentMaxChars`.** The plan adds `documentMaxChars` to `PluginSettings` but doesn't say whether the running `MarkdownAssembler` reads the setting at construction or per-call. If per-construction, mid-session changes are silently ignored until restart. The plugin's existing `HttpClientFactory.timeoutsFromSettings()` reads per-call (per `core/CLAUDE.md` patterns); mirror that — the assembler should pull `maxChars` from settings on every invocation, not via DI cache.
9. **(NEW, MEDIUM) Memory pressure on default heap.** IDEA's default heap is 750MB→2GB depending on edition. A 100-page PDF with embedded fonts can produce a `PDDocument` of 100-300MB resident. Tabula then duplicates page text. Tika's `XHTMLContentHandler` retains the full DOM. Worst-case concurrent extraction of two 100-page PDFs can hit 1GB+. Plan's `setMaxMainMemoryBytes(50_000_000)` only governs PDFBox's *scratch buffer*, not retained document state. Mitigation: a global semaphore (`Semaphore(2)`) around `read_document` calls so at most 2 documents extract concurrently across the entire agent.
10. **(NEW, LOW) `OutOfMemoryError` is not catchable in coroutines reliably.** Plan says "OOM caught and converted to ToolResult.Failure." `OutOfMemoryError` extends `Error`, not `Exception`. `runCatching { }` catches it (it catches `Throwable`), but the JVM may already be in unrecoverable state — the next coroutine continuation may fail. Better: `try { ... } catch (e: OutOfMemoryError) { System.gc(); ToolResult.Failure(...) }`, but accept that the IDE might be unstable after.
11. **(NEW, LOW) `Files API` upload (Anthropic) and Sourcegraph PDF probe both miss from v2 roadmap.** The roadmap mentions probing Sourcegraph for `document` blocks but doesn't note that Anthropic's [Files API](https://docs.claude.com/en/docs/build-with-claude/files) is the right shape for prompt caching of repeated PDFs across turns — relevant once the agent makes `read_document` discover the same PDF repeatedly. Out of scope for v1, but the v2 doc should mention it.

---

## Other findings

- **Dependency footprint estimate is optimistic.** Plan says 35–55 MB, then "Tabula adds ~1 MB on top of Tika because it shares PDFBox." If you fix the PDFBox version conflict by excluding from Tabula (Q1), Tabula adds Tabula-proper (~1 MB) PLUS its own jts-core (~2 MB), commons-csv (~250 KB), commons-cli (~150 KB), bouncycastle (~7 MB depending on transitive resolution). Expect closer to **45–70 MB** added.
- **`xmpTPg:NPages` in research doc is a real metadata key**, but in Tika 3.x the canonical accessor is `metadata.get(PDF.PDF_PAGE_COUNT)` or `metadata.get(Office.PAGE_COUNT)`. Not load-bearing; just sloppy.
- **Step 4 XLSX code is buggy** (`sheet.firstOrNull()?.map { cellAsString(it) } ?: continue` — `continue` inside a `for...in wb` loop produces `for` continue but `continue` is not a function; this is Kotlin scope-aware and compiles, but `wb` is a `Workbook`, and `for sheet in wb` iterates `Sheet` — `sheet.firstOrNull()` calls `Sheet.firstOrNull()` which returns the first `Row`, fine. But then `sheet.drop(1)` returns `List<Row>`, materializing the entire sheet in memory — **for the 100K-row case the plan claims to support, this allocates and copies 100K rows just to skip 1**. Use `iterator()` plus `next()` skip, or `XSSFWorkbook` is wrong choice — use `SXSSFWorkbook` for streaming.
- **Step 5 Tabula loop ignores Tabula's own per-page extraction shortcut.** `extractor.extract(pageNumber)` is more efficient than `pages.next()` iteration when you only need a few pages (e.g., truncated extraction). Not critical for v1 but worth noting.
- **`tika-config.xml` `loadErrorHandler="WARN"` is wrong for production**: it silently logs and continues when a parser fails to load, hiding ServiceLoader bugs (Q4 #2). Use `THROW` in dev, `IGNORE` in prod, or build a custom handler.
- **No mention of `MalformedByteSequenceException` in the error catalog.** Tika's `XmlEncodingDetector` regularly throws this on legitimate-but-weird Office docs. Add to error map.
- **Acceptance criterion bullet 7** — "Tool description in deferred-tier registry; tool_search finds it via 'pdf' / 'document' / 'spec'" — but the plan's `description` block (step 7) doesn't include the keyword "spec" anywhere. Either add it to the description or drop it from the AC.
- **The "explicit no: iText (AGPL)"** — correct, but iText 7+ is AGPL/commercial dual-license. Worth noting in research doc that the dual-license exists; it doesn't change the conclusion but makes the rejection rigorous.
- **The plan never specifies which `PluginClassLoader` API surface the agent runs through.** If `:document` is a separate Gradle module, it gets the same `PluginClassLoader` as `:agent` (one classloader per plugin), so module separation does not isolate POI. The "new module" boundary is for code organization only, not classloader isolation. The plan's framing in §5 implies otherwise.
- **Effort estimate is light.** "0.5 day for agent tool wrapper" assumes no rework. The deferred-tier wiring and `OutputConfig.spillIfOver(30_000)` interaction with the `MarkdownAssembler`'s `truncated` bool is non-trivial. Add 0.5 day.

---

## What to actually change before implementation starts

In priority order:

1. **Section 4 / Risk #5: correct the PDFBox version claim.** State explicitly that `technology.tabula:tabula:1.0.5` ships with `pdfbox:2.0.24` transitively. Document the conflict with Tika's PDFBox 3.0.5. Provide the `exclude` + `constraint` Gradle snippet (Q1 above) AND a fallback of vendoring Tabula `master` HEAD. Add a runtime sanity test that asserts a `PDDocument` opened from Tabula's classes resolves to PDFBox 3.0.5 bytecode.
2. **Section 7 (tika-config) + Risk register: acknowledge OCR module is in standard package.** Replace "no native code, no OCR" with "OCR module is on the classpath but Tesseract is excluded by parser-exclude in tika-config.xml; verified by an init-time assertion that no OCR parser is registered."
3. **Section 5 step 5 / new section: replace page-bucket splice with bbox-aware merge.** Add ~30 lines of bbox sorting and overlap detection. Keep the page-bucket as the fallback only if bbox is unavailable. Update the Markdown assembler to emit `<!-- table-flow:approximate -->` when fallback used.
4. **Section 5 step 5: disable Tabula stream mode by default for v1.** Lattice-only; surface stream behind a `useStreamFallback: Boolean` setting that defaults to false. Phantom-table risk (#3 in plan, #2/#4 in Q2 above) is meaningfully reduced.
5. **Risk register: replace risks #1, #5 wording and add risks #1–#11 from Q4 above.** Specifically rewrite #1 mitigation to be "test against IDEA Ultimate with Database + Big Data plugins enabled, fall back to UrlClassLoader-based isolation per SpecificLanguages MPS pattern."
6. **Section 6 (wiring): add classloader-context wrap around `parser.parse()`.** Set `Thread.currentThread().setContextClassLoader(TikaConfig::class.java.classLoader)` for the duration of the call, restore in `finally`. This is the documented workaround for TIKA-1145 in plugin contexts.
7. **Step 4 XLSX: use `Sheet.iterator()` or `SXSSFWorkbook`/streaming reader.** Don't materialize 100K rows just to read them.
8. **Section 3 / step 7: add a global `Semaphore(2)` around `read_document`.** Memory pressure on 750MB heap with concurrent 100-page PDFs is the realistic OOM trigger.
9. **Section 8 settings: state explicitly that `documentMaxChars` is read per-call from `PluginSettings`** — not constructor-cached. Match `HttpClientFactory.timeoutsFromSettings()` pattern.
10. **Acceptance criterion: add "init-time assertion that `tikaConfig.parser.parsers` contains expected MIME coverage and zero OCR parsers."** Without this, the ServiceLoader bug surfaces only in user-reported empty extracts.
11. **Effort estimate: bump from 7 to 9 days focused.** Buffer for classloader work specifically. The plan's 0.5-day buffer is unrealistic given the precedent literature.

The plan is ~80% right. The 20% that's wrong is concentrated in three load-bearing places (Q1, Q2, Q4 #1+#2+#7), and all three would visibly fail on day 1 of integration testing.
