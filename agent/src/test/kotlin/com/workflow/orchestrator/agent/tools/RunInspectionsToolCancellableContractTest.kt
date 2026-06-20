package com.workflow.orchestrator.agent.tools

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class RunInspectionsToolCancellableContractTest {
    private val src = File(
        "src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/RunInspectionsTool.kt"
    ).readText()

    @Test
    fun `walk is under a suspend read-action and polls cancellation`() {
        assertTrue(src.contains("smartReadAction("), "must use the suspend read-action API")
        assertFalse(src.contains("executeSynchronously()"), "blocking read action must be removed")
        assertTrue(
            src.contains("checkCanceled()"),
            "visitElement must poll checkCanceled() so a coroutine cancel aborts the CPU-bound walk",
        )
    }

    @Test
    fun `inner per-inspection catch re-throws ProcessCanceledException before swallowing Exception`() {
        // The inner catch that silences per-inspection failures MUST NOT swallow PCE.
        // Assert that catch(ProcessCanceledException) appears AND appears BEFORE the inner
        // catch(_: Exception) so the dedicated typed re-throw guard is in place.
        val pceCatch = "catch (e: ProcessCanceledException)"
        val genericCatch = "catch (_: Exception)"

        assertTrue(
            src.contains(pceCatch),
            "inner catch must have a dedicated ProcessCanceledException re-throw guard",
        )
        assertTrue(
            src.contains(genericCatch),
            "inner catch (_: Exception) silencing per-inspection failures must still be present",
        )

        // Ordering: the PCE guard must appear before the swallowing catch so it takes priority
        val pceIdx = src.indexOf(pceCatch)
        val genericIdx = src.indexOf(genericCatch)
        assertTrue(
            pceIdx < genericIdx,
            "catch (e: ProcessCanceledException) must appear before catch (_: Exception) " +
                "so cancellation propagates rather than being swallowed",
        )
    }
}
