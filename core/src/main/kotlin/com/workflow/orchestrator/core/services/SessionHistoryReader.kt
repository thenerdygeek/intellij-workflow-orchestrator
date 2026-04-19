package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.model.insights.SessionRecord
import java.io.File

interface SessionHistoryReader {
    fun loadSessions(baseDir: File): List<SessionRecord>
}
