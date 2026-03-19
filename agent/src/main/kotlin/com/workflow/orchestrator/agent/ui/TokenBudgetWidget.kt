package com.workflow.orchestrator.agent.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JPanel
import javax.swing.JProgressBar

class TokenBudgetWidget : JPanel(BorderLayout()) {

    private val progressBar = JProgressBar(0, 100).apply {
        isStringPainted = true
    }
    private val label = JBLabel("Budget: 150K  ")

    init {
        toolTipText = "Token budget \u2014 shows how much of the context window has been used"
        border = JBUI.Borders.empty(4, 8)
        add(label, BorderLayout.WEST)
        add(progressBar, BorderLayout.CENTER)
        update(0, 0)
    }

    fun update(usedTokens: Int, maxTokens: Int) {
        if (maxTokens <= 0) {
            label.text = "Budget: 150K  "
            progressBar.value = 0
            progressBar.string = ""
            progressBar.isVisible = false
            return
        }
        progressBar.isVisible = true
        val percent = (usedTokens * 100 / maxTokens).coerceIn(0, 100)
        progressBar.value = percent
        progressBar.string = "$percent%"
        label.text = "Tokens: ${formatTokens(usedTokens)} / ${formatTokens(maxTokens)}  "

        progressBar.foreground = when {
            percent < 60 -> JBColor(Color(0, 150, 0), Color(0, 180, 0))
            percent < 80 -> JBColor(Color(200, 150, 0), Color(220, 180, 0))
            else -> JBColor(Color(200, 0, 0), Color(220, 50, 50))
        }
    }

    private fun formatTokens(tokens: Int): String = when {
        tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0)
        tokens >= 1_000 -> "%.1fK".format(tokens / 1_000.0)
        else -> tokens.toString()
    }
}
