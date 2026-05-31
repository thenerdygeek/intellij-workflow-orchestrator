package com.workflow.orchestrator.automation.service

import com.workflow.orchestrator.automation.model.BaselineDiagnostics
import com.workflow.orchestrator.automation.model.BaselineLoadResult
import com.workflow.orchestrator.automation.model.BaselineRun
import com.workflow.orchestrator.automation.model.RegistryStatus
import com.workflow.orchestrator.automation.model.TagEntry
import com.workflow.orchestrator.automation.model.TagSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class BaselineCacheServiceTest {

    private fun sampleResult(planKey: String, buildNumber: Int): BaselineLoadResult {
        val run = BaselineRun(
            buildNumber = buildNumber,
            resultKey = "$planKey-$buildNumber",
            dockerTags = mapOf("svc-a" to "1.0.0", "svc-b" to "2.0.0"),
            releaseTagCount = 2,
            totalServices = 2,
            successfulStages = 3,
            failedStages = 0,
            triggeredAt = Instant.parse("2026-05-11T10:00:00Z"),
            score = 35
        )
        val tags = run.dockerTags.map { (svc, tag) ->
            TagEntry(svc, tag, TagSource.BASELINE, RegistryStatus.UNKNOWN, false)
        }
        return BaselineLoadResult(
            tags = tags,
            selectedBuild = run,
            diagnostics = BaselineDiagnostics(1, 1, 1, null, emptyList()),
            allRanked = listOf(run)
        )
    }

    @Test
    fun `put then get round-trips`(@TempDir tmp: Path) = runTest {
        val svc = BaselineCacheService.forTesting(tmp.toFile())
        val original = sampleResult("PROJ-A", 100)
        svc.put("PROJ-A", original)

        val readBack = svc.get("PROJ-A")
        assertNotNull(readBack)
        assertEquals(100, readBack!!.selectedBuild!!.buildNumber)
        assertEquals(mapOf("svc-a" to "1.0.0", "svc-b" to "2.0.0"),
            readBack.selectedBuild!!.dockerTags)
    }

    @Test
    fun `get returns null for unknown planKey`(@TempDir tmp: Path) = runTest {
        val svc = BaselineCacheService.forTesting(tmp.toFile())
        assertNull(svc.get("PROJ-DOES-NOT-EXIST"))
    }

    @Test
    fun `put survives service restart (disk persistence)`(@TempDir tmp: Path) = runTest {
        val svc1 = BaselineCacheService.forTesting(tmp.toFile())
        svc1.put("PROJ-A", sampleResult("PROJ-A", 200))

        // Simulate IDE restart by constructing a fresh service against the same dir.
        val svc2 = BaselineCacheService.forTesting(tmp.toFile())
        val readBack = svc2.get("PROJ-A")
        assertNotNull(readBack)
        assertEquals(200, readBack!!.selectedBuild!!.buildNumber)
    }

    @Test
    fun `put two suites keeps both entries`(@TempDir tmp: Path) = runTest {
        val svc = BaselineCacheService.forTesting(tmp.toFile())
        svc.put("PROJ-A", sampleResult("PROJ-A", 100))
        svc.put("PROJ-B", sampleResult("PROJ-B", 300))

        assertEquals(100, svc.get("PROJ-A")!!.selectedBuild!!.buildNumber)
        assertEquals(300, svc.get("PROJ-B")!!.selectedBuild!!.buildNumber)
    }

    @Test
    fun `invalidate removes the entry`(@TempDir tmp: Path) = runTest {
        val svc = BaselineCacheService.forTesting(tmp.toFile())
        svc.put("PROJ-A", sampleResult("PROJ-A", 100))
        svc.invalidate("PROJ-A")
        assertNull(svc.get("PROJ-A"))
    }

    @Test
    fun `corrupt json file is treated as empty cache, next put recovers`(@TempDir tmp: Path) = runTest {
        // Pre-poison the cache file.
        val cacheFile = tmp.resolve("baseline-cache.json").toFile()
        cacheFile.writeText("{ this is not valid json")

        val svc = BaselineCacheService.forTesting(tmp.toFile())
        assertNull(svc.get("PROJ-A"))

        svc.put("PROJ-A", sampleResult("PROJ-A", 400))
        assertEquals(400, svc.get("PROJ-A")!!.selectedBuild!!.buildNumber)

        // And the file is now valid JSON again.
        val freshSvc = BaselineCacheService.forTesting(tmp.toFile())
        assertEquals(400, freshSvc.get("PROJ-A")!!.selectedBuild!!.buildNumber)
    }

    @Test
    fun `unknown schema version is ignored, cache is empty`(@TempDir tmp: Path) = runTest {
        val cacheFile = tmp.resolve("baseline-cache.json").toFile()
        cacheFile.writeText("""{"version":999,"entries":{}}""")

        val svc = BaselineCacheService.forTesting(tmp.toFile())
        assertNull(svc.get("PROJ-A"))
    }

    @Test
    fun `latest write wins on disk under concurrent puts`(@TempDir tmp: Path) = runDispatchedTest {
        val svc = BaselineCacheService.forTesting(tmp.toFile())

        // Fire many concurrent puts for the same key with increasing build numbers.
        // Whatever order they reach the disk-write step in, the highest-versioned
        // snapshot (the last to mutate the map) must be the one persisted.
        val jobs = (1..50).map { n ->
            launch { svc.put("PROJ-A", sampleResult("PROJ-A", n)) }
        }
        jobs.forEach { it.join() }

        // In-memory state reflects some put; the last mutation under the lock wins.
        val inMemory = svc.get("PROJ-A")!!.selectedBuild!!.buildNumber

        // Reload from disk: the persisted state must match the latest in-memory
        // state — i.e. no stale snapshot clobbered a newer one.
        val reloaded = BaselineCacheService.forTesting(tmp.toFile())
        val onDisk = reloaded.get("PROJ-A")!!.selectedBuild!!.buildNumber

        assertEquals(inMemory, onDisk,
            "Disk must hold the latest in-memory snapshot, not a stale one")
    }
}

private fun runDispatchedTest(body: suspend CoroutineScope.() -> Unit) =
    // Use a real multi-threaded dispatcher (not runTest's single virtual-time
    // scheduler) so put()s genuinely interleave at the disk-write step.
    runBlocking(Dispatchers.Default, body)
