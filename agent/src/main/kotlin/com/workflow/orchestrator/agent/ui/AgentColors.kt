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
    // ── Panel backgrounds ── (Light: VS Code Light+, Dark: VS Code Dark+)
    val panelBg = JBColor(Color(0xFFFFFF), Color(0x1E1E1E))
    val userMsgBg = JBColor(Color(0xF3F3F3), Color(0x2D2D30))
    val agentMsgBg = JBColor(Color(0xFFFFFF), Color(0x1E1E1E)) // Same as panel
    val toolCallBg = JBColor(Color(0xF8F8F8), Color(0x252526))
    val codeBg = JBColor(Color(0xF3F3F3), Color(0x1E1E1E))
    val thinkingBg = JBColor(Color(0xF8F8F8), Color(0x252526))

    // ── Tool badge backgrounds ──
    val badgeRead = JBColor(Color(0xD6ECFF), Color(0x1A3A5C))
    val badgeWrite = JBColor(Color(0xD4EDDA), Color(0x1E3A1E))
    val badgeEdit = JBColor(Color(0xFFF3CD), Color(0x3D3017))
    val badgeCmd = JBColor(Color(0xFDE2E2), Color(0x3D1717))
    val badgeSearch = JBColor(Color(0xD4F4F4), Color(0x17333D))
    val badgeThinking = JBColor(Color(0xF3F3F3), Color(0x252526))

    // ── Tool badge text ──
    val badgeReadText = JBColor(Color(0x0451A5), Color(0x569CD6))
    val badgeWriteText = JBColor(Color(0x1B7742), Color(0x6A9955))
    val badgeEditText = JBColor(Color(0x795E00), Color(0xDCDCAA))
    val badgeCmdText = JBColor(Color(0xCD3131), Color(0xF44747))
    val badgeSearchText = JBColor(Color(0x16825D), Color(0x4EC9B0))

    // ── Tool accent (left border) ──
    val accentRead = JBColor(Color(0x0451A5), Color(0x569CD6))
    val accentWrite = JBColor(Color(0x1B7742), Color(0x6A9955))
    val accentEdit = JBColor(Color(0x795E00), Color(0xDCDCAA))
    val accentCmd = JBColor(Color(0xCD3131), Color(0xF44747))
    val accentSearch = JBColor(Color(0x16825D), Color(0x4EC9B0))

    // ── Diff colors ──
    val diffAddBg = JBColor(Color(0xD4EDDA), Color(0x1E3A1E))
    val diffRemBg = JBColor(Color(0xFDE2E2), Color(0x3D1717))
    val diffAddText = JBColor(Color(0x1B7742), Color(0xB5CEA8))
    val diffRemText = JBColor(Color(0xCD3131), Color(0xF4A5A5))

    // ── Text colors ──
    val primaryText = JBColor(Color(0x1E1E1E), Color(0xD4D4D4))
    val secondaryText = JBColor(Color(0x616161), Color(0x9D9D9D))
    val mutedText = JBColor(Color(0x9E9E9E), Color(0x6A6A6A))
    val linkText = JBColor(Color(0x0451A5), Color(0x569CD6))

    // ── Status colors ──
    val success = JBColor(Color(0x1B7742), Color(0x6A9955))
    val error = JBColor(Color(0xCD3131), Color(0xF44747))
    val warning = JBColor(Color(0x795E00), Color(0xDCDCAA))

    // ── Borders ──
    val border = JBColor(Color(0xE0E0E0), Color(0x3C3C3C))
    val subtleBorder = JBColor(Color(0xF3F3F3), Color(0x2D2D30))

    fun hex(color: Color): String = "#${Integer.toHexString(color.rgb and 0xFFFFFF).padStart(6, '0')}"
}
