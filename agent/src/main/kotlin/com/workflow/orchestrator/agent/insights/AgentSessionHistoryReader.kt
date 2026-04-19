package com.workflow.orchestrator.agent.insights

import com.workflow.orchestrator.agent.session.MessageStateHandler
import com.workflow.orchestrator.core.model.insights.SessionRecord
import com.workflow.orchestrator.core.services.SessionHistoryReader
import java.io.File

class AgentSessionHistoryReader : SessionHistoryReader {
    override fun loadSessions(baseDir: File): List<SessionRecord> =
        MessageStateHandler.loadGlobalIndex(baseDir).map { item ->
            SessionRecord(
                id = item.id,
                ts = item.ts,
                task = item.task,
                tokensIn = item.tokensIn,
                tokensOut = item.tokensOut,
                totalCost = item.totalCost,
                modelId = item.modelId,
                isFavorited = item.isFavorited,
            )
        }
}
