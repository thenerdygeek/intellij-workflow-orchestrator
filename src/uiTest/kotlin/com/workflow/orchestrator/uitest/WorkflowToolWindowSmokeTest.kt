/*
 * WorkflowToolWindowSmokeTest — out-of-process Remote Robot smoke test.
 *
 * ============================================================================
 * (a) HOW TO RUN — this test CANNOT run on an unlicensed/headless machine.
 *     It requires IntelliJ IDEA *Ultimate* with a valid license AND a real (or
 *     virtual) display: a developer machine (Windows/macOS/Linux with a GUI),
 *     or CI under Xvfb. Two terminals, in order:
 *         Terminal 1:  ./gradlew runIdeForUiTests      # launches sandbox IDE + Robot Server on :8082
 *         Terminal 2:  ./gradlew uiTest                # runs THIS test against that IDE
 *     The IDE from terminal 1 must be fully started (project window visible)
 *     before terminal 2 is launched; @BeforeAll polls until it is responsive.
 *
 * (b) LOCATORS ARE BEST-GUESSES FOR 2025.1 — TUNE AGAINST THE LIVE TREE.
 *     The XPath locators below (StripeButton for the tool-window stripe button,
 *     and the content-tab labels) are best-effort for the IntelliJ 2025.1 Swing
 *     component tree and are NOT verified here (no license/display in the build
 *     environment). When the sandbox is running, open the live component tree at
 *         http://127.0.0.1:8082
 *     and adjust the XPaths / accessible names to match the actual hierarchy
 *     (class names, `accessiblename`, `text`, `tooltiptext` can all drift between
 *     platform builds). Treat the constants below as a starting point only.
 *
 * (c) THE AGENT TAB IS NOT INSPECTABLE BY REMOTE ROBOT.
 *     The Agent chat UI is a JCEF/Chromium (off-screen Chromium) surface, not
 *     native Swing, so Remote Robot's component tree cannot see inside it. Any
 *     verification of Agent-tab internals stays MANUAL — this smoke test only
 *     covers the six native Swing tabs (Sprint, PR, Build, Quality, Automation,
 *     Handover).
 * ============================================================================
 */
package com.workflow.orchestrator.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.waitFor
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Duration
import javax.imageio.ImageIO

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowToolWindowSmokeTest {

    private val remoteRobot = RemoteRobot("http://127.0.0.1:8082")

    /** Tabs the Workflow tool window is expected to expose (the Agent tab is JCEF — see header (c)). */
    private val expectedTabs = listOf("Sprint", "PR", "Build", "Quality", "Automation", "Handover")

    @BeforeAll
    fun waitForIde() {
        // Poll until the running IDE answers Remote Robot and an IdeFrame is on screen. A long
        // timeout absorbs first-run indexing / sandbox warm-up on the licensed machine.
        waitFor(Duration.ofMinutes(2), Duration.ofSeconds(2)) {
            remoteRobot.findAll<ComponentFixture>(byXpath("//div[@class='IdeFrameImpl']")).isNotEmpty()
        }
    }

    @Test
    fun workflowToolWindowExposesAllTabs() {
        // Click the "Workflow" tool-window stripe button to open the tool window. Searched from the
        // RemoteRobot root — @BeforeAll's waitForIde has already confirmed the IdeFrame is up.
        // BEST-GUESS locator (see header (b)) — stripe buttons are commonly `StripeButton` with the
        // tool-window display name as accessible text.
        val stripeButton = remoteRobot.find(
            ComponentFixture::class.java,
            byXpath("//div[@class='StripeButton' and @text='Workflow']"),
            Duration.ofSeconds(30),
        )
        stripeButton.click()

        // Wait for the tool window's content tabs to render, then assert each expected tab label
        // is present. ContentTabLabel is the usual class for tabbed tool-window headers; tune live.
        waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1)) {
            remoteRobot.findAll<ComponentFixture>(byXpath("//div[@class='ContentTabLabel']")).isNotEmpty()
        }

        val tabLabels = remoteRobot
            .findAll<ComponentFixture>(byXpath("//div[@class='ContentTabLabel']"))
            .flatMap { it.findAllText().map { text -> text.text.trim() } }

        expectedTabs.forEach { expected ->
            assertTrue(
                tabLabels.any { it.equals(expected, ignoreCase = true) },
                "Expected Workflow tool-window tab '$expected' not found; visible tab labels = $tabLabels",
            )
        }

        // Persist a screenshot for post-run inspection / CI artifact upload. Diagnostic only — a
        // screenshot I/O failure must never mask an otherwise-passing test, so swallow + log it.
        try {
            val screenshotDir = File("build/ui-test-screenshots").apply { mkdirs() }
            ImageIO.write(remoteRobot.getScreenshot(), "png", File(screenshotDir, "workflow-tool-window.png"))
        } catch (e: Exception) {
            println("UI smoke: screenshot save failed (non-fatal): $e")
        }
    }
}
