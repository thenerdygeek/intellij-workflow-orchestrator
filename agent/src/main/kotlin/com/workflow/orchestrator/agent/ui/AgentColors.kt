package com.workflow.orchestrator.agent.ui

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Color palette for the agent UI — VS Code Dark+ inspired (dark), neutral light (light).
 * All colors have light and dark variants via JBColor.
 *
 * Color-coded tool badges are the defining visual pattern of S-tier agent UIs.
 */
object AgentColors {
    // ── Panel backgrounds ── (Dark: VS Code Dark+ warm neutrals)
    val panelBg = JBColor(Color(0xFFFFFF), Color(0x1E1E1E))
    val userMsgBg = JBColor(Color(0xF1F5F9), Color(0x2D2D30))
    val agentMsgBg = JBColor(Color(0xFFFFFF), Color(0x1E1E1E)) // Same as panel
    val toolCallBg = JBColor(Color(0xF8FAFC), Color(0x252526))
    val codeBg = JBColor(Color(0xF1F5F9), Color(0x1E1E1E))
    val thinkingBg = JBColor(Color(0xF9FAFB), Color(0x252526))

    // ── Tool badge backgrounds ──
    val badgeRead = JBColor(Color(0xDBEAFE), Color(0x1A3A5C))
    val badgeWrite = JBColor(Color(0xDCFCE7), Color(0x1E3A1E))
    val badgeEdit = JBColor(Color(0xFEF3C7), Color(0x3D3017))
    val badgeCmd = JBColor(Color(0xFEE2E2), Color(0x3D1717))
    val badgeSearch = JBColor(Color(0xCFFAFE), Color(0x17333D))
    val badgeThinking = JBColor(Color(0xF3F4F6), Color(0x252526))

    // ── Tool badge text ──
    val badgeReadText = JBColor(Color(0x2563EB), Color(0x569CD6))
    val badgeWriteText = JBColor(Color(0x16A34A), Color(0x6A9955))
    val badgeEditText = JBColor(Color(0xD97706), Color(0xDCDCAA))
    val badgeCmdText = JBColor(Color(0xDC2626), Color(0xF44747))
    val badgeSearchText = JBColor(Color(0x0891B2), Color(0x4EC9B0))

    // ── Tool accent (left border) ──
    val accentRead = JBColor(Color(0x3B82F6), Color(0x569CD6))
    val accentWrite = JBColor(Color(0x22C55E), Color(0x6A9955))
    val accentEdit = JBColor(Color(0xF59E0B), Color(0xDCDCAA))
    val accentCmd = JBColor(Color(0xEF4444), Color(0xF44747))
    val accentSearch = JBColor(Color(0x06B6D4), Color(0x4EC9B0))

    // ── Diff colors ──
    val diffAddBg = JBColor(Color(0xDCFCE7), Color(0x1E3A1E))
    val diffRemBg = JBColor(Color(0xFEE2E2), Color(0x3D1717))
    val diffAddText = JBColor(Color(0x166534), Color(0xB5CEA8))
    val diffRemText = JBColor(Color(0x991B1B), Color(0xF4A5A5))

    // ── Text colors ──
    val primaryText = JBColor(Color(0x1E293B), Color(0xD4D4D4))
    val secondaryText = JBColor(Color(0x475569), Color(0x9D9D9D))
    val mutedText = JBColor(Color(0x64748B), Color(0x6A6A6A))
    val linkText = JBColor(Color(0x2563EB), Color(0x569CD6))

    // ── Status colors ──
    val success = JBColor(Color(0x16A34A), Color(0x6A9955))
    val error = JBColor(Color(0xDC2626), Color(0xF44747))
    val warning = JBColor(Color(0xD97706), Color(0xDCDCAA))

    // ── Borders ──
    val border = JBColor(Color(0xE2E8F0), Color(0x3C3C3C))
    val subtleBorder = JBColor(Color(0xF1F5F9), Color(0x2D2D30))

    fun hex(color: Color): String = "#${Integer.toHexString(color.rgb and 0xFFFFFF).padStart(6, '0')}"
}
