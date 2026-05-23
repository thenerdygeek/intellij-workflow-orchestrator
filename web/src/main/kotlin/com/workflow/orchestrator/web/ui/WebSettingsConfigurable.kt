package com.workflow.orchestrator.web.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.*
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.getWebAllowlist
import com.workflow.orchestrator.core.settings.setWebAllowlist
import com.workflow.orchestrator.web.service.search.BraveProvider
import com.workflow.orchestrator.web.service.search.CustomHttpProvider
import com.workflow.orchestrator.web.service.search.SearXNGProvider
import okhttp3.OkHttpClient
import java.awt.CardLayout
import java.time.Duration
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings page **Tools ▸ Workflow Orchestrator ▸ Web**.
 *
 * Registered in [web-plugin.xml] as a child of `AgentParentConfigurable`.
 *
 * Layout (5 groups, in order):
 *  1. Top toggles — enable_web_fetch / enable_web_search / allow in plan mode
 *  2. Fetch — Allowlist — [AllowlistEditorPanel] + unlisted-domain policy + approval timeout
 *  3. Fetch — Content limits — bytes cap, text cap, timeouts, HTTPS, IP literal, shortener
 *  4. Fetch — Sanitizer — brain ID, fail-closed toggle
 *  5. Search — Provider — SearXNG / Brave / Custom HTTP provider config
 */
class WebSettingsConfigurable(private val project: Project) : Configurable {

    private val settings: PluginSettings get() = project.service()
    private val connSettings: ConnectionSettings get() = ConnectionSettings.getInstance()
    private val credentialStore = CredentialStore()

    // Sub-panel that manages the allowlist table
    private var allowlistPanel: AllowlistEditorPanel? = null

    // DialogPanel created by the Kotlin UI DSL — drives isModified/apply/reset for bound fields
    private var dialogPanel: com.intellij.openapi.ui.DialogPanel? = null

    // ── Deferred API key save (same pattern as ConnectionsConfigurable) ──────
    /** API key typed into the password field — saved to PasswordSafe on apply(). */
    private var pendingWebSearchApiKey: String? = null
    private var isInitializing = true

    // Password field refs (needed for reset() to repopulate from PasswordSafe)
    private var braveApiKeyField: JBPasswordField? = null
    private var customApiKeyField: JBPasswordField? = null

    override fun getDisplayName(): String = "Web"

    // ── createComponent ──────────────────────────────────────────────────────

    override fun createComponent(): JComponent {
        isInitializing = true
        val ap = AllowlistEditorPanel().also { allowlistPanel = it }
        ap.loadEntries(settings.getWebAllowlist())

        // ── Provider-specific sub-panels (CardLayout) ────────────────────────
        // Each sub-panel is a JPanel whose visibility is toggled by the provider dropdown.
        // We use a simple CardLayout approach because the Kotlin UI DSL's .visibleIf() is
        // not available in the version this project targets (2025.1).

        val cardContainer = JPanel(CardLayout())
        val nonePanel = JPanel().also { it.isOpaque = false }
        val searxngPanel = buildSearXNGPanel()
        val bravePanel = buildBravePanel()
        val customPanel = buildCustomPanel()

        cardContainer.add(nonePanel, "NONE")
        cardContainer.add(searxngPanel, "SEARXNG")
        cardContainer.add(bravePanel, "BRAVE")
        cardContainer.add(customPanel, "CUSTOM_HTTP")

        fun showCard(providerType: String) {
            (cardContainer.layout as CardLayout).show(cardContainer, providerType)
        }

        val inner = panel {
            // ── Group 1: Top toggles ──────────────────────────────────────────
            group("Web Tools") {
                row {
                    checkBox("Enable web_fetch tool")
                        .bindSelected(
                            { settings.state.enableWebFetch },
                            { settings.state.enableWebFetch = it }
                        )
                }
                row {
                    checkBox("Enable web_search tool")
                        .bindSelected(
                            { settings.state.enableWebSearch },
                            { settings.state.enableWebSearch = it }
                        )
                }
                row {
                    checkBox("Allow web tools in plan mode (default: off)")
                        .bindSelected(
                            { settings.state.webPlanModeAllow },
                            { settings.state.webPlanModeAllow = it }
                        )
                        .comment(
                            "When unchecked (default), the agent cannot call <code>web_fetch</code> or " +
                                "<code>web_search</code> while in plan mode. Enable only if you trust the " +
                                "agent to read external content without prior human approval."
                        )
                }
            }

            // ── Group 2: Fetch — Allowlist ────────────────────────────────────
            group("Fetch — Allowlist") {
                row {
                    comment(
                        "Domains listed here are approved for automatic fetch (no approval dialog). " +
                            "Use <code>*.example.com</code> to allow all subdomains."
                    )
                }
                row {
                    cell(ap)
                        .align(AlignX.FILL)
                        .resizableColumn()
                }.resizableRow()
                row("Unlisted domain policy:") {
                    comboBox(listOf("PROMPT", "REJECT"))
                        .bindItem(
                            { settings.state.webUnlistedPolicy ?: "PROMPT" },
                            { settings.state.webUnlistedPolicy = it ?: "PROMPT" }
                        )
                        .comment(
                            "<b>PROMPT</b> — show the approval dialog and let the user decide. " +
                                "<b>REJECT</b> — silently refuse any URL not on the allowlist."
                        )
                }
                row("Approval timeout (seconds):") {
                    intTextField(10..600)
                        .bindIntText(settings.state::webApprovalTimeoutSec)
                        .comment("How long to wait before auto-denying the approval dialog. Default: 60 s.")
                }
            }

            // ── Group 3: Fetch — Content limits ──────────────────────────────
            group("Fetch — Content Limits") {
                row("Max response bytes:") {
                    intTextField(1_024..10_485_760, 65_536)
                        .bindIntText(settings.state::webMaxBytes)
                        .comment("Bytes streamed before the fetch is aborted. Default: 256 KB.")
                }
                row("Max extracted text characters:") {
                    intTextField(1_000..1_000_000, 4_096)
                        .bindIntText(settings.state::webMaxExtractedChars)
                        .comment("Characters passed to the agent after sanitization. Default: 32 768.")
                }
                row("Connect timeout (seconds):") {
                    intTextField(1..120)
                        .bindIntText(settings.state::webConnectTimeoutSec)
                }
                row("Read timeout (seconds):") {
                    intTextField(1..300)
                        .bindIntText(settings.state::webReadTimeoutSec)
                }
                row {
                    checkBox("Require HTTPS (recommended)")
                        .bindSelected(
                            { settings.state.webRequireHttps },
                            { settings.state.webRequireHttps = it }
                        )
                        .comment("Reject plain <code>http://</code> URLs unless overridden per-domain via the allowlist.")
                }
                row {
                    checkBox("Allow raw IP literals (e.g. https://203.0.113.1/)")
                        .bindSelected(
                            { settings.state.webAllowIpLiteral },
                            { settings.state.webAllowIpLiteral = it }
                        )
                        .comment("Disabled by default — raw IP URLs bypass hostname-based SSRF heuristics.")
                }
                row {
                    checkBox("Resolve URL shorteners before showing the approval dialog")
                        .bindSelected(
                            { settings.state.webResolveShorteners },
                            { settings.state.webResolveShorteners = it }
                        )
                        .comment(
                            "When enabled, known shortener domains (bit.ly, t.co, etc.) are followed " +
                                "via a HEAD request and the destination URL is shown in the approval dialog."
                        )
                }
            }

            // ── Group 4: Fetch — Sanitizer ───────────────────────────────────
            group("Fetch — Sanitizer") {
                row("Sanitizer brain ID:") {
                    textField()
                        .columns(40)
                        .bindText(
                            { settings.state.webSanitizerBrainId ?: "" },
                            { settings.state.webSanitizerBrainId = it.trim() }
                        )
                        .comment(
                            "Brain (model) used for the sanitizer subagent that rewrites fetched content " +
                                "into neutral form before the main agent sees it. Leave blank to use the cheapest available brain."
                        )
                }
                row {
                    checkBox("Fail closed if the sanitizer subagent times out (recommended)")
                        .bindSelected(
                            { settings.state.webSanitizerFailClosed },
                            { settings.state.webSanitizerFailClosed = it }
                        )
                        .comment(
                            "When checked (default), a sanitizer timeout causes the entire fetch to fail " +
                                "with <code>SANITIZER_TIMEOUT</code>. When unchecked, the structurally-sanitized " +
                                "text is passed directly to the agent without LLM-level semantic filtering."
                        )
                }
            }

            // ── Group 5: Search — Provider ────────────────────────────────────
            group("Search — Provider") {
                row("Provider:") {
                    val providerItems = listOf("NONE", "SEARXNG", "BRAVE", "CUSTOM_HTTP")
                    comboBox(providerItems)
                        .bindItem(
                            { settings.state.webSearchProviderType ?: "NONE" },
                            { settings.state.webSearchProviderType = it ?: "NONE" }
                        )
                        .applyToComponent {
                            addActionListener {
                                showCard(selectedItem as? String ?: "NONE")
                            }
                        }
                        .comment(
                            "<b>None</b> — web_search disabled. " +
                                "<b>SearXNG</b> — self-hosted meta-search (no API key). " +
                                "<b>Brave</b> — Brave Search API (paid key required). " +
                                "<b>Custom HTTP</b> — any JSON endpoint."
                        )
                }
                row {
                    cell(cardContainer).align(AlignX.FILL)
                }
                separator()
                row("Max results:") {
                    intTextField(1..50)
                        .bindIntText(settings.state::webSearchMaxResults)
                        .comment("Maximum search results returned to the agent per query. Default: 10.")
                }
                row("Snippet max characters:") {
                    intTextField(50..4_000)
                        .bindIntText(settings.state::webSearchSnippetMaxChars)
                        .comment("Maximum characters kept per result snippet after sanitization. Default: 500.")
                }
            }
        }

        dialogPanel = inner

        // Show the current provider card after the dialog panel is created
        showCard(settings.state.webSearchProviderType ?: "NONE")

        isInitializing = false

        // Load API key from PasswordSafe in background (same pattern as ConnectionsConfigurable)
        loadApiKeyFromPasswordSafe()

        return inner
    }

    // ── Provider sub-panel builders ───────────────────────────────────────────

    private fun buildSearXNGPanel(): JPanel {
        val panel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 4))
        panel.isOpaque = false
        val inner = com.intellij.ui.dsl.builder.panel {
            row("SearXNG base URL:") {
                textField()
                    .columns(40)
                    .bindText(
                        { connSettings.state.webSearchSearxngUrl },
                        { connSettings.state.webSearchSearxngUrl = it.trim() }
                    )
                    .comment("e.g. http://localhost:8080 — no auth required for internal instances.")
                button("Test") {
                    val url = connSettings.state.webSearchSearxngUrl.trim()
                    val client = buildTestClient()
                    runBackgroundableTask("Testing SearXNG", project, false) {
                        val provider = SearXNGProvider(url, client)
                        val validResult = runBlockingCancellable { provider.validate() }
                        if (validResult.isFailure) {
                            invokeLater {
                                Messages.showErrorDialog(
                                    project,
                                    validResult.exceptionOrNull()?.message ?: "Validation failed",
                                    "SearXNG Test"
                                )
                            }
                            return@runBackgroundableTask
                        }
                        val searchResult = runBlockingCancellable { provider.search("test", 1) }
                        invokeLater {
                            if (searchResult.isSuccess) {
                                val hits = searchResult.getOrDefault(emptyList())
                                Messages.showInfoMessage(
                                    project,
                                    "SearXNG OK — returned ${hits.size} result(s).",
                                    "SearXNG Test"
                                )
                            } else {
                                Messages.showErrorDialog(
                                    project,
                                    searchResult.exceptionOrNull()?.message ?: "Search failed",
                                    "SearXNG Test"
                                )
                            }
                        }
                    }
                }
            }
        }
        panel.add(inner)
        return panel
    }

    private fun buildBravePanel(): JPanel {
        val panel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 4))
        panel.isOpaque = false
        var apiKeyFieldRef: JBPasswordField? = null
        val inner = com.intellij.ui.dsl.builder.panel {
            row("Brave Search URL:") {
                textField()
                    .columns(40)
                    .bindText(
                        { connSettings.state.webSearchBraveUrl },
                        { connSettings.state.webSearchBraveUrl = it.trim() }
                    )
                    .comment("Default: https://api.search.brave.com/res/v1/web/search")
            }
            row("API key:") {
                cell(JBPasswordField().also { f ->
                    apiKeyFieldRef = f
                    braveApiKeyField = f
                    f.columns = 40
                    f.addPropertyChangeListener("document") {
                        if (!isInitializing) {
                            pendingWebSearchApiKey = String(f.password)
                        }
                    }
                    // Also wire key listener for immediate change detection
                    f.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                        override fun insertUpdate(e: javax.swing.event.DocumentEvent) { onApiKeyChanged(f) }
                        override fun removeUpdate(e: javax.swing.event.DocumentEvent) { onApiKeyChanged(f) }
                        override fun changedUpdate(e: javax.swing.event.DocumentEvent) { onApiKeyChanged(f) }
                    })
                })
                .comment("Stored in OS keychain (PasswordSafe). Never written to project files.")
                button("Test") {
                    val url = connSettings.state.webSearchBraveUrl.trim()
                    val apiKey = apiKeyFieldRef?.let { String(it.password) }
                        ?: credentialStore.getToken(ServiceType.WEB_SEARCH)
                    val client = buildTestClient()
                    runBackgroundableTask("Testing Brave Search", project, false) {
                        val provider = BraveProvider(url, apiKey, client)
                        val validResult = runBlockingCancellable { provider.validate() }
                        if (validResult.isFailure) {
                            invokeLater {
                                Messages.showErrorDialog(
                                    project,
                                    validResult.exceptionOrNull()?.message ?: "Validation failed",
                                    "Brave Search Test"
                                )
                            }
                            return@runBackgroundableTask
                        }
                        val searchResult = runBlockingCancellable { provider.search("test", 1) }
                        invokeLater {
                            if (searchResult.isSuccess) {
                                val hits = searchResult.getOrDefault(emptyList())
                                Messages.showInfoMessage(
                                    project,
                                    "Brave Search OK — returned ${hits.size} result(s).",
                                    "Brave Search Test"
                                )
                            } else {
                                Messages.showErrorDialog(
                                    project,
                                    searchResult.exceptionOrNull()?.message ?: "Search failed",
                                    "Brave Search Test"
                                )
                            }
                        }
                    }
                }
            }
        }
        panel.add(inner)
        return panel
    }

    private fun buildCustomPanel(): JPanel {
        val panel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 4))
        panel.isOpaque = false
        var apiKeyFieldRef: JBPasswordField? = null
        val inner = com.intellij.ui.dsl.builder.panel {
            row("URL template:") {
                textField()
                    .columns(50)
                    .bindText(
                        { connSettings.state.webSearchCustomUrl },
                        { connSettings.state.webSearchCustomUrl = it.trim() }
                    )
                    .comment("Must contain <code>{query}</code>, e.g. <code>https://api.example.com/search?q={query}</code>")
            }
            row("Method:") {
                comboBox(listOf("GET", "POST"))
                    .bindItem(
                        { connSettings.state.webSearchCustomMethod.ifBlank { "GET" } },
                        { connSettings.state.webSearchCustomMethod = it ?: "GET" }
                    )
            }
            row("Auth header name:") {
                textField()
                    .columns(30)
                    .bindText(
                        { connSettings.state.webSearchCustomHeaderName },
                        { connSettings.state.webSearchCustomHeaderName = it.trim() }
                    )
                    .comment("e.g. <code>Authorization</code> or <code>X-Api-Key</code>. Leave blank for no auth header.")
            }
            row("Auth header value:") {
                cell(JBPasswordField().also { f ->
                    apiKeyFieldRef = f
                    customApiKeyField = f
                    f.columns = 40
                    f.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                        override fun insertUpdate(e: javax.swing.event.DocumentEvent) { onApiKeyChanged(f) }
                        override fun removeUpdate(e: javax.swing.event.DocumentEvent) { onApiKeyChanged(f) }
                        override fun changedUpdate(e: javax.swing.event.DocumentEvent) { onApiKeyChanged(f) }
                    })
                })
                .comment("Stored in OS keychain (PasswordSafe). Never written to project files.")
            }
            row("Results JSON path:") {
                textField()
                    .columns(30)
                    .bindText(
                        { connSettings.state.webSearchCustomResultsPath },
                        { connSettings.state.webSearchCustomResultsPath = it.trim() }
                    )
                    .comment("JSONPath to the results array, e.g. <code>$.results</code>")
            }
            row("Title JSON path:") {
                textField()
                    .columns(30)
                    .bindText(
                        { connSettings.state.webSearchCustomTitlePath },
                        { connSettings.state.webSearchCustomTitlePath = it.trim() }
                    )
                    .comment("JSONPath within each result item, e.g. <code>$.title</code>")
            }
            row("URL JSON path:") {
                textField()
                    .columns(30)
                    .bindText(
                        { connSettings.state.webSearchCustomUrlPath },
                        { connSettings.state.webSearchCustomUrlPath = it.trim() }
                    )
                    .comment("JSONPath within each result item, e.g. <code>$.url</code>")
            }
            row("Snippet JSON path:") {
                textField()
                    .columns(30)
                    .bindText(
                        { connSettings.state.webSearchCustomSnippetPath },
                        { connSettings.state.webSearchCustomSnippetPath = it.trim() }
                    )
                    .comment("JSONPath within each result item, e.g. <code>$.snippet</code>")
            }
            row {
                button("Test") {
                    val conn = connSettings.state
                    val apiKey = apiKeyFieldRef?.let { f ->
                        String(f.password).ifBlank { null }
                    } ?: credentialStore.getToken(ServiceType.WEB_SEARCH)
                    val headerName = conn.webSearchCustomHeaderName.takeIf { it.isNotBlank() }
                    val headerValue = apiKey?.takeIf { headerName != null }
                    val client = buildTestClient()
                    runBackgroundableTask("Testing Custom HTTP provider", project, false) {
                        val provider = CustomHttpProvider(
                            urlTemplate = conn.webSearchCustomUrl,
                            method = conn.webSearchCustomMethod,
                            headerName = headerName,
                            headerValue = headerValue,
                            resultsPath = conn.webSearchCustomResultsPath,
                            titlePath = conn.webSearchCustomTitlePath,
                            urlPath = conn.webSearchCustomUrlPath,
                            snippetPath = conn.webSearchCustomSnippetPath,
                            client = client,
                        )
                        val validResult = runBlockingCancellable { provider.validate() }
                        if (validResult.isFailure) {
                            invokeLater {
                                Messages.showErrorDialog(
                                    project,
                                    validResult.exceptionOrNull()?.message ?: "Validation failed",
                                    "Custom HTTP Test"
                                )
                            }
                            return@runBackgroundableTask
                        }
                        val searchResult = runBlockingCancellable { provider.search("test", 1) }
                        invokeLater {
                            if (searchResult.isSuccess) {
                                val hits = searchResult.getOrDefault(emptyList())
                                Messages.showInfoMessage(
                                    project,
                                    "Custom HTTP provider OK — returned ${hits.size} result(s).",
                                    "Custom HTTP Test"
                                )
                            } else {
                                Messages.showErrorDialog(
                                    project,
                                    searchResult.exceptionOrNull()?.message ?: "Search failed",
                                    "Custom HTTP Test"
                                )
                            }
                        }
                    }
                }
            }
        }
        panel.add(inner)
        return panel
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun onApiKeyChanged(field: JBPasswordField) {
        if (!isInitializing) {
            pendingWebSearchApiKey = String(field.password)
        }
    }

    /** Minimal OkHttpClient for one-off Test button probes. */
    private fun buildTestClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(15))
        .build()

    /** Load API key from PasswordSafe in background and push into the password field. */
    private fun loadApiKeyFromPasswordSafe() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val token = credentialStore.getToken(ServiceType.WEB_SEARCH) ?: return@executeOnPooledThread
            if (token.isNotBlank()) {
                invokeLater {
                    isInitializing = true
                    braveApiKeyField?.text = token
                    customApiKeyField?.text = token
                    isInitializing = false
                }
            }
        }
    }

    // ── Configurable lifecycle ────────────────────────────────────────────────

    override fun isModified(): Boolean {
        val panel = dialogPanel ?: return false
        if (panel.isModified()) return true
        if (pendingWebSearchApiKey != null) return true
        return allowlistPanel?.isModified ?: false
    }

    override fun apply() {
        dialogPanel?.apply()
        allowlistPanel?.let { ap ->
            settings.setWebAllowlist(ap.currentEntries())
            ap.loadEntries(settings.getWebAllowlist())   // re-snapshot after write
        }
        // Save API key to PasswordSafe on explicit Apply only
        pendingWebSearchApiKey?.let { key ->
            if (key.isNotBlank()) {
                credentialStore.storeToken(ServiceType.WEB_SEARCH, key)
            }
            pendingWebSearchApiKey = null
        }
    }

    override fun reset() {
        dialogPanel?.reset()
        allowlistPanel?.loadEntries(settings.getWebAllowlist())
        pendingWebSearchApiKey = null
        // Reload API key from PasswordSafe
        loadApiKeyFromPasswordSafe()
    }

    override fun disposeUIResources() {
        dialogPanel = null
        allowlistPanel = null
        braveApiKeyField = null
        customApiKeyField = null
    }
}
