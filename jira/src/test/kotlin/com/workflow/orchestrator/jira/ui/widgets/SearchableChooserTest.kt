package com.workflow.orchestrator.jira.ui.widgets

import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/** Synchronous ui-runner: executes the block immediately on the calling thread.
 *  Avoids the IntelliJ EDT dispatcher which requires a running Application. */
private val syncRunner: (() -> Unit) -> Unit = { it() }

class SearchableChooserTest {

    @Test
    fun `coalesces typing within debounce window`() = runBlocking {
        val disposable = Disposer.newDisposable()
        val searches = AtomicInteger()
        val chooser = SearchableChooser<String>(
            disposable = disposable,
            debounceMs = 100,
            search = { q -> searches.incrementAndGet(); listOf("$q-a", "$q-b") },
            display = { it },
            multi = false,
            uiRunner = syncRunner
        )
        chooser.queryForTest("a")
        delay(30); chooser.queryForTest("ab")
        delay(30); chooser.queryForTest("abc")
        delay(250)
        // Only the final "abc" query should have reached the search function
        assertEquals(1, searches.get())
        Disposer.dispose(disposable)
    }

    @Test
    fun `each query after debounce window fires its own search`() = runBlocking {
        val disposable = Disposer.newDisposable()
        val searches = AtomicInteger()
        val chooser = SearchableChooser<String>(
            disposable = disposable,
            debounceMs = 50,
            search = { searches.incrementAndGet(); emptyList() },
            display = { it },
            multi = false,
            uiRunner = syncRunner
        )
        chooser.queryForTest("x")
        delay(150)   // first query settles
        chooser.queryForTest("y")
        delay(150)   // second query settles
        assertEquals(2, searches.get())
        Disposer.dispose(disposable)
    }

    @Test
    fun `multi mode accumulates selections`() = runBlocking {
        val disposable = Disposer.newDisposable()
        val items = listOf("alpha", "beta", "gamma")
        val chooser = SearchableChooser<String>(
            disposable = disposable,
            debounceMs = 50,
            search = { items },
            display = { it },
            multi = true,
            uiRunner = syncRunner
        )
        // Simulate external selection by directly adding to multiSelection
        chooser.multiSelection.add("alpha")
        chooser.multiSelection.add("gamma")
        assertEquals(2, chooser.multiSelection.size)
        assertEquals(setOf("alpha", "gamma"), chooser.multiSelection.toSet())
        Disposer.dispose(disposable)
    }
}
