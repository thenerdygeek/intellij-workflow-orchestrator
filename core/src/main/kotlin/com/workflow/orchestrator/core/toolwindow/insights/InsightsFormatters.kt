package com.workflow.orchestrator.core.toolwindow.insights

internal object InsightsFormatters {

    fun formatTokenCount(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fK".format(n / 1_000.0)
        else -> n.toString()
    }

    fun formatTokenPair(tokensIn: Long, tokensOut: Long): String {
        val inStr = formatTokenCount(tokensIn)
        val outStr = formatTokenCount(tokensOut)
        return "$inStr↑ / $outStr↓"
    }

    fun formatCost(costUsd: Double, hasRealData: Boolean): String = when {
        !hasRealData || costUsd == 0.0 && !hasRealData -> "—"
        else -> "≈ $%.2f".format(costUsd)
    }
}
