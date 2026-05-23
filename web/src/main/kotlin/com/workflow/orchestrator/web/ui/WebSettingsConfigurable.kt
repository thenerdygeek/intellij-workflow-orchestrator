package com.workflow.orchestrator.web.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.*
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.getWebAllowlist
import com.workflow.orchestrator.core.settings.setWebAllowlist
import javax.swing.JComponent

/**
 * Settings page **Tools ▸ Workflow Orchestrator ▸ Web** — fetch sections only.
 *
 * Registered in [web-plugin.xml] as a child of `AgentParentConfigurable`.
 * Group 5 (Search — Provider) is a placeholder; it will be filled in by Task 22 (PR 2).
 *
 * Layout (5 groups, in order):
 *  1. Top toggles — enable_web_fetch / enable_web_search / allow in plan mode
 *  2. Fetch — Allowlist — [AllowlistEditorPanel] + unlisted-domain policy + approval timeout
 *  3. Fetch — Content limits — bytes cap, text cap, timeouts, HTTPS, IP literal, shortener
 *  4. Fetch — Sanitizer — brain ID, fail-closed toggle
 *  5. Search — Provider — placeholder label (PR 2)
 */
class WebSettingsConfigurable(private val project: Project) : Configurable {

    private val settings: PluginSettings get() = project.service()

    // Sub-panel that manages the allowlist table
    private var allowlistPanel: AllowlistEditorPanel? = null

    // DialogPanel created by the Kotlin UI DSL — drives isModified/apply/reset for bound fields
    private var dialogPanel: com.intellij.openapi.ui.DialogPanel? = null

    override fun getDisplayName(): String = "Web"

    // ── createComponent ──────────────────────────────────────────────────────

    override fun createComponent(): JComponent {
        val ap = AllowlistEditorPanel().also { allowlistPanel = it }
        ap.loadEntries(settings.getWebAllowlist())

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

            // ── Group 5: Search — Provider (PR 2 placeholder) ────────────────
            group("Search — Provider") {
                row {
                    cell(JBLabel("Search provider settings coming in PR 2."))
                }
            }
        }

        dialogPanel = inner
        return inner
    }

    // ── Configurable lifecycle ────────────────────────────────────────────────

    override fun isModified(): Boolean {
        val panel = dialogPanel ?: return false
        if (panel.isModified()) return true
        return allowlistPanel?.isModified ?: false
    }

    override fun apply() {
        dialogPanel?.apply()
        allowlistPanel?.let { ap ->
            settings.setWebAllowlist(ap.currentEntries())
            ap.loadEntries(settings.getWebAllowlist())   // re-snapshot after write
        }
    }

    override fun reset() {
        dialogPanel?.reset()
        allowlistPanel?.loadEntries(settings.getWebAllowlist())
    }

    override fun disposeUIResources() {
        dialogPanel = null
        allowlistPanel = null
    }
}
