package com.workflow.orchestrator.handover.service

import com.workflow.orchestrator.handover.model.HandoverTemplateAction
import com.workflow.orchestrator.handover.model.HandoverTemplateOrigin
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClasspathBundledLoaderTest {

    @Test
    fun `loads the 4 bundled templates from classpath`() {
        val list = ClasspathBundledLoader.load()
        val ids = list.map { it.id }.toSet()
        assertTrue(ids.contains("jira/standard-closure"), "missing jira/standard-closure: $ids")
        assertTrue(ids.contains("jira/hotfix"), "missing jira/hotfix: $ids")
        assertTrue(ids.contains("email/qa-handover"), "missing email/qa-handover: $ids")
        assertTrue(ids.contains("email/release-notes"), "missing email/release-notes: $ids")
    }

    @Test
    fun `bundled templates are flagged with BUNDLED origin`() {
        val list = ClasspathBundledLoader.load()
        assertTrue(list.isNotEmpty(), "expected bundled templates")
        assertTrue(list.all { it.origin == HandoverTemplateOrigin.BUNDLED })
    }

    @Test
    fun `actions are correctly assigned by directory`() {
        val list = ClasspathBundledLoader.load()
        val byAction = list.groupBy { it.action }
        assertTrue(byAction[HandoverTemplateAction.JIRA].orEmpty().isNotEmpty())
        assertTrue(byAction[HandoverTemplateAction.EMAIL].orEmpty().isNotEmpty())
        assertTrue(list.filter { it.id.startsWith("jira/") }.all { it.action == HandoverTemplateAction.JIRA })
        assertTrue(list.filter { it.id.startsWith("email/") }.all { it.action == HandoverTemplateAction.EMAIL })
    }
}
