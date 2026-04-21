package com.workflow.orchestrator.agent.tools.database

import com.intellij.openapi.project.Project
import java.lang.reflect.Method

/**
 * Reflective facade over com.intellij.database. Discovers IDE-configured
 * data sources (the ones users set up in View | Tool Windows | Database)
 * and exposes them as DatabaseProfile instances with source=IDE.
 *
 * Self-gating: on Community (no Database plugin) every method returns
 * empty list. Augments DbListProfilesTool output alongside the agent's
 * manual profiles — does not replace them. Passwords are NOT read;
 * authentication for IDE-sourced profiles is handled separately by the
 * user through the IDE's Database tool window.
 *
 * Signatures verified 2026-04-21 against IU-2025.1. See
 * docs/research/2026-04-21-intellij-database-api-signatures.md.
 *
 * Entry point: [LocalDataSourceManager.getInstance(project)] → [getDataSources()]
 * returns List<LocalDataSource>. Each LocalDataSource exposes getName(), getUrl(),
 * getUsername() (no-arg), getDriverClass(), getDbms(). Passwords are NOT accessible
 * via reflection — getPasswordStorage() returns only the storage location enum.
 *
 * Threading: LocalDataSourceManager.getInstance and getDataSources are project
 * services safe to call from any thread (no ReadAction required).
 */
internal object IdeDataSourceResolver {

    private const val LOCAL_DATA_SOURCE_MANAGER_FQN =
        "com.intellij.database.dataSource.LocalDataSourceManager"

    @Volatile private var cached: Api? = null
    @Volatile private var initialized: Boolean = false

    /**
     * IDE-configured data sources, converted to DatabaseProfile. Each
     * profile has source=IDE, a derived id like "ide-<dataSourceName>",
     * and uses the data source's JDBC URL directly (no host/port
     * disassembly). Empty list on Community or if reflection fails.
     */
    fun discover(project: Project): List<DatabaseProfile> {
        val api = api() ?: return emptyList()
        val manager = try {
            api.getInstance.invoke(null, project)
        } catch (_: Exception) { return emptyList() } ?: return emptyList()

        val rawSources = try {
            @Suppress("UNCHECKED_CAST")
            api.getDataSources.invoke(manager) as? List<Any>
        } catch (_: Exception) { null } ?: return emptyList()

        val profiles = rawSources.mapNotNull { toProfile(it) }

        // Deduplicate ids by suffixing a counter when two IDE data sources
        // have names that normalize to the same slug (e.g. "Local DB" and
        // "Local.DB" both becoming ide-Local_DB). First occurrence keeps the
        // clean id; subsequent collisions get _2, _3, etc.
        val seenCounts = mutableMapOf<String, Int>()
        return profiles.map { profile ->
            val count = seenCounts.merge(profile.id, 1, Int::plus)!!
            if (count == 1) profile else profile.copy(id = "${profile.id}_$count")
        }
    }

    private fun toProfile(rawDataSource: Any): DatabaseProfile? {
        val name = invokeString(rawDataSource, "getName") ?: return null
        val url = invokeString(rawDataSource, "getUrl").orEmpty()
        // getUsername() is overloaded (one no-arg instance method, one static with param).
        // Filter explicitly by parameterCount == 0 to avoid the static overload.
        val username = try {
            rawDataSource.javaClass.methods
                .firstOrNull { it.name == "getUsername" && it.parameterCount == 0 }
                ?.invoke(rawDataSource) as? String
        } catch (_: Exception) { null }.orEmpty()

        // Derive DbType from the JDBC URL. The Database plugin's Dbms
        // value type is richer, but matching to our DbType enum via
        // URL prefix is sufficient and avoids another reflective
        // class-load cost.
        val dbType = when {
            url.startsWith("jdbc:postgresql:") -> DbType.POSTGRESQL
            url.startsWith("jdbc:mysql:") -> DbType.MYSQL
            url.startsWith("jdbc:sqlserver:") -> DbType.MSSQL
            url.startsWith("jdbc:sqlite:") -> DbType.SQLITE
            else -> DbType.GENERIC
        }

        return DatabaseProfile(
            id = "ide-${name.replace(Regex("[^A-Za-z0-9_-]"), "_")}",
            displayName = "$name (IDE)",
            dbType = dbType,
            username = username,
            jdbcUrl = url,
            source = ProfileSource.IDE,
        )
    }

    private fun api(): Api? {
        if (initialized) return cached
        synchronized(this) {
            if (initialized) return cached
            cached = buildApi()
            initialized = true
            return cached
        }
    }

    private fun buildApi(): Api? {
        return try {
            val mgrCls = Class.forName(LOCAL_DATA_SOURCE_MANAGER_FQN)
            val getInstance = mgrCls.getMethod("getInstance", Project::class.java)
            val getDataSources = mgrCls.methods.firstOrNull {
                it.name == "getDataSources" && it.parameterCount == 0
            } ?: return null
            Api(getInstance = getInstance, getDataSources = getDataSources)
        } catch (_: Throwable) {
            null
        }
    }

    private class Api(val getInstance: Method, val getDataSources: Method)

    private fun invokeString(target: Any, methodName: String): String? = try {
        target.javaClass.getMethod(methodName).invoke(target) as? String
    } catch (_: Exception) { null }
}
