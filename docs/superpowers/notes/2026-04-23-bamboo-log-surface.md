# Bamboo log surface audit — 2026-04-23

## Finding: Path A (ConsoleView)

Bamboo build-log output is rendered in `StageDetailPanel` (`bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/StageDetailPanel.kt`). The panel constructs an IntelliJ `ConsoleView` (via `TextConsoleBuilderFactory`) inside `createConsoleView()` at line 212. Log lines are fed through `showLog()` → `printLogLines()` using `console.print(text, contentType)` — no Swing text component or JCEF browser is involved.

## Integration

Path A was chosen because `ConsoleView` supports `Filter` / `HyperlinkInfo` natively. A new `FilePathHyperlinkFilter` (same package) implements `Filter`, matches file-path substrings with a regex, delegates validation to `PathLinkResolver` (`:core`), and returns `Filter.ResultItem` entries backed by `OpenFileHyperlinkInfo`. The filter is installed via `console.addMessageFilter(FilePathHyperlinkFilter(project))` immediately after the existing `RegexpFilter` in `createConsoleView()`. This keeps all Windows-hardening and project-root-scoping logic in one place.
