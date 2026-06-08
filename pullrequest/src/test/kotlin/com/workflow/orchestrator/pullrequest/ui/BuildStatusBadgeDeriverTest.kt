package com.workflow.orchestrator.pullrequest.ui

import com.workflow.orchestrator.core.bitbucket.BitbucketBuildStatus
import com.workflow.orchestrator.core.ui.StatusColors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class BuildStatusBadgeDeriverTest {

    private fun status(state: String, url: String = "") =
        BitbucketBuildStatus(state = state, key = "k-$state", url = url)

    @Test
    fun `empty statuses show No builds with INFO color and no url`() {
        val s = BuildStatusBadgeDeriver.derive(emptyList())
        assertEquals("No builds", s.text)
        assertSame(StatusColors.INFO, s.color)
        assertNull(s.url)
    }

    @Test
    fun `any failed build wins over in-progress and successful`() {
        val s = BuildStatusBadgeDeriver.derive(
            listOf(
                status("SUCCESSFUL", url = "ok"),
                status("INPROGRESS", url = "run"),
                status("FAILED", url = "boom"),
            )
        )
        assertEquals("Build Failed", s.text)
        assertSame(StatusColors.ERROR, s.color)
        assertEquals("boom", s.url, "the FAILED build's url is chosen for click-to-open")
    }

    @Test
    fun `in-progress wins when there is no failure`() {
        val s = BuildStatusBadgeDeriver.derive(
            listOf(status("SUCCESSFUL", url = "ok"), status("INPROGRESS", url = "run"))
        )
        assertEquals("Building...", s.text)
        assertSame(StatusColors.LINK, s.color)
        assertEquals("run", s.url)
    }

    @Test
    fun `all successful shows Build Passed with the first url`() {
        val s = BuildStatusBadgeDeriver.derive(
            listOf(status("SUCCESSFUL", url = "first"), status("successful", url = "second"))
        )
        assertEquals("Build Passed", s.text)
        assertSame(StatusColors.SUCCESS, s.color)
        assertEquals("first", s.url)
    }

    @Test
    fun `mixed non-terminal states fall back to Build Unknown with the first url`() {
        val s = BuildStatusBadgeDeriver.derive(
            listOf(status("CANCELLED", url = "c"), status("SUCCESSFUL", url = "ok"))
        )
        assertEquals("Build Unknown", s.text)
        assertSame(StatusColors.INFO, s.color)
        assertEquals("c", s.url)
    }
}
