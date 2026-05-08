package com.workflow.orchestrator.handover.ui.tabs

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.handover.service.TimeTrackingService
import com.workflow.orchestrator.handover.ui.cards.CopyrightFixCard
import com.workflow.orchestrator.handover.ui.cards.TimeLogCard
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Container

class ActionsTabTest {

    private fun walkClasses(c: Container): List<Class<*>> = buildList {
        for (i in 0 until c.componentCount) {
            val child = c.getComponent(i)
            add(child::class.java)
            if (child is Container) addAll(walkClasses(child))
        }
    }

    // TimeLogCard calls project.getService(TimeTrackingService::class.java) at field init.
    // TimeTrackingService has a @TestOnly no-arg constructor, so we stub getService to return
    // a real instance rather than a generic Object mock (which causes ClassCastException).
    private fun makeProject(): Project = mockk<Project>(relaxed = true).apply {
        every { getService(TimeTrackingService::class.java) } returns TimeTrackingService()
    }

    @Test
    fun `mounts CopyrightFixCard and TimeLogCard`() {
        val tab = ActionsTab(makeProject())
        val classes = walkClasses(tab)
        assertTrue(classes.any { it == CopyrightFixCard::class.java }, "CopyrightFixCard not mounted")
        assertTrue(classes.any { it == TimeLogCard::class.java }, "TimeLogCard not mounted")
    }
}
