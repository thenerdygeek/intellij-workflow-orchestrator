# Attachment Enhancements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add image thumbnails, download service, "Download All" button, and per-attachment context menu (Open in Editor / Open in Browser) to the ticket detail panel.

**Architecture:** New `AttachmentDownloadService` in the Jira service layer returns rich `AttachmentDownloadResult` via `ToolResult<T>` pattern. Thumbnails lazy-loaded from Jira's thumbnail URL. UI enhanced with context menu and download actions. Service is reusable by other modules.

**Tech Stack:** Kotlin, IntelliJ Platform SDK, Jira REST API, OkHttp (existing HTTP client)

---

## File Structure

### New Files
| File | Responsibility |
|------|---------------|
| `jira/src/.../service/AttachmentDownloadService.kt` | Download attachments (single/batch), return rich result with file path, name, size, content type |
| `jira/src/.../model/AttachmentDownloadResult.kt` | Data class for download result (path, filename, size, mimeType, etc.) |

### Modified Files
| File | Changes |
|------|---------|
| `jira/src/.../api/dto/JiraDtos.kt` | Add `thumbnail` field to `JiraAttachment` |
| `jira/src/.../ui/TicketDetailPanel.kt` | Rewrite `addAttachments()` — thumbnails, context menu, download all button |

---

## Dependency Graph

```
Task 1 (DTO + Download Service) — foundation
  ├── Task 2 (Image thumbnails)
  └── Task 3 (Download All + Context menu)
```

---

### Task 1: AttachmentDownloadService + DTO Update

**Files:**
- Create: `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/AttachmentDownloadService.kt`
- Create: `jira/src/main/kotlin/com/workflow/orchestrator/jira/model/AttachmentDownloadResult.kt`
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/api/dto/JiraDtos.kt`

- [ ] **Step 1: Add thumbnail field to JiraAttachment DTO**

In `JiraDtos.kt`, add `thumbnail` to the existing `JiraAttachment`:

```kotlin
@Serializable
data class JiraAttachment(
    val id: String,
    val filename: String,
    val author: JiraUser? = null,
    val mimeType: String? = null,
    val size: Long = 0,
    val created: String? = null,
    val content: String = "",
    val thumbnail: String? = null    // Jira-generated thumbnail URL (images only)
)
```

- [ ] **Step 2: Create AttachmentDownloadResult**

```kotlin
package com.workflow.orchestrator.jira.model

import java.io.File

data class AttachmentDownloadResult(
    val file: File,
    val filename: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val attachmentId: String,
    val sourceUrl: String
) {
    val isImage: Boolean get() = mimeType?.startsWith("image/") == true
    val isText: Boolean get() = mimeType?.let {
        it.startsWith("text/") || it in listOf(
            "application/json", "application/xml", "application/javascript",
            "application/x-yaml", "application/x-sh"
        )
    } ?: false
}
```

- [ ] **Step 3: Create AttachmentDownloadService**

A service that downloads attachments from Jira using the authenticated HTTP client. Follows the same service layer pattern as other services (suspend funs, reusable).

```kotlin
package com.workflow.orchestrator.jira.service

class AttachmentDownloadService(
    private val project: Project
) {
    /**
     * Download a single attachment to a target directory.
     * Returns ToolResult with AttachmentDownloadResult on success.
     */
    suspend fun downloadAttachment(
        attachment: JiraAttachment,
        targetDir: File? = null   // null = system temp dir
    ): AttachmentDownloadResult?   // null on failure

    /**
     * Download all attachments to a user-selected directory.
     * Returns list of successful downloads + summary string.
     */
    suspend fun downloadAll(
        attachments: List<JiraAttachment>,
        targetDir: File
    ): Pair<List<AttachmentDownloadResult>, String>  // results + summary message

    /**
     * Download a thumbnail image (small preview).
     * Returns the image as a BufferedImage, or null on failure.
     */
    suspend fun downloadThumbnail(
        attachment: JiraAttachment
    ): BufferedImage?
}
```

Implementation details:
- Use the existing `HttpClientFactory.sharedPool` with auth interceptor (Bearer token) for authenticated downloads
- Get Jira URL and token from `ConnectionSettings` and `CredentialStore` (same as JiraApiClient)
- Download to `targetDir` or `Files.createTempFile()` for temp downloads
- `downloadThumbnail()` uses `attachment.thumbnail` URL, returns `ImageIO.read()` result
- Cache thumbnails in an LRU `ConcurrentHashMap<String, BufferedImage>` (keyed by attachment ID, max ~50 entries)
- All downloads on `Dispatchers.IO`

- [ ] **Step 4: Build and commit**

Run: `./gradlew :jira:compileKotlin`

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/
git commit -m "feat(jira): AttachmentDownloadService with rich result, thumbnail support, DTO update"
```

---

### Task 2: Image Thumbnails in Ticket Detail

**Files:**
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketDetailPanel.kt`

- [ ] **Step 1: Enhance addAttachments() with thumbnails**

Rewrite the attachment cards to show image thumbnails for image attachments:

For each attachment where `mimeType?.startsWith("image/") == true` and `thumbnail != null`:
1. Show a placeholder (gray box with loading icon) initially
2. Lazy-load the thumbnail via `AttachmentDownloadService.downloadThumbnail()`
3. On success, replace placeholder with scaled `ImageIcon` in a `JBLabel`
4. Thumbnail size: `JBUI.scale(80)` x `JBUI.scale(60)` (landscape aspect)

For non-image attachments, keep the existing icon + filename display.

Layout per attachment card:
```
Image attachments:
┌─────────────┐
│  [thumbnail] │  ← 80x60 scaled image
│  photo.png   │  ← filename
│  245 KB      │  ← size
└─────────────┘

Non-image attachments:
┌─────────────┐
│  [file icon] │  ← AllIcons based on mime type
│  report.pdf  │  ← filename
│  1.2 MB      │  ← size
└─────────────┘
```

Use `Dispatchers.IO` for thumbnail loading, `Dispatchers.EDT` for UI updates. Scale images to fit the thumbnail box while maintaining aspect ratio.

- [ ] **Step 2: Build and commit**

Run: `./gradlew :jira:compileKotlin`

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketDetailPanel.kt
git commit -m "feat(jira): inline image thumbnails for ticket attachments"
```

---

### Task 3: Download All Button + Context Menu

**Files:**
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketDetailPanel.kt`

- [ ] **Step 1: Add "Download All" button**

In the attachments section header row, add a "Download All" button (or link-styled label):

```kotlin
val downloadAllButton = JBLabel("Download All").apply {
    foreground = StatusColors.LINK
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    font = JBUI.Fonts.smallFont()
}
```

Place it in the header row: `"Attachments (3)    Download All"`

On click:
1. Show a directory picker via `FileChooser.chooseFile(FileChooserDescriptor(false, true, false, false, false, false), project, null)`
2. Call `attachmentDownloadService.downloadAll(attachments, selectedDir)`
3. Show progress (disable button, show "Downloading...")
4. On success, show notification: "Downloaded 3 attachments to /path"
5. On error, show error notification

- [ ] **Step 2: Add per-attachment context menu**

Add a right-click popup menu (or three-dot icon) on each attachment card with options:

```kotlin
val popupMenu = JPopupMenu().apply {
    add(JMenuItem("Open in Editor").apply {
        addActionListener {
            // Download to temp file via attachmentDownloadService.downloadAttachment()
            // Open in IntelliJ editor via FileEditorManager.getInstance(project).openFile(virtualFile, true)
            // Convert File to VirtualFile via LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        }
    })
    add(JMenuItem("Open in Browser").apply {
        addActionListener {
            BrowserUtil.browse(attachment.content)
        }
    })
    add(JMenuItem("Download").apply {
        addActionListener {
            // Single file download with directory picker
        }
    })
}
```

Trigger on right-click via `MouseAdapter.mousePressed/mouseReleased` checking `isPopupTrigger`.

Also add a small three-dot icon button (AllIcons.Actions.More) on each card for discoverability.

- [ ] **Step 3: Build and commit**

Run: `./gradlew :jira:compileKotlin`

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketDetailPanel.kt
git commit -m "feat(jira): download all button and per-attachment context menu with open in editor"
```

---

## Final Verification

- [ ] Run: `./gradlew buildPlugin`
- [ ] Run: `./gradlew :jira:test`
- [ ] Update `jira/CLAUDE.md` with new service and UI features
