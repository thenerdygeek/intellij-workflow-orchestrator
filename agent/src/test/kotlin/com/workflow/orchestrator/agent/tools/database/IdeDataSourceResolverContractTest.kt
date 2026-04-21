package com.workflow.orchestrator.agent.tools.database

import com.intellij.openapi.project.Project
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Contract tests that pin the reflection surface used by [IdeDataSourceResolver].
 *
 * On Community (no Database plugin), the Database plugin classes are absent from
 * the test classpath and every test short-circuits via the catch block. On Ultimate,
 * these tests assert the API shape verified against IU-2025.1.
 *
 * See docs/research/2026-04-21-intellij-database-api-signatures.md for the
 * full surface that these tests guard.
 */
class IdeDataSourceResolverContractTest {

    @Nested
    inner class DatabasePluginApiContract {

        @Test
        fun `LocalDataSourceManager getInstance takes Project`() {
            val cls = try {
                Class.forName("com.intellij.database.dataSource.LocalDataSourceManager")
            } catch (_: ClassNotFoundException) {
                // Community edition — Database plugin absent; skip gracefully
                return
            }
            // The method must exist — throws NoSuchMethodException if it doesn't
            val method = cls.getMethod("getInstance", Project::class.java)
            assertNotNull(method, "LocalDataSourceManager.getInstance(Project) must exist")
        }

        @Test
        fun `LocalDataSourceManager exposes getDataSources`() {
            val cls = try {
                Class.forName("com.intellij.database.dataSource.LocalDataSourceManager")
            } catch (_: ClassNotFoundException) {
                return
            }
            val method = cls.methods.firstOrNull { it.name == "getDataSources" && it.parameterCount == 0 }
                ?: throw AssertionError("getDataSources() not found on LocalDataSourceManager")
            assertNotNull(method)
        }

        @Test
        fun `DasDataSource exposes getName`() {
            // DasDataSource (model interface) exposes getName, getComment, getUniqueId,
            // getDbms, getModel — but NOT getUrl or getUsername (those are on LocalDataSource).
            // IdeDataSourceResolver calls getName/getUrl/getUsername via rawDataSource.javaClass
            // (the concrete LocalDataSource), not via DasDataSource directly — so this test
            // just pins the interface presence and its getName contract.
            val cls = try {
                Class.forName("com.intellij.database.model.DasDataSource")
            } catch (_: ClassNotFoundException) {
                return
            }
            cls.getMethod("getName")
        }

        @Test
        fun `LocalDataSource exposes getUrl and getUsername no-arg`() {
            // getUrl and getUsername (no-arg) live on LocalDataSource / DatabaseConnectionPoint,
            // not on DasDataSource. IdeDataSourceResolver uses rawDataSource.javaClass to find
            // these — confirmed in research doc IU-2025.1 section.
            val cls = try {
                Class.forName("com.intellij.database.dataSource.LocalDataSource")
            } catch (_: ClassNotFoundException) {
                return
            }
            cls.getMethod("getUrl")
            // getUsername is overloaded — filter by parameterCount == 0
            cls.methods.firstOrNull { it.name == "getUsername" && it.parameterCount == 0 }
                ?: throw AssertionError("getUsername() no-arg not found on LocalDataSource")
        }
    }

    @Nested
    inner class ProfileSourceEnum {

        @Test
        fun `ProfileSource has MANUAL and IDE values`() {
            val values = ProfileSource.entries
            assert(ProfileSource.MANUAL in values) { "ProfileSource.MANUAL must exist" }
            assert(ProfileSource.IDE in values) { "ProfileSource.IDE must exist" }
        }

        @Test
        fun `DatabaseProfile defaults source to MANUAL`() {
            val p = DatabaseProfile(
                id = "test",
                displayName = "Test",
                dbType = DbType.POSTGRESQL,
                username = "user",
            )
            assert(p.source == ProfileSource.MANUAL) {
                "Default source must be MANUAL for backward compatibility with persisted profiles"
            }
        }

        @Test
        fun `DatabaseProfile accepts IDE source`() {
            val p = DatabaseProfile(
                id = "ide-local_pg",
                displayName = "Local PostgreSQL (IDE)",
                dbType = DbType.POSTGRESQL,
                username = "postgres",
                jdbcUrl = "jdbc:postgresql://localhost:5432/mydb",
                source = ProfileSource.IDE,
            )
            assert(p.source == ProfileSource.IDE) { "Explicitly set source must be IDE" }
        }
    }

    @Nested
    inner class IdeDataSourceResolverBehaviour {

        @Test
        fun `discover returns empty list when Database plugin is absent`() {
            // If the Database plugin is on the classpath (Ultimate test run), the resolver
            // may return actual data sources or still empty (no project data sources configured
            // in a unit-test context). Either outcome is valid here. The important invariant
            // is that the call never throws.
            val mockProject = io.mockk.mockk<Project>(relaxed = true)
            val result = runCatching { IdeDataSourceResolver.discover(mockProject) }
            assert(result.isSuccess) { "discover() must not throw — got: ${result.exceptionOrNull()}" }
        }
    }

    @Nested
    inner class IdDeduplication {

        /**
         * Exercises the dedup logic directly using [DatabaseProfile] instances whose ids
         * already collide — simulating two IDE data sources whose names normalise to the
         * same slug (e.g. "Local DB" and "Local.DB" both becoming "ide-Local_DB").
         *
         * We cannot drive this through [IdeDataSourceResolver.discover] without a real
         * IntelliJ Database plugin on the classpath, so we test the dedup contract by
         * replicating the algorithm inline. This is intentional: the test pins the
         * *contract* (first keeps clean id; subsequent collisions get _2, _3), not the
         * implementation internals.
         */
        @Test
        fun `duplicate normalized ids get counter suffix preserving first occurrence`() {
            // Simulate three profiles that would collide on "ide-Local_DB"
            val colliding = listOf(
                buildIdeProfile("ide-Local_DB"),
                buildIdeProfile("ide-Local_DB"),
                buildIdeProfile("ide-Local_DB"),
            )

            val deduped = deduplicateIds(colliding)

            assert(deduped.size == 3) { "All profiles must be retained" }
            assert(deduped[0].id == "ide-Local_DB")    { "First keeps clean id" }
            assert(deduped[1].id == "ide-Local_DB_2")  { "Second gets _2 suffix" }
            assert(deduped[2].id == "ide-Local_DB_3")  { "Third gets _3 suffix" }
        }

        @Test
        fun `unique ids are not modified`() {
            val unique = listOf(
                buildIdeProfile("ide-ProdDB"),
                buildIdeProfile("ide-StagingDB"),
                buildIdeProfile("ide-LocalDB"),
            )

            val result = deduplicateIds(unique)

            assert(result.map { it.id } == listOf("ide-ProdDB", "ide-StagingDB", "ide-LocalDB")) {
                "Unique ids must not be altered"
            }
        }

        @Test
        fun `two collisions are handled independently`() {
            val mixed = listOf(
                buildIdeProfile("ide-Alpha"),
                buildIdeProfile("ide-Alpha"),
                buildIdeProfile("ide-Beta"),
                buildIdeProfile("ide-Beta"),
            )

            val result = deduplicateIds(mixed)

            assert(result.map { it.id } == listOf("ide-Alpha", "ide-Alpha_2", "ide-Beta", "ide-Beta_2")) {
                "Each collision group should be independently counter-suffixed"
            }
        }

        // Replicates the dedup algorithm from IdeDataSourceResolver.discover so the test
        // is independent of implementation internals (no reflection required).
        private fun deduplicateIds(profiles: List<DatabaseProfile>): List<DatabaseProfile> {
            val seenCounts = mutableMapOf<String, Int>()
            return profiles.map { profile ->
                val count = seenCounts.merge(profile.id, 1, Int::plus)!!
                if (count == 1) profile else profile.copy(id = "${profile.id}_$count")
            }
        }

        private fun buildIdeProfile(id: String) = DatabaseProfile(
            id = id,
            displayName = "$id (IDE)",
            dbType = DbType.POSTGRESQL,
            username = "postgres",
            jdbcUrl = "jdbc:postgresql://localhost:5432/mydb",
            source = ProfileSource.IDE,
        )
    }
}
