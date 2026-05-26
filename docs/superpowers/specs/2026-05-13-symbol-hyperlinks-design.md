# Symbol Hyperlinks in Agent Chat UI

**Date:** 2026-05-13  
**Status:** Approved  
**Branch:** fix/automation-handover-quality-tabs

---

## Problem

The agent chat frequently mentions class names, methods, and fields in plain prose. The existing
`file-link-scanner.ts` auto-detects file paths via regex, but symbol references (`UserService`,
`authenticate()`, `TOKEN`) are impossible to regex-detect reliably — they have no structural
delimiter that separates them from ordinary words.

---

## Solution

Introduce a declarative `symbol:` URL scheme the LLM can emit inside standard markdown link
syntax. The IDE's PSI layer resolves each reference at render time; valid ones become clickable
navigation links, invalid or ambiguous ones silently fall back to plain text.

---

## Link Format

```
[DisplayText](symbol:com.example.ClassName)               ← class / interface / object / enum / annotation
[DisplayText](symbol:com.example.ClassName#memberName)    ← method / field / property / enum constant
```

**Examples:**
```markdown
The [UserService](symbol:com.workflow.core.UserService) delegates auth to
[authenticate](symbol:com.workflow.core.UserService#authenticate).
The result is stored in [TOKEN](symbol:com.workflow.core.model.TokenType#TOKEN).
```

### Validation rule

PSI must find **exactly one** element for the fully-qualified name:

| PSI result count | Outcome |
|---|---|
| 1 (unique match) | Rendered as blue underline link; click navigates to definition |
| 0 (not found) | `href` stripped; text renders as plain prose |
| 2+ (ambiguous) | `href` stripped; text renders as plain prose |

The LLM is instructed to always use the fully qualified name so ambiguity is rare.

---

## Architecture

### Approach chosen: mirror file-link architecture (Approach 1)

Parallel to the existing `file-link-scanner.ts` / `PathLinkResolver.kt` pipeline.
No changes to the file-link scanner; zero regression risk on file links.

### Data flow

```
Message stream ends
  ↓
AgentMessage.tsx useEffect
  ↓
symbol-link-scanner.ts  scanAndSymbolLinkify(root)
  — collect all <a href="symbol:…"> anchors in rendered DOM
  — deduplicate hrefs
  ↓
kotlinBridge.resolveSymbols(hrefs[])     (new bridge, 2 s timeout)
  ↓
AgentCefPanel  resolveSymbolsQuery
  → AgentController  handleResolveSymbols(symbolsJson, callbackName)
  → SymbolLinkResolver.resolveAll(hrefs)    (suspend, readAction per entry)
  → returns ValidatedPath[]                 (same type as validatePaths)
  ↓
JS callback fires
  valid   → data-canonical + data-line written onto <a>; href kept as-is
  invalid → href attribute removed; anchor renders as unstyled text
  ↓
User clicks valid link
  AnchorNode.onClick: href.startsWith("symbol:") → read data-canonical + data-line
  → window._navigateToFile(canonicalPath + ":" + (line+1))
  → existing navigateToFileQuery → AgentController.navigateToFile
  → FileEditorManager.openEditor at line
```

The click side reuses the **entire existing** `_navigateToFile` → `navigateToFileQuery` →
`AgentController.navigateToFile` → `PathLinkResolver.resolveForOpen` →
`FileEditorManager` chain — no new click handler needed.

### Visual style

Uniform — same blue underline as file links (`text-[var(--link)] underline`).
Hover tooltip shows `canonicalPath:line` (same as file links). No additional styling.

---

## New Files

### `agent/webview/src/util/symbol-link-scanner.ts`

Post-render DOM scanner. Parallel to `file-link-scanner.ts`.

```typescript
const SYMBOL_SCHEME = 'symbol:';
const RPC_TIMEOUT_MS = 2000;

export async function scanAndSymbolLinkify(root: HTMLElement): Promise<void> {
  const anchors = collectSymbolAnchors(root);
  if (anchors.length === 0) return;

  const uniqHrefs = Array.from(new Set(anchors.map(a => a.getAttribute('href')!)));
  const resolved = await withTimeout(kotlinBridge.resolveSymbols(uniqHrefs), RPC_TIMEOUT_MS);
  if (resolved.length === 0) {
    // all invalid — strip hrefs
    for (const a of anchors) a.removeAttribute('href');
    return;
  }

  const byHref = new Map(resolved.map(v => [v.input, v]));
  for (const a of anchors) {
    const href = a.getAttribute('href')!;
    const v = byHref.get(href);
    if (v) {
      a.dataset.canonical = v.canonicalPath;
      a.dataset.line = String(v.line);
      a.dataset.column = String(v.column);
      a.title = `${v.canonicalPath}:${v.line + 1}`;
    } else {
      a.removeAttribute('href');
    }
  }
}

function collectSymbolAnchors(root: HTMLElement): HTMLAnchorElement[] {
  // After scanning, hrefs are either stripped or patched — no re-match guard needed
  return Array.from(root.querySelectorAll<HTMLAnchorElement>('a[href^="symbol:"]'));
}
```

### `core/src/main/kotlin/…/core/util/SymbolLinkResolver.kt`

PSI resolver in `:core`. Handles Java + Kotlin classes, methods, fields, properties.

```kotlin
class SymbolLinkResolver(private val project: Project) {

    suspend fun resolveAll(hrefs: List<String>): List<ValidatedPath> =
        hrefs.mapNotNull { resolve(it) }

    suspend fun resolve(href: String): ValidatedPath? {
        val fqn = href.removePrefix("symbol:")
        if (fqn.isBlank()) return null

        val (classFqn, memberName) = if ('#' in fqn)
            fqn.substringBefore('#') to fqn.substringAfter('#')
        else fqn to null

        val scope = GlobalSearchScope.allScope(project)

        val element: PsiElement = readAction {
            val cls = JavaPsiFacade.getInstance(project).findClass(classFqn, scope)
                ?: return@readAction null
            if (memberName == null) return@readAction cls
            cls.findMethodsByName(memberName, true).firstOrNull()
                ?: cls.findFieldByName(memberName, true)
        } ?: return null

        return readAction {
            val vFile = element.containingFile?.virtualFile ?: return@readAction null
            val doc = PsiDocumentManager.getInstance(project)
                .getDocument(element.containingFile) ?: return@readAction null
            val line = doc.getLineNumber(element.textOffset)
            val col  = element.textOffset - doc.getLineStartOffset(line)
            ValidatedPath(
                input        = href,
                canonicalPath = vFile.path,
                line         = line,
                column       = col,
            )
        }
    }
}
```

`ValidatedPath` is the existing type in `:core` — no new model type required.

---

## Modified Files

### `agent/webview/src/bridge/jcef-bridge.ts`

Add `resolveSymbols` alongside the existing `validatePaths`:

```typescript
async resolveSymbols(hrefs: string[]): Promise<ValidatedPath[]> {
  // same callback pattern as validatePaths
  const callbackName = `_resolveSymbolsCb_${Date.now()}_${Math.random().toString(36).slice(2)}`;
  return new Promise((resolve) => {
    (window as any)[callbackName] = (json: string) => {
      delete (window as any)[callbackName];
      try { resolve(JSON.parse(json)); } catch { resolve([]); }
    };
    const bridge = (window as any)._resolveSymbols;
    if (bridge) bridge(JSON.stringify(hrefs) + '|' + callbackName);
    else resolve([]);
  });
}
```

### `agent/webview/src/bridge/globals.d.ts`

```typescript
_resolveSymbols?: (payload: string) => void;
```

### `agent/webview/src/components/markdown/MarkdownRenderer.tsx` — `AnchorNode`

```tsx
function AnchorNode({ href, children, ...props }: any) {
  return (
    <a
      href={href}
      className="text-[var(--link)] underline decoration-[var(--link)]/30 hover:decoration-[var(--link)]"
      onClick={(e: React.MouseEvent) => {
        e.preventDefault();
        if (!href) return;
        // symbol: links — read validated data-canonical written by scanner
        if (href.startsWith('symbol:')) {
          const el = e.currentTarget as HTMLAnchorElement;
          const canonical = el.dataset.canonical;
          const line = el.dataset.line ?? '0';
          if (canonical) (window as any)._navigateToFile?.(`${canonical}:${parseInt(line, 10) + 1}`);
          return;
        }
        (window as any)._navigateToFile?.(href);
      }}
      {...props}
    >
      {children}
    </a>
  );
}
```

### `agent/src/main/kotlin/…/agent/ui/AgentCefPanel.kt`

Register `resolveSymbolsQuery` and inject `_resolveSymbols` — mirrors the existing
`validatePathsQuery` / `_validatePaths` pattern exactly.

```kotlin
// declaration (alongside validatePathsQuery)
private var resolveSymbolsQuery: JBCefJSQuery? = null
var onResolveSymbols: ((String, String) -> Unit)? = null

// registration (alongside validatePathsQuery registration)
resolveSymbolsQuery = registerQuery(b) { data ->
    val sepIdx = data.lastIndexOf('|')
    val hrefsJson   = if (sepIdx > 0) data.substring(0, sepIdx) else "[]"
    val callbackName = if (sepIdx > 0) data.substring(sepIdx + 1) else ""
    onResolveSymbols?.invoke(hrefsJson, callbackName)
    JBCefJSQuery.Response("ok")
}

// injection (alongside _validatePaths injection)
injectBridge("_resolveSymbols") {
    resolveSymbolsQuery?.let { q ->
        js("window._resolveSymbols = function(payload) { ${q.inject("payload")} }")
    }
}
```

### `agent/src/main/kotlin/…/agent/ui/AgentDashboardPanel.kt`

```kotlin
fun setResolveSymbolsCallback(cb: (String, String) -> Unit) {
    cefPanel?.onResolveSymbols = cb
}
```

### `agent/src/main/kotlin/…/agent/ui/AgentController.kt`

Wire in `initDashboard()` alongside `handleValidatePaths`:

```kotlin
dashboard.setResolveSymbolsCallback { hrefsJson, callbackName ->
    handleResolveSymbols(hrefsJson, callbackName)
}

private fun handleResolveSymbols(hrefsJson: String, callbackName: String) {
    if (callbackName.isBlank()) return
    controllerScope.launch(Dispatchers.IO) {
        val hrefs = try {
            lenientJson.decodeFromString<List<String>>(hrefsJson)
        } catch (e: Exception) {
            LOG.warn("handleResolveSymbols: bad payload", e); emptyList()
        }
        val resolver = SymbolLinkResolver(project)
        val results  = resolver.resolveAll(hrefs)
        val json     = lenientJson.encodeToString(results)
        dashboard.executeJs("if(window['$callbackName']) window['$callbackName']('${JsEscape.escapeForJsString(json)}')")
    }
}
```

### `agent/webview/src/components/chat/AgentMessage.tsx`

Call both scanners in the finalization `useEffect`:

```typescript
useEffect(() => {
  if (!isStreaming && msgRef.current) {
    scanAndLinkify(msgRef.current);          // existing file-link scanner
    scanAndSymbolLinkify(msgRef.current);    // new symbol-link scanner
  }
}, [isStreaming]);
```

### `agent/src/main/kotlin/…/agent/prompt/SystemPrompt.kt` — Rules section

Add to the communication / code-reference rules:

```
SYMBOL LINKS — When mentioning a class, method, field, enum constant, or other named code symbol,
wrap it as [DisplayName](symbol:com.example.FullyQualifiedName) for types, or
[memberName](symbol:com.example.ClassName#memberName) for members. Always use the fully qualified
name — bare names like [Foo](symbol:Foo) will not resolve if multiple matches exist in the project.
The IDE renders valid links as clickable; invalid or ambiguous ones fall back to plain text.
```

---

## Edge Cases

| Scenario | Behaviour |
|---|---|
| FQN not in project | Resolved count 0 → href stripped → plain text |
| Ambiguous simple name (same name, different packages) | Resolution prefers the full FQN; the simple-name fallback (used for members and as a last resort) picks the first provider match. |
| Overloaded method (`#doThing` with 3 overloads) | Picks first overload by PSI order; navigates to its definition |
| `resolveSymbols` times out (2 s) | Returns `[]` → all symbol hrefs stripped → no broken links shown |
| PSI not ready (dumb mode / indexing) | `readAction` returns null → treated as not-found |
| Kotlin top-level functions (FQN form) | Still unresolved — `findSymbol` has no top-level-function-by-FQN index. A bare name may resolve via `PsiShortNamesCache`. Extend later via `KotlinTopLevelFunctionFqnNameIndex`. |
| LLM omits `symbol:` prefix | No special handling — plain `[Foo](Foo)` is already handled by `AnchorNode` as a file path, which will fail validation and render as a plain link |

---

## Tests

| Test file | Coverage |
|---|---|
| `agent/webview/src/__tests__/symbol-link-scanner.test.tsx` | collectSymbolAnchors, valid/invalid patch, timeout fallback, dedup |
| `agent/src/test/kotlin/…/agent/link/SymbolLinkResolverTest.kt` | class resolution, member resolution (simple-class query), class fallback, multi-provider fan-out, not-found returns null |

---

## Update — 2026-05-26: language-agnostic resolution + `class:` consolidation

- `SymbolLinkResolver` moved from `:core` (`core/util`) to `:agent` (`agent/link`) and now resolves through `LanguageProviderRegistry.allProviders()` instead of `JavaPsiFacade` directly. **Python symbols now resolve** (and any future language provider works without changes). It lives in `:agent` because the registry is owned there by `AgentService`.
- Member links query the **simple** class form (`Foo.member`) because that is the only 2-part shape both `JavaKotlinProvider` (splits on `#`/`.`) and `PythonProvider` (splits on `.`) accept; a full FQN+member is not recognized by either. Misses fall back to the enclosing class.
- The redundant `class:` scheme is **no longer advertised** to the agent — the system-prompt `OUTPUT FORMATTING` section now lists only `symbol:` for code symbols. `class:` parsing/resolution (`LinkParser` + `ClassLinkResolver`) is **retained for backward compatibility** with already-persisted sessions.

## Out of Scope (still)

- Kotlin top-level functions by FQN (no top-level-function-by-FQN index — needs `KotlinTopLevelFunctionFqnNameIndex`)
- TypeScript / other non-provider languages (no `LanguageIntelligenceProvider` registered)
- Auto-detection of class names in plain text (regex approach; user explicitly declined)
- Disambiguation UI (dropped: LLM must use FQN; ambiguous → first match)
