# @ Mention V2 — Content Injection & Folder Support

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade @ mentions from path-only text prepending to Claude Code-style content injection — `@file` reads the file and injects its content into the LLM context, `@folder` injects a directory tree, and all mention context is stored as a compression-proof anchor in ContextManager.

**Architecture:** A new `MentionContextBuilder` (Kotlin) reads file content via VFS/Document API at send time. Mention context is injected as a dedicated `mentionAnchor` in `ContextManager` (surviving compression like plan/skill anchors). The JS `sendMessage()` sends mentions as structured JSON alongside the text, not prepended as `[Context: ...]`. AgentController builds the anchor from the JSON before recording the user message.

**Tech Stack:** Kotlin (IntelliJ VFS, Document API), ContextManager anchor system, JSON mention payload

---

## File Structure

### New Files

| File | Responsibility |
|------|---------------|
| `agent/src/main/kotlin/.../ui/MentionContextBuilder.kt` | Reads file content, builds directory trees, assembles mention context for LLM |
| `agent/src/test/kotlin/.../ui/MentionContextBuilderTest.kt` | Tests for content reading, folder tree, token budget |

### Modified Files

| File | Changes |
|------|---------|
| `agent/src/main/resources/webview/agent-chat.html` | Change `sendMessage()` to send JSON mentions via separate bridge. Add `@folder` category. |
| `agent/src/main/kotlin/.../ui/AgentCefPanel.kt` | New `_sendMessageWithMentions` bridge accepting text + mentionsJSON separately. |
| `agent/src/main/kotlin/.../ui/AgentController.kt` | Build mention anchor from JSON, set on ContextManager before recording user message. |
| `agent/src/main/kotlin/.../context/ContextManager.kt` | Add `mentionAnchor` slot (compression-proof, like planAnchor). |
| `agent/src/main/kotlin/.../ui/MentionSearchProvider.kt` | Add `folder` search type. |
| `agent/src/main/kotlin/.../ui/AgentDashboardPanel.kt` | New delegation for mention-aware send. |

---

## Task 1: MentionContextBuilder — Read Files, Build Trees

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/MentionContextBuilder.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/ui/MentionContextBuilderTest.kt`

- [ ] **Step 1: Create MentionContextBuilder**

```kotlin
package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

/**
 * Builds rich context from @ mentions.
 * Reads file content via IntelliJ Document API (sees unsaved changes),
 * generates directory trees, and assembles a context string for the LLM.
 *
 * Token budget: Each file is capped at 500 lines / 20K chars.
 * Total mention context is capped at 50K chars (~12.5K tokens).
 */
class MentionContextBuilder(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(MentionContextBuilder::class.java)
        private const val MAX_FILE_LINES = 500
        private const val MAX_FILE_CHARS = 20_000
        private const val MAX_TOTAL_CHARS = 50_000
        private const val MAX_TREE_DEPTH = 4
        private const val MAX_TREE_ENTRIES = 100
    }

    data class Mention(
        val type: String,   // "file", "symbol", "tool", "skill", "folder"
        val name: String,   // Display name
        val value: String   // Path or identifier
    )

    /**
     * Build a context string from a list of mentions.
     * Returns XML-tagged context suitable for injection as a system message.
     */
    fun buildContext(mentions: List<Mention>): String? {
        if (mentions.isEmpty()) return null
        val basePath = project.basePath ?: return null

        val sb = StringBuilder()
        var totalChars = 0

        for (mention in mentions) {
            if (totalChars >= MAX_TOTAL_CHARS) {
                sb.appendLine("\n[Mention context truncated — ${MAX_TOTAL_CHARS / 1000}K char budget reached]")
                break
            }

            val section = when (mention.type) {
                "file" -> buildFileContext(mention, basePath)
                "folder" -> buildFolderContext(mention, basePath)
                "symbol" -> buildSymbolContext(mention)
                "tool" -> buildToolContext(mention)
                "skill" -> buildSkillContext(mention)
                else -> null
            } ?: continue

            val remaining = MAX_TOTAL_CHARS - totalChars
            val truncated = if (section.length > remaining) {
                section.take(remaining) + "\n[truncated]"
            } else section

            sb.append(truncated)
            totalChars += truncated.length
        }

        return if (sb.isNotBlank()) sb.toString() else null
    }

    private fun buildFileContext(mention: Mention, basePath: String): String {
        val fullPath = if (mention.value.startsWith("/")) mention.value
                       else "$basePath/${mention.value}"
        val file = File(fullPath)
        if (!file.isFile) return "<mentioned_file path=\"${mention.value}\">\nFile not found.\n</mentioned_file>\n\n"

        // Try IntelliJ Document API first (sees unsaved changes)
        val content = try {
            val vf = LocalFileSystem.getInstance().findFileByPath(fullPath)
            if (vf != null) {
                val doc = ReadAction.compute<Document?, Exception> {
                    FileDocumentManager.getInstance().getDocument(vf)
                }
                doc?.text ?: vf.contentsToByteArray().toString(Charsets.UTF_8)
            } else {
                file.readText()
            }
        } catch (e: Exception) {
            return "<mentioned_file path=\"${mention.value}\">\nError reading file: ${e.message}\n</mentioned_file>\n\n"
        }

        // Truncate large files
        val lines = content.lines()
        val truncatedContent = if (lines.size > MAX_FILE_LINES) {
            lines.take(MAX_FILE_LINES).joinToString("\n") + "\n\n[File truncated at $MAX_FILE_LINES lines — ${lines.size} total]"
        } else if (content.length > MAX_FILE_CHARS) {
            content.take(MAX_FILE_CHARS) + "\n\n[File truncated at ${MAX_FILE_CHARS / 1000}K chars]"
        } else content

        return "<mentioned_file path=\"${mention.value}\" lines=\"${lines.size}\">\n$truncatedContent\n</mentioned_file>\n\n"
    }

    private fun buildFolderContext(mention: Mention, basePath: String): String {
        val fullPath = if (mention.value.startsWith("/")) mention.value
                       else "$basePath/${mention.value}"
        val dir = File(fullPath)
        if (!dir.isDirectory) return "<mentioned_folder path=\"${mention.value}\">\nDirectory not found.\n</mentioned_folder>\n\n"

        val tree = buildTree(dir, "", 0)
        return "<mentioned_folder path=\"${mention.value}\">\n$tree</mentioned_folder>\n\n"
    }

    private fun buildTree(dir: File, prefix: String, depth: Int): String {
        if (depth > MAX_TREE_DEPTH) return "${prefix}...\n"
        val sb = StringBuilder()
        var count = 0
        val entries = dir.listFiles()
            ?.filter { !it.name.startsWith(".") && it.name != "node_modules" && it.name != "build" && it.name != "out" && it.name != "__pycache__" }
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })
            ?: return ""

        for (entry in entries) {
            if (count >= MAX_TREE_ENTRIES) {
                sb.appendLine("${prefix}... (${entries.size - count} more entries)")
                break
            }
            if (entry.isDirectory) {
                sb.appendLine("${prefix}${entry.name}/")
                sb.append(buildTree(entry, "$prefix  ", depth + 1))
            } else {
                sb.appendLine("${prefix}${entry.name}")
            }
            count++
        }
        return sb.toString()
    }

    private fun buildSymbolContext(mention: Mention): String {
        return "<mentioned_symbol name=\"${mention.name}\" qualified=\"${mention.value}\">\nSymbol: ${mention.value}\nThe user is referencing this symbol. Use find_definition or find_references to explore it.\n</mentioned_symbol>\n\n"
    }

    private fun buildToolContext(mention: Mention): String {
        return "<mentioned_tool name=\"${mention.value}\">\nThe user wants you to use the ${mention.value} tool.\n</mentioned_tool>\n\n"
    }

    private fun buildSkillContext(mention: Mention): String {
        return "<mentioned_skill name=\"${mention.value}\">\nThe user wants you to activate the /${mention.value} skill.\n</mentioned_skill>\n\n"
    }
}
```

- [ ] **Step 2: Write tests**

```kotlin
@Test
fun `buildFileContext reads file content`() {
    val file = tempDir.resolve("Test.kt").also { it.writeText("class Test {\n  fun hello() {}\n}") }
    val builder = MentionContextBuilder(project)
    val mention = MentionContextBuilder.Mention("file", "Test.kt", file.absolutePath)
    val context = builder.buildContext(listOf(mention))
    assertNotNull(context)
    assertTrue(context!!.contains("class Test"))
    assertTrue(context.contains("<mentioned_file"))
}

@Test
fun `buildFolderContext generates tree`() {
    val dir = tempDir.resolve("src").also { it.mkdirs() }
    File(dir, "Main.kt").writeText("fun main() {}")
    File(dir, "Utils.kt").writeText("object Utils {}")
    val mention = MentionContextBuilder.Mention("folder", "src", dir.absolutePath)
    val context = builder.buildContext(listOf(mention))
    assertNotNull(context)
    assertTrue(context!!.contains("Main.kt"))
    assertTrue(context.contains("Utils.kt"))
    assertTrue(context.contains("<mentioned_folder"))
}

@Test
fun `large file is truncated`() {
    val largeContent = (1..1000).joinToString("\n") { "line $it: some code here" }
    val file = tempDir.resolve("Large.kt").also { it.writeText(largeContent) }
    val mention = MentionContextBuilder.Mention("file", "Large.kt", file.absolutePath)
    val context = builder.buildContext(listOf(mention))
    assertNotNull(context)
    assertTrue(context!!.contains("[File truncated"))
}

@Test
fun `tool mention provides instruction`() {
    val mention = MentionContextBuilder.Mention("tool", "search_code", "search_code")
    val context = builder.buildContext(listOf(mention))
    assertNotNull(context)
    assertTrue(context!!.contains("use the search_code tool"))
}

@Test
fun `empty mentions returns null`() {
    assertNull(builder.buildContext(emptyList()))
}
```

- [ ] **Step 3: Compile and test**

```bash
./gradlew :agent:test --tests "*.MentionContextBuilderTest" -x verifyPlugin
```

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/MentionContextBuilder.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/ui/MentionContextBuilderTest.kt
git commit -m "feat(agent): MentionContextBuilder reads file content for @ mentions

Reads file content via IntelliJ Document API (sees unsaved changes).
Generates directory trees for @folder. Caps at 500 lines/20K per file,
50K total. XML-tagged output for LLM injection."
```

---

## Task 2: Add mentionAnchor to ContextManager

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt`

- [ ] **Step 1: Add mentionAnchor slot**

After `skillAnchor` (line ~47), add:

```kotlin
/** Dedicated mention anchor — file content from @ mentions, survives compression. */
private var mentionAnchor: ChatMessage? = null
```

Add setter:
```kotlin
fun setMentionAnchor(message: ChatMessage?) {
    mentionAnchor = message
    totalTokens = TokenEstimator.estimate(getMessages())
}
```

- [ ] **Step 2: Include mentionAnchor in getMessages()**

In `getMessages()`, after `skillAnchor?.let { result.add(it) }`, add:

```kotlin
mentionAnchor?.let { result.add(it) }
```

- [ ] **Step 3: Compile and verify**

```bash
./gradlew :agent:compileKotlin -x verifyPlugin
```

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt
git commit -m "feat(agent): add mentionAnchor to ContextManager for @ mention content

Mention context (file contents, folder trees) stored as compression-proof
anchor alongside planAnchor and skillAnchor. Updated in getMessages()."
```

---

## Task 3: Change JS sendMessage to Send Structured Mentions

**Files:**
- Modify: `agent/src/main/resources/webview/agent-chat.html`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/MentionSearchProvider.kt`

- [ ] **Step 1: Add @folder to MentionSearchProvider categories and search**

In `MentionSearchProvider.buildCategoriesJson()`, add after the file entry:
```kotlin
add(buildJsonObject {
    put("type", JsonPrimitive("folder"))
    put("icon", JsonPrimitive("[D]"))
    put("label", JsonPrimitive("Folder"))
    put("hint", JsonPrimitive("Search project directories"))
})
```

Add `searchFolders()` method:
```kotlin
private fun searchFolders(query: String): String {
    val lowerQuery = query.lowercase()
    val results = mutableListOf<JsonObject>()
    val basePath = project.basePath ?: return "[]"

    try {
        val roots = ProjectRootManager.getInstance(project).contentSourceRoots
        for (root in roots) {
            collectFolders(root, lowerQuery, basePath, results)
        }
    } catch (_: Exception) {}

    return JsonArray(results).toString()
}

private fun collectFolders(
    dir: com.intellij.openapi.vfs.VirtualFile,
    query: String,
    basePath: String,
    results: MutableList<JsonObject>
) {
    if (results.size >= MAX_RESULTS) return
    if (dir.name.startsWith(".") || dir.name == "node_modules" || dir.name == "build" || dir.name == "out") return
    val relativePath = dir.path.removePrefix("$basePath/")
    if (query.isBlank() || dir.name.lowercase().contains(query) || relativePath.lowercase().contains(query)) {
        results.add(buildJsonObject {
            put("type", JsonPrimitive("folder"))
            put("name", JsonPrimitive(dir.name + "/"))
            put("value", JsonPrimitive(relativePath))
            put("desc", JsonPrimitive(relativePath))
        })
    }
    for (child in dir.children) {
        if (child.isDirectory && results.size < MAX_RESULTS) {
            collectFolders(child, query, basePath, results)
        }
    }
}
```

Add `"folder" -> searchFolders(query)` to the `search()` when clause.

- [ ] **Step 2: Change JS sendMessage to send JSON mentions**

Replace the current `sendMessage()` in agent-chat.html with:

```javascript
function sendMessage() {
  var ta = document.getElementById('chat-input');
  var text = ta ? ta.value.trim() : '';
  if (!text && _mentions.length === 0) return;

  var sendBtn = document.getElementById('send-btn');
  if (sendBtn) sendBtn.disabled = true;
  ta.value = '';
  ta.style.height = 'auto';

  // Send text + structured mentions as JSON
  var payload = JSON.stringify({
    text: text,
    mentions: _mentions
  });

  _mentions = [];
  renderMentionPills();
  closeMentionDropdown();

  if (window._sendMessageWithMentions) {
    window._sendMessageWithMentions(payload);
  } else if (window._sendMessage) {
    // Fallback: prepend mentions as text
    var message = text;
    if (_mentions.length > 0) {
      var context = _mentions.map(function(m) { return '@' + m.type + ' ' + m.value; }).join(', ');
      message = '[Context: ' + context + ']\n\n' + text;
    }
    window._sendMessage(message);
  }
}
```

- [ ] **Step 3: Add `_sendMessageWithMentions` bridge to AgentCefPanel**

Add new query field:
```kotlin
private var sendMessageWithMentionsQuery: JBCefJSQuery? = null
var onSendMessageWithMentions: ((String, String) -> Unit)? = null  // (text, mentionsJson)
```

Create in `createBrowser()`:
```kotlin
sendMessageWithMentionsQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
    addHandler { payload ->
        try {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(payload).jsonObject
            val text = json["text"]?.jsonPrimitive?.content ?: ""
            val mentionsJson = json["mentions"]?.toString() ?: "[]"
            onSendMessageWithMentions?.invoke(text, mentionsJson)
        } catch (e: Exception) {
            // Fallback: treat entire payload as text
            onSendMessage?.invoke(payload)
        }
        JBCefJSQuery.Response("ok")
    }
}
```

Inject in `onLoadingStateChange`:
```kotlin
sendMessageWithMentionsQuery?.let { q ->
    val sendJs = q.inject("payload")
    js("window._sendMessageWithMentions = function(payload) { $sendJs }")
}
```

Dispose:
```kotlin
sendMessageWithMentionsQuery?.dispose(); sendMessageWithMentionsQuery = null
```

- [ ] **Step 4: Add delegation in AgentDashboardPanel**

```kotlin
fun setCefMentionCallbacks(
    onSendWithMentions: (String, String) -> Unit
) {
    cefPanel?.onSendMessageWithMentions = onSendWithMentions
}
```

- [ ] **Step 5: Compile and verify**

```bash
./gradlew :agent:compileKotlin -x verifyPlugin
```

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/resources/webview/agent-chat.html agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/MentionSearchProvider.kt
git commit -m "feat(agent-ui): send structured mentions JSON + @folder support

sendMessage() now sends text + mentions as JSON payload via separate bridge.
MentionSearchProvider supports folder search. Falls back to text prepend
if new bridge unavailable."
```

---

## Task 4: Wire MentionContextBuilder into AgentController

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`

- [ ] **Step 1: Create MentionContextBuilder instance**

Add field to AgentController:
```kotlin
private val mentionContextBuilder by lazy { MentionContextBuilder(project) }
```

- [ ] **Step 2: Wire the mention-aware send callback**

In `init`, add after existing `setCefActionCallbacks`:

```kotlin
dashboard.setCefMentionCallbacks(
    onSendWithMentions = { text, mentionsJson ->
        handleMessageWithMentions(text, mentionsJson)
    }
)
```

- [ ] **Step 3: Add handleMessageWithMentions method**

```kotlin
private fun handleMessageWithMentions(text: String, mentionsJson: String) {
    // Parse mentions
    val mentions = try {
        kotlinx.serialization.json.Json.parseToJsonElement(mentionsJson).jsonArray.map { el ->
            val obj = el.jsonObject
            MentionContextBuilder.Mention(
                type = obj["type"]?.jsonPrimitive?.content ?: "file",
                name = obj["name"]?.jsonPrimitive?.content ?: "",
                value = obj["value"]?.jsonPrimitive?.content ?: ""
            )
        }
    } catch (_: Exception) { emptyList() }

    // Build mention context (reads files, generates trees)
    if (mentions.isNotEmpty()) {
        scope.launch(Dispatchers.IO) {
            val context = mentionContextBuilder.buildContext(mentions)
            if (context != null) {
                session?.contextManager?.setMentionAnchor(
                    com.workflow.orchestrator.agent.api.dto.ChatMessage(
                        role = "system",
                        content = "<mentioned_context>\n$context</mentioned_context>"
                    )
                )
            } else {
                session?.contextManager?.setMentionAnchor(null)
            }
            // Now execute the task on the main flow
            withContext(Dispatchers.Main) {
                executeTask(text)
            }
        }
    } else {
        // No mentions — clear any previous anchor and send normally
        session?.contextManager?.setMentionAnchor(null)
        executeTask(text)
    }
}
```

- [ ] **Step 4: Update the existing `executeTask` to show mentions in the display message**

In `executeTask()`, when calling `dashboard.appendUserMessage(task)`, the user sees their text. The mention pills are already shown in the JCEF input. No change needed — the pills are rendered by JS before send, and the display message is just the text.

However, for the display we should show what was mentioned. In `handleMessageWithMentions`, before calling `executeTask(text)`, show the user message with mention info:

```kotlin
// Display the user's message (mentions are visible as pills in the input bar)
val displayText = if (mentions.isNotEmpty()) {
    val mentionList = mentions.joinToString(", ") { "@${it.name}" }
    "$text\n\n_Mentioned: $mentionList_"
} else text
```

Actually, keep it simple — just pass `text` to `executeTask()`. The pills are already visible in the UI. The LLM context anchor handles the content injection silently.

- [ ] **Step 5: Compile and test**

```bash
./gradlew :agent:test --rerun --no-build-cache -x verifyPlugin 2>&1 | tail -10
```

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "feat(agent): wire MentionContextBuilder into message flow

handleMessageWithMentions() parses JSON mentions, builds context via
MentionContextBuilder (reads files, generates trees), injects as
mentionAnchor in ContextManager. Anchor survives compression."
```

---

## Task 5: Update Documentation

**Files:**
- Modify: `agent/CLAUDE.md`

- [ ] **Step 1: Update @ mention documentation**

Replace the existing @ mention line in CLAUDE.md with:

```markdown
- @ mention autocomplete: type @ to reference files, folders, symbols, tools, skills.
  @file reads content via Document API (sees unsaved changes) and injects into LLM context.
  @folder injects directory tree. Content stored as compression-proof mentionAnchor.
  Budget: 500 lines / 20K per file, 50K total.
```

- [ ] **Step 2: Run all tests and verify**

```bash
./gradlew :agent:test --rerun --no-build-cache -x verifyPlugin
./gradlew verifyPlugin
```

- [ ] **Step 3: Commit**

```bash
git add agent/CLAUDE.md
git commit -m "docs: update @ mention docs with content injection behavior"
```

---

## Verification

Manual testing in `runIde`:
1. Type `@` → pick File → select `LoginService.kt` → send message → verify LLM responds with knowledge of file content (without calling read_file)
2. Type `@` → pick Folder → select `src/main/` → send → verify LLM knows directory structure
3. Type `@` → pick Symbol → type `LoginService` → select → verify LLM references the class
4. Type `@` → pick Tool → select `search_code` → send "search for auth bugs" → verify LLM uses search_code
5. Select 3+ files → verify total context is capped at 50K
6. Send a long conversation → verify mention anchor survives compression
7. New message without mentions → verify mention anchor is cleared
