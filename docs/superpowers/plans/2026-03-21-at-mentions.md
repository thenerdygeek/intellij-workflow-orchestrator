# @ Mention Autocomplete — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `@` mention autocomplete to the JCEF chat input bar — type `@` to see a category picker, then search files, symbols, tools, or skills with live filtering, and insert styled mention tokens that provide context to the LLM.

**Architecture:** The `@` keystroke in the textarea triggers a floating dropdown anchored to the cursor position. A JS→Kotlin bridge (`_searchMentions`) queries VFS/PSI/ToolRegistry/SkillRegistry on the Kotlin side and returns JSON results. Selected mentions are inserted as `<span class="mention">` tokens. When sending, mentions are extracted and prepended as context to the LLM message.

**Tech Stack:** HTML/CSS/JS (JCEF), Kotlin (IntelliJ VFS, PSI), existing JBCefJSQuery bridge pattern

---

## File Structure

### Modified Files

| File | Changes |
|------|---------|
| `agent/src/main/resources/webview/agent-chat.html` | CSS for mention dropdown + tokens. JS for @ detection, dropdown rendering, keyboard navigation, mention insertion, mention extraction on send. |
| `agent/src/main/kotlin/.../ui/AgentCefPanel.kt` | New `_searchMentions` JBCefJSQuery bridge with async callback pattern. |
| `agent/src/main/kotlin/.../ui/MentionSearchProvider.kt` | **NEW** — Kotlin-side search across VFS, PSI, tools, skills. Returns JSON. |
| `agent/src/main/kotlin/.../ui/AgentController.kt` | Extract mentions from message, prepend as context before sending to orchestrator. |
| `agent/src/test/kotlin/.../ui/MentionSearchProviderTest.kt` | Tests for search provider. |

---

## Design Decisions

### Mention Types

| Type | Trigger | Source | What it searches |
|------|---------|--------|-----------------|
| `file` | `@file:` or just `@` then pick File | VFS `ProjectRootManager.contentSourceRoots` | Project files by name pattern |
| `symbol` | `@symbol:` or pick Symbol | PSI `PsiShortNamesCache` | Classes, methods, fields by name |
| `tool` | `@tool:` or pick Tool | `ToolRegistry.allTools()` | Agent tools by name |
| `skill` | `@skill:` or pick Skill | `SkillRegistry.getUserInvocableSkills()` | User-invocable skills by name |

**No `@ticket`** — JiraService only has `getTicket(key)` (exact key lookup), not a search API. Users can type ticket keys directly in the message.

### UX Flow

1. User types `@` in textarea
2. **Category picker** appears: File, Symbol, Tool, Skill
3. User picks a category (click or arrow+Enter) OR continues typing to filter
4. Dropdown shows filtered results from that category
5. User selects a result (click or Enter)
6. A styled `<span class="mention" data-type="file" data-value="src/main/Foo.kt">@Foo.kt</span>` is inserted
7. On send, mentions are extracted and prepended as context:
   ```
   [Context: @file src/main/kotlin/com/example/Foo.kt, @tool search_code]

   User's actual message here
   ```

### Why Not contenteditable?

A `<textarea>` can't render styled spans inline. Two approaches:
- **A) contenteditable div** — full rich text, complex to implement (cursor management, paste handling)
- **B) textarea + visual overlay** — textarea for input, separate div for rendered mentions

We use **B** — it's simpler and the textarea already works with all our keyboard handling. Mentions appear as styled pills ABOVE the textarea (in a "mentioned items" bar), not inline. This matches how GitHub Issues and Slack mobile handle mentions — the mention is shown as a tag, not inline text.

### Mention Display

```
┌──────────────────────────────────────────────────┐
│ [@Foo.kt] [@search_code]                         │ ← mention pills bar
│ Fix the bug in the authentication module          │ ← textarea
│                                                   │
│ [◆ Sonnet 4.5 ▾] [☰ Plan] [⚡ Skills] [Send ➤] │
└──────────────────────────────────────────────────┘
```

---

## Task 1: Mention Dropdown CSS + HTML Structure

**Files:**
- Modify: `agent/src/main/resources/webview/agent-chat.html`

- [ ] **Step 1: Add mention CSS**

Add to the CSS section (after the `.chip-dropdown` styles):

```css
/* ═══════════════════════════════════════════════════
   @ Mention Autocomplete
   ═══════════════════════════════════════════════════ */
.mention-dropdown {
  display: none;
  position: absolute;
  bottom: 100%;
  left: 0;
  right: 0;
  margin-bottom: 4px;
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 10px;
  box-shadow: 0 8px 24px rgba(0,0,0,0.3);
  z-index: 100;
  max-height: 280px;
  overflow-y: auto;
  overflow-x: hidden;
}
.mention-dropdown.visible { display: block; }
.mention-dropdown::-webkit-scrollbar { width: 6px; }
.mention-dropdown::-webkit-scrollbar-thumb { background: var(--border); border-radius: 3px; }

.mention-category {
  padding: 6px 12px;
  font-size: 10px;
  font-weight: 600;
  color: var(--fg-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  border-bottom: 1px solid var(--divider-subtle);
}

.mention-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 12px;
  cursor: pointer;
  transition: background 0.1s;
  font-size: 12px;
}
.mention-item:hover, .mention-item.selected {
  background: var(--hover-overlay-strong);
}
.mention-item .mention-icon {
  font-size: 12px;
  color: var(--fg-muted);
  width: 18px;
  text-align: center;
  flex-shrink: 0;
}
.mention-item .mention-name {
  color: var(--fg);
  font-family: var(--font-mono);
  font-size: 12px;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mention-item .mention-desc {
  color: var(--fg-muted);
  font-size: 11px;
  flex-shrink: 0;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mention-item .mention-path {
  color: var(--fg-muted);
  font-size: 10px;
  font-family: var(--font-mono);
}

/* Mention pills bar (above textarea) */
.mention-pills {
  display: none;
  flex-wrap: wrap;
  gap: 4px;
  padding: 0 0 6px 0;
}
.mention-pills.has-items { display: flex; }
.mention-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  background: rgba(59,130,246,0.1);
  border: 1px solid rgba(59,130,246,0.2);
  border-radius: 6px;
  font-size: 11px;
  color: var(--link);
  font-family: var(--font-mono);
}
.mention-pill .pill-remove {
  cursor: pointer;
  opacity: 0.5;
  font-size: 12px;
  margin-left: 2px;
}
.mention-pill .pill-remove:hover { opacity: 1; }
.mention-pill.type-file { background: rgba(59,130,246,0.1); border-color: rgba(59,130,246,0.2); color: var(--link); }
.mention-pill.type-symbol { background: rgba(139,92,246,0.1); border-color: rgba(139,92,246,0.2); color: #a78bfa; }
.mention-pill.type-tool { background: rgba(34,197,94,0.1); border-color: rgba(34,197,94,0.2); color: #4ade80; }
.mention-pill.type-skill { background: rgba(251,191,36,0.1); border-color: rgba(251,191,36,0.2); color: #fbbf24; }

/* Mention loading spinner */
.mention-loading {
  padding: 12px;
  text-align: center;
  color: var(--fg-muted);
  font-size: 11px;
}
```

- [ ] **Step 2: Add mention dropdown and pills bar to HTML structure**

In the input bar HTML, add the mention dropdown INSIDE `.input-wrapper` (before the textarea), and the pills bar before the textarea:

Find the `<div class="input-wrapper">` and change its content to:

```html
<div class="input-wrapper">
  <div class="mention-dropdown" id="mention-dropdown"></div>
  <div class="mention-pills" id="mention-pills"></div>
  <textarea class="input-ta" id="chat-input" placeholder="Ask the agent to do something... (@ to mention)" rows="1"></textarea>
  <div class="input-bottom">
    <!-- existing chips and send button unchanged -->
  </div>
</div>
```

- [ ] **Step 3: Compile and commit**

```bash
./gradlew :agent:compileKotlin -x verifyPlugin
git add agent/src/main/resources/webview/agent-chat.html
git commit -m "feat(agent-ui): @ mention autocomplete CSS and HTML structure

Add mention dropdown, mention pills bar, and mention item styles. Dropdown
positioned above input wrapper. Pills bar shows selected mentions as
color-coded tokens. Category-specific colors for file/symbol/tool/skill."
```

---

## Task 2: MentionSearchProvider (Kotlin)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/MentionSearchProvider.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/ui/MentionSearchProviderTest.kt`

- [ ] **Step 1: Create MentionSearchProvider**

```kotlin
package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.search.PsiShortNamesCache
import kotlinx.serialization.json.*

/**
 * Provides search results for @ mention autocomplete.
 * Searches project files (VFS), symbols (PSI), tools, and skills.
 * Returns JSON arrays for the JCEF dropdown.
 */
class MentionSearchProvider(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(MentionSearchProvider::class.java)
        private const val MAX_RESULTS = 15
        private val FILE_EXTENSIONS = setOf("kt", "java", "xml", "yaml", "yml", "json", "properties", "gradle", "md", "html", "css", "js", "ts", "py", "go", "rs")
    }

    /**
     * Search for mentions by type and query.
     * @param type One of: "file", "symbol", "tool", "skill", "categories"
     * @param query Search query (empty = show all/top results)
     * @return JSON string of results array
     */
    fun search(type: String, query: String): String {
        return try {
            when (type) {
                "categories" -> buildCategoriesJson()
                "file" -> searchFiles(query)
                "symbol" -> searchSymbols(query)
                "tool" -> searchTools(query)
                "skill" -> searchSkills(query)
                else -> "[]"
            }
        } catch (e: Exception) {
            LOG.debug("MentionSearchProvider: search failed for type=$type query=$query: ${e.message}")
            "[]"
        }
    }

    private fun buildCategoriesJson(): String = buildJsonArray {
        add(buildJsonObject {
            put("type", JsonPrimitive("file"))
            put("icon", JsonPrimitive("&#x1F4C4;"))
            put("label", JsonPrimitive("File"))
            put("hint", JsonPrimitive("Search project files"))
        })
        add(buildJsonObject {
            put("type", JsonPrimitive("symbol"))
            put("icon", JsonPrimitive("&#x2726;"))
            put("label", JsonPrimitive("Symbol"))
            put("hint", JsonPrimitive("Search classes, methods"))
        })
        add(buildJsonObject {
            put("type", JsonPrimitive("tool"))
            put("icon", JsonPrimitive("&#x2699;"))
            put("label", JsonPrimitive("Tool"))
            put("hint", JsonPrimitive("Agent tools"))
        })
        add(buildJsonObject {
            put("type", JsonPrimitive("skill"))
            put("icon", JsonPrimitive("&#x26A1;"))
            put("label", JsonPrimitive("Skill"))
            put("hint", JsonPrimitive("Workflow skills"))
        })
    }.toString()

    private fun searchFiles(query: String): String {
        val lowerQuery = query.lowercase()
        val results = mutableListOf<JsonObject>()
        val basePath = project.basePath ?: return "[]"

        try {
            val roots = ProjectRootManager.getInstance(project).contentSourceRoots
            for (root in roots) {
                if (results.size >= MAX_RESULTS) break
                collectFiles(root, lowerQuery, basePath, results)
            }
        } catch (_: Exception) {}

        return JsonArray(results).toString()
    }

    private fun collectFiles(
        dir: com.intellij.openapi.vfs.VirtualFile,
        query: String,
        basePath: String,
        results: MutableList<JsonObject>
    ) {
        if (results.size >= MAX_RESULTS) return
        for (child in dir.children) {
            if (results.size >= MAX_RESULTS) return
            if (child.isDirectory) {
                if (child.name.startsWith(".") || child.name == "node_modules" || child.name == "build" || child.name == "out") continue
                collectFiles(child, query, basePath, results)
            } else {
                if (child.extension?.lowercase() !in FILE_EXTENSIONS) continue
                if (query.isBlank() || child.name.lowercase().contains(query) || child.path.lowercase().contains(query)) {
                    val relativePath = child.path.removePrefix("$basePath/")
                    results.add(buildJsonObject {
                        put("type", JsonPrimitive("file"))
                        put("name", JsonPrimitive(child.name))
                        put("value", JsonPrimitive(relativePath))
                        put("desc", JsonPrimitive(relativePath.substringBeforeLast('/')))
                    })
                }
            }
        }
    }

    private fun searchSymbols(query: String): String {
        if (query.length < 2) return "[]" // Need at least 2 chars for symbol search
        val results = mutableListOf<JsonObject>()

        try {
            ReadAction.run<Exception> {
                val cache = PsiShortNamesCache.getInstance(project)
                // Search classes
                val classNames = cache.allClassNames.filter { it.lowercase().contains(query.lowercase()) }.take(MAX_RESULTS)
                for (name in classNames) {
                    if (results.size >= MAX_RESULTS) break
                    val classes = cache.getClassesByName(name, com.intellij.psi.search.GlobalSearchScope.projectScope(project))
                    for (cls in classes) {
                        if (results.size >= MAX_RESULTS) break
                        val qualifiedName = cls.qualifiedName ?: name
                        val filePath = cls.containingFile?.virtualFile?.path?.let {
                            project.basePath?.let { bp -> it.removePrefix("$bp/") } ?: it
                        } ?: ""
                        results.add(buildJsonObject {
                            put("type", JsonPrimitive("symbol"))
                            put("name", JsonPrimitive(name))
                            put("value", JsonPrimitive(qualifiedName))
                            put("desc", JsonPrimitive(filePath))
                        })
                    }
                }
            }
        } catch (_: Exception) {}

        return JsonArray(results).toString()
    }

    private fun searchTools(query: String): String {
        val agentService = try {
            com.workflow.orchestrator.agent.AgentService.getInstance(project)
        } catch (_: Exception) { return "[]" }

        val lowerQuery = query.lowercase()
        val tools = agentService.toolRegistry.allTools()
            .filter { lowerQuery.isBlank() || it.name.lowercase().contains(lowerQuery) || it.description.lowercase().contains(lowerQuery) }
            .take(MAX_RESULTS)

        return buildJsonArray {
            for (tool in tools) {
                add(buildJsonObject {
                    put("type", JsonPrimitive("tool"))
                    put("name", JsonPrimitive(tool.name))
                    put("value", JsonPrimitive(tool.name))
                    put("desc", JsonPrimitive(tool.description.take(60)))
                })
            }
        }.toString()
    }

    private fun searchSkills(query: String): String {
        val agentService = try {
            com.workflow.orchestrator.agent.AgentService.getInstance(project)
        } catch (_: Exception) { return "[]" }

        val lowerQuery = query.lowercase()
        val skills = agentService.currentSkillManager?.registry?.getUserInvocableSkills()
            ?.filter { lowerQuery.isBlank() || it.name.lowercase().contains(lowerQuery) || it.description.lowercase().contains(lowerQuery) }
            ?.take(MAX_RESULTS)
            ?: return "[]"

        return buildJsonArray {
            for (skill in skills) {
                add(buildJsonObject {
                    put("type", JsonPrimitive("skill"))
                    put("name", JsonPrimitive(skill.name))
                    put("value", JsonPrimitive(skill.name))
                    put("desc", JsonPrimitive(skill.description.take(60)))
                })
            }
        }.toString()
    }
}
```

- [ ] **Step 2: Write tests**

```kotlin
@Test
fun `categories returns all 4 types`() {
    val provider = MentionSearchProvider(project)
    val json = provider.search("categories", "")
    val arr = Json.parseToJsonElement(json).jsonArray
    assertEquals(4, arr.size)
    val types = arr.map { it.jsonObject["type"]?.jsonPrimitive?.content }
    assertTrue("file" in types)
    assertTrue("symbol" in types)
    assertTrue("tool" in types)
    assertTrue("skill" in types)
}

@Test
fun `tool search filters by name`() {
    val provider = MentionSearchProvider(project)
    val json = provider.search("tool", "read")
    val arr = Json.parseToJsonElement(json).jsonArray
    assertTrue(arr.isNotEmpty())
    assertTrue(arr.all { it.jsonObject["name"]?.jsonPrimitive?.content?.contains("read") == true })
}

@Test
fun `unknown type returns empty array`() {
    val provider = MentionSearchProvider(project)
    assertEquals("[]", provider.search("unknown", ""))
}
```

- [ ] **Step 3: Compile and test**

```bash
./gradlew :agent:test --tests "*.MentionSearchProviderTest" -x verifyPlugin
```

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/MentionSearchProvider.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/ui/MentionSearchProviderTest.kt
git commit -m "feat(agent): MentionSearchProvider for @ mention autocomplete

Searches project files (VFS), symbols (PSI), tools (ToolRegistry), and
skills (SkillRegistry). Returns JSON arrays. Max 15 results per search.
Symbol search requires 2+ chars. File search excludes build/node_modules."
```

---

## Task 3: JBCefJSQuery Bridge for Mention Search

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`

- [ ] **Step 1: Add searchMentions bridge to AgentCefPanel**

Add field:
```kotlin
private var searchMentionsQuery: JBCefJSQuery? = null
```

The mention search is async — JS sends a query, Kotlin searches, then calls back with results via `executeJavaScript`. The pattern:

In `createBrowser()`, create the query:
```kotlin
searchMentionsQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
    addHandler { data ->
        // data format: "type:query" e.g. "file:Login" or "categories:"
        val colonIdx = data.indexOf(':')
        val type = if (colonIdx > 0) data.substring(0, colonIdx) else data
        val query = if (colonIdx > 0) data.substring(colonIdx + 1) else ""
        // Search on IO thread, callback to JS
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val results = mentionSearchProvider?.search(type, query) ?: "[]"
            callJs("receiveMentionResults(${jsonStr(results)})")
        }
        JBCefJSQuery.Response("ok")
    }
}
```

Add field:
```kotlin
var mentionSearchProvider: MentionSearchProvider? = null
```

In `onLoadingStateChange`, inject:
```kotlin
searchMentionsQuery?.let { q ->
    val searchJs = q.inject("data")
    js("window._searchMentions = function(data) { $searchJs }")
}
```

In `dispose()`:
```kotlin
searchMentionsQuery?.dispose(); searchMentionsQuery = null
```

- [ ] **Step 2: Wire MentionSearchProvider in AgentController**

In `AgentController.init`, after the existing bridge wiring, add:
```kotlin
dashboard.cefPanel?.mentionSearchProvider = MentionSearchProvider(project)
```

Wait — `cefPanel` is private on `AgentDashboardPanel`. Add a delegation method:

In `AgentDashboardPanel`:
```kotlin
fun setMentionSearchProvider(provider: MentionSearchProvider) {
    cefPanel?.mentionSearchProvider = provider
}
```

In `AgentController.init`:
```kotlin
dashboard.setMentionSearchProvider(MentionSearchProvider(project))
```

- [ ] **Step 3: Compile and verify**

```bash
./gradlew :agent:compileKotlin -x verifyPlugin
```

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt
git commit -m "feat(agent-ui): JBCefJSQuery bridge for @ mention search

_searchMentions bridge sends type:query from JS, Kotlin searches on IO
thread, results returned via receiveMentionResults() JS callback.
MentionSearchProvider wired in AgentController."
```

---

## Task 4: @ Mention JavaScript — Detection, Dropdown, Keyboard Navigation, Insertion

**Files:**
- Modify: `agent/src/main/resources/webview/agent-chat.html`

This is the main JS task — all the interaction logic.

- [ ] **Step 1: Add mention state variables and core functions**

Add after the existing input bar JS functions:

```javascript
/* ═══════════════════════════════════════════════════
   @ Mention Autocomplete
   ═══════════════════════════════════════════════════ */
var _mentionState = null; // { active: bool, type: null|'file'|'symbol'|'tool'|'skill', query: '', startPos: 0, selectedIndex: 0, results: [] }
var _mentions = []; // Array of { type, name, value }

function initMentionSystem() {
  var ta = document.getElementById('chat-input');
  if (!ta) return;

  ta.addEventListener('input', function(e) {
    var val = this.value;
    var pos = this.selectionStart;

    // Check if we just typed @
    if (val.charAt(pos - 1) === '@' && (pos === 1 || /\s/.test(val.charAt(pos - 2)))) {
      _mentionState = { active: true, type: null, query: '', startPos: pos, selectedIndex: 0, results: [] };
      showMentionCategories();
      return;
    }

    // If mention is active, update query
    if (_mentionState && _mentionState.active) {
      var textAfterAt = val.substring(_mentionState.startPos);
      var spaceIdx = textAfterAt.indexOf(' ');
      var query = spaceIdx >= 0 ? textAfterAt.substring(0, spaceIdx) : textAfterAt;

      // Check for type prefix: file:, symbol:, tool:, skill:
      var colonIdx = query.indexOf(':');
      if (colonIdx > 0 && !_mentionState.type) {
        var typePart = query.substring(0, colonIdx);
        if (['file', 'symbol', 'tool', 'skill'].indexOf(typePart) >= 0) {
          _mentionState.type = typePart;
          _mentionState.query = query.substring(colonIdx + 1);
        } else {
          _mentionState.query = query;
        }
      } else if (_mentionState.type) {
        _mentionState.query = query;
      } else {
        _mentionState.query = query;
      }

      // If space was typed, close dropdown
      if (spaceIdx >= 0 && !_mentionState.type) {
        closeMentionDropdown();
        return;
      }

      // Search
      if (_mentionState.type) {
        searchMentions(_mentionState.type, _mentionState.query);
      } else if (_mentionState.query.length > 0) {
        // Filter categories by typed text
        filterCategories(_mentionState.query);
      }
    }
  });

  // Keyboard navigation for mention dropdown
  ta.addEventListener('keydown', function(e) {
    if (!_mentionState || !_mentionState.active) return;
    var dd = document.getElementById('mention-dropdown');
    if (!dd || !dd.classList.contains('visible')) return;

    if (e.key === 'ArrowDown') {
      e.preventDefault();
      _mentionState.selectedIndex = Math.min(_mentionState.selectedIndex + 1, _mentionState.results.length - 1);
      updateMentionSelection();
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      _mentionState.selectedIndex = Math.max(_mentionState.selectedIndex - 1, 0);
      updateMentionSelection();
    } else if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      e.stopPropagation();
      selectMentionItem(_mentionState.selectedIndex);
    } else if (e.key === 'Escape') {
      e.preventDefault();
      e.stopPropagation();
      closeMentionDropdown();
    } else if (e.key === 'Tab') {
      e.preventDefault();
      selectMentionItem(_mentionState.selectedIndex);
    }
  }, true); // capture phase to intercept before sendMessage handler
}

function showMentionCategories() {
  if (window._searchMentions) {
    window._searchMentions('categories:');
  }
}

function searchMentions(type, query) {
  if (window._searchMentions) {
    window._searchMentions(type + ':' + query);
  }
}

function receiveMentionResults(resultsJson) {
  var results;
  try {
    results = typeof resultsJson === 'string' ? JSON.parse(resultsJson) : resultsJson;
  } catch (e) { return; }

  if (!_mentionState || !_mentionState.active) return;
  _mentionState.results = results;
  _mentionState.selectedIndex = 0;
  renderMentionDropdown(results);
}

function renderMentionDropdown(items) {
  var dd = document.getElementById('mention-dropdown');
  if (!dd) return;

  if (items.length === 0) {
    dd.innerHTML = '<div class="mention-loading">No results</div>';
    dd.classList.add('visible');
    return;
  }

  var html = '';
  items.forEach(function(item, idx) {
    var selected = idx === _mentionState.selectedIndex ? ' selected' : '';
    var icon = item.icon || (item.type === 'file' ? '&#x1F4C4;' : item.type === 'symbol' ? '&#x2726;' : item.type === 'tool' ? '&#x2699;' : '&#x26A1;');

    if (item.type && item.label) {
      // Category item
      html += '<div class="mention-item' + selected + '" data-index="' + idx + '" onclick="selectCategory(\'' + esc(item.type) + '\')">'
        + '<span class="mention-icon">' + icon + '</span>'
        + '<span class="mention-name">' + esc(item.label) + '</span>'
        + '<span class="mention-desc">' + esc(item.hint || '') + '</span>'
        + '</div>';
    } else {
      // Result item
      html += '<div class="mention-item' + selected + '" data-index="' + idx + '" onclick="selectMentionItem(' + idx + ')">'
        + '<span class="mention-icon">' + icon + '</span>'
        + '<span class="mention-name">' + esc(item.name) + '</span>'
        + '<span class="mention-desc">' + esc(item.desc || '') + '</span>'
        + '</div>';
    }
  });

  dd.innerHTML = html;
  dd.classList.add('visible');
}

function filterCategories(query) {
  // Re-request categories and filter client-side
  // For simplicity, just search across all types
  searchMentions('file', query);
}

function selectCategory(type) {
  _mentionState.type = type;
  _mentionState.query = '';
  _mentionState.selectedIndex = 0;
  // Remove the category text from textarea
  var ta = document.getElementById('chat-input');
  // Search with empty query to show top results
  searchMentions(type, '');
}

function selectMentionItem(index) {
  if (!_mentionState || index < 0 || index >= _mentionState.results.length) return;
  var item = _mentionState.results[index];
  if (!item.value && item.type && item.label) {
    // This is a category, not a result
    selectCategory(item.type);
    return;
  }

  // Add to mentions list
  _mentions.push({ type: item.type || _mentionState.type || 'file', name: item.name, value: item.value });

  // Remove the @query text from textarea
  var ta = document.getElementById('chat-input');
  if (ta) {
    var val = ta.value;
    var before = val.substring(0, _mentionState.startPos - 1); // before the @
    var after = val.substring(ta.selectionStart);
    ta.value = before + after;
    ta.selectionStart = ta.selectionEnd = before.length;
    ta.dispatchEvent(new Event('input')); // trigger auto-expand
  }

  // Render pills
  renderMentionPills();
  closeMentionDropdown();
}

function updateMentionSelection() {
  var dd = document.getElementById('mention-dropdown');
  if (!dd) return;
  dd.querySelectorAll('.mention-item').forEach(function(el, i) {
    el.classList.toggle('selected', i === _mentionState.selectedIndex);
  });
  // Scroll selected into view
  var sel = dd.querySelector('.mention-item.selected');
  if (sel) sel.scrollIntoView({ block: 'nearest' });
}

function closeMentionDropdown() {
  _mentionState = null;
  var dd = document.getElementById('mention-dropdown');
  if (dd) { dd.classList.remove('visible'); dd.innerHTML = ''; }
}

function renderMentionPills() {
  var container = document.getElementById('mention-pills');
  if (!container) return;
  if (_mentions.length === 0) {
    container.classList.remove('has-items');
    container.innerHTML = '';
    return;
  }
  container.classList.add('has-items');
  container.innerHTML = _mentions.map(function(m, i) {
    return '<span class="mention-pill type-' + esc(m.type) + '">'
      + '@' + esc(m.name)
      + '<span class="pill-remove" onclick="removeMention(' + i + ')">&#xD7;</span>'
      + '</span>';
  }).join('');
}

function removeMention(index) {
  _mentions.splice(index, 1);
  renderMentionPills();
}

// Close dropdown on click outside
document.addEventListener('click', function(e) {
  if (!e.target.closest('.input-wrapper')) {
    closeMentionDropdown();
  }
});

// Initialize
initMentionSystem();
```

- [ ] **Step 2: Modify `sendMessage()` to include mentions as context**

Replace the existing `sendMessage()` function:

```javascript
function sendMessage() {
  var ta = document.getElementById('chat-input');
  var text = ta ? ta.value.trim() : '';
  if (!text && _mentions.length === 0) return;

  // Build message with mention context
  var message = text;
  if (_mentions.length > 0) {
    var context = _mentions.map(function(m) {
      return '@' + m.type + ' ' + m.value;
    }).join(', ');
    message = '[Context: ' + context + ']\n\n' + text;
  }

  // Clear state
  var sendBtn = document.getElementById('send-btn');
  if (sendBtn) sendBtn.disabled = true;
  ta.value = '';
  ta.style.height = 'auto';
  _mentions = [];
  renderMentionPills();
  closeMentionDropdown();

  if (window._sendMessage) window._sendMessage(message);
}
```

- [ ] **Step 3: Compile and verify**

```bash
./gradlew :agent:compileKotlin -x verifyPlugin
```

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/resources/webview/agent-chat.html
git commit -m "feat(agent-ui): @ mention autocomplete JS — detection, dropdown, keyboard, pills

Type @ to see category picker (File, Symbol, Tool, Skill). Continue typing
to filter. Arrow keys + Enter/Tab to select. Mentions shown as color-coded
pills above textarea. Mentions prepended as [Context: ...] to message."
```

---

## Task 5: Documentation Update

**Files:**
- Modify: `agent/CLAUDE.md`

- [ ] **Step 1: Update the Rich Chat UI section**

Add to the "IDE-native features" list:
```markdown
- @ mention autocomplete: type @ to search files (VFS), symbols (PSI), tools, skills — mentions provide context to the LLM
```

- [ ] **Step 2: Run all tests**

```bash
./gradlew :agent:test --rerun --no-build-cache -x verifyPlugin
```

- [ ] **Step 3: Commit**

```bash
git add agent/CLAUDE.md
git commit -m "docs: add @ mention autocomplete to agent documentation"
```

---

## Verification

```bash
./gradlew :agent:test --rerun --no-daemon
./gradlew verifyPlugin
```

Manual verification in `runIde`:
1. Type `@` in the input bar — verify category picker appears (File, Symbol, Tool, Skill)
2. Click "File" — verify project files appear
3. Type `@Login` — verify files matching "Login" appear
4. Use arrow keys to navigate, Enter to select — verify mention pill appears above textarea
5. Add multiple mentions — verify pills stack
6. Click × on a pill — verify it's removed
7. Send message — verify `[Context: @file src/...]` is prepended
8. Type `@tool:search` — verify tool results appear
9. Press Escape while dropdown is open — verify it closes without sending
10. Type normally (no @) — verify no dropdown appears
