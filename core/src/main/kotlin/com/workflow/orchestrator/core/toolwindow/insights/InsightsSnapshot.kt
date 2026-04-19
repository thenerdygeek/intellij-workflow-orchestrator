package com.workflow.orchestrator.core.toolwindow.insights

import com.workflow.orchestrator.core.model.insights.SessionRecord
import com.workflow.orchestrator.core.services.InsightsStats

internal data class InsightsSnapshot(
    val today: InsightsStats,
    val week: InsightsStats,
    val all: List<SessionRecord>,
)
