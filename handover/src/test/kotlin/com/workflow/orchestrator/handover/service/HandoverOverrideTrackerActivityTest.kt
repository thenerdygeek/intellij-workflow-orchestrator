package com.workflow.orchestrator.handover.service

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HandoverOverrideTrackerActivityTest {

    @BeforeEach fun setUp() = mockkStatic(HandoverOverrideTracker::class).let { Unit }
    @AfterEach  fun tearDown() = unmockkStatic(HandoverOverrideTracker::class)

    @Test
    fun `execute forces tracker getInstance`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val tracker = mockk<HandoverOverrideTracker>(relaxed = true)
        every { HandoverOverrideTracker.getInstance(project) } returns tracker

        HandoverOverrideTrackerActivity().execute(project)

        verify { HandoverOverrideTracker.getInstance(project) }
    }
}
