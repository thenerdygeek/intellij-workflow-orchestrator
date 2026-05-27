package com.workflow.orchestrator.bamboo.api

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.intellij.testFramework.LoggedErrorProcessorEnabler
import org.junit.jupiter.api.extension.ExtendWith

/**
 * getLatestResult must always issue exactly ONE /result/{key}/latest request with the
 * stages expand and NEVER a /branch/ segment — for keys of every shape, including
 * PROJ-PLAN138 (the regressed branch-plan key) and PROJ-BUILD2 (master ending in digit).
 * The /branch/{name}/latest form is deleted (probe: 404-prone, absent from 10.2 swagger).
 */
@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class BambooApiClientLatestResultTest {
    private lateinit var server: MockWebServer
    private lateinit var client: BambooApiClient

    @BeforeEach fun setUp() {
        server = MockWebServer(); server.start()
        client = BambooApiClient(
            baseUrl = server.url("").toString().trimEnd('/'),
            tokenProvider = { "t" },
            connectTimeoutSeconds = 5,
            readTimeoutSeconds = 5,
        )
    }
    @AfterEach fun tearDown() { server.shutdown() }

    private suspend fun assertDirectLatest(key: String) {
        server.enqueue(MockResponse().setBody("""{"buildNumber":42,"state":"Successful","stages":{"stage":[]}}"""))
        client.getLatestResult(key)
        val path = server.takeRequest().path!!
        assertFalse(path.contains("/branch/"), "must not contain /branch/: $path")
        assertTrue(path.contains("/result/$key/latest"), "must hit /result/$key/latest: $path")
        assertTrue(path.contains("expand=stages.stage.results.result"), "must request stages expand: $path")
    }

    @Test fun `branch plan key PROJ-PLAN138 uses direct latest`() = runTest { assertDirectLatest("PROJ-PLAN138") }
    @Test fun `master key ending in digit PROJ-BUILD2 uses direct latest`() = runTest { assertDirectLatest("PROJ-BUILD2") }
    @Test fun `plain master key PROJ-PLAN uses direct latest`() = runTest { assertDirectLatest("PROJ-PLAN") }
}
