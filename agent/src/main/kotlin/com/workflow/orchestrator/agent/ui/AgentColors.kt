package com.workflow.orchestrator.agent.ui

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Color palette for the agent UI, based on research across Claude Code, Cursor,
 * Cline, Windsurf, and Augment Code. All colors have light and dark variants.
 *
 * Color-coded tool badges are the defining visual pattern of S-tier agent UIs.
 */
object AgentColors {
    // ── Panel backgrounds ──
    val panelBg = JBColor(Color(0xFFFFFF), Color(0x2B2D30))
    val userMsgBg = JBColor(Color(0xF1F5F9), Color(0x1E293B))
    val agentMsgBg = JBColor(Color(0xFFFFFF), Color(0x2B2D30)) // Same as panel
    val toolCallBg = JBColor(Color(0xF8FAFC), Color(0x1A1D23))
    val codeBg = JBColor(Color(0xF1F5F9), Color(0x1E1E2E))
    val thinkingBg = JBColor(Color(0xF9FAFB), Color(0x1F2937))

    // ── Tool badge backgrounds ──
    val badgeRead = JBColor(Color(0xDBEAFE), Color(0x1E3A5F))
    val badgeWrite = JBColor(Color(0xDCFCE7), Color(0x14532D))
    val badgeEdit = JBColor(Color(0xFEF3C7), Color(0x451A03))
    val badgeCmd = JBColor(Color(0xFEE2E2), Color(0x450A0A))
    val badgeSearch = JBColor(Color(0xCFFAFE), Color(0x083344))
    val badgeThinking = JBColor(Color(0xF3F4F6), Color(0x1F2937))

    // ── Tool badge text ──
    val badgeReadText = JBColor(Color(0x2563EB), Color(0x60A5FA))
    val badgeWriteText = JBColor(Color(0x16A34A), Color(0x4ADE80))
    val badgeEditText = JBColor(Color(0xD97706), Color(0xFBBF24))
    val badgeCmdText = JBColor(Color(0xDC2626), Color(0xF87171))
    val badgeSearchText = JBColor(Color(0x0891B2), Color(0x22D3EE))

    // ── Tool accent (left border) ──
    val accentRead = JBColor(Color(0x3B82F6), Color(0x3B82F6))
    val accentWrite = JBColor(Color(0x22C55E), Color(0x22C55E))
    val accentEdit = JBColor(Color(0xF59E0B), Color(0xF59E0B))
    val accentCmd = JBColor(Color(0xEF4444), Color(0xEF4444))
    val accentSearch = JBColor(Color(0x06B6D4), Color(0x06B6D4))

    // ── Diff colors ──
    val diffAddBg = JBColor(Color(0xDCFCE7), Color(0x14332A))
    val diffRemBg = JBColor(Color(0xFEE2E2), Color(0x3B1818))
    val diffAddText = JBColor(Color(0x166534), Color(0x86EFAC))
    val diffRemText = JBColor(Color(0x991B1B), Color(0xFCA5A5))

    // ── Text colors ──
    val primaryText = JBColor(Color(0x1E293B), Color(0xCBD5E1))
    val secondaryText = JBColor(Color(0x475569), Color(0x94A3B8))
    val mutedText = JBColor(Color(0x64748B), Color(0x6B7280))
    val linkText = JBColor(Color(0x2563EB), Color(0x60A5FA))

    // ── Status colors ──
    val success = JBColor(Color(0x16A34A), Color(0x22C55E))
    val error = JBColor(Color(0xDC2626), Color(0xEF4444))
    val warning = JBColor(Color(0xD97706), Color(0xF59E0B))

    // ── Borders ──
    val border = JBColor(Color(0xE2E8F0), Color(0x3F3F46))
    val subtleBorder = JBColor(Color(0xF1F5F9), Color(0x27272A))

    fun hex(color: Color): String = "#${Integer.toHexString(color.rgb and 0xFFFFFF).padStart(6, '0')}"
}
