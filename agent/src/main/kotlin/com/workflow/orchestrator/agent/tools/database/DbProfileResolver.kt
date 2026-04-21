package com.workflow.orchestrator.agent.tools.database

import com.intellij.openapi.project.Project

/**
 * Unified profile lookup for db_* tools.
 *
 * Returns [DbProfileLookup.Found] when a manual (Settings-UI) profile exists for [profileId].
 * Returns [DbProfileLookup.IdeManaged] when the id matches an IDE-sourced data source —
 * these cannot be executed by the agent because their JDBC credentials are held by the IDE
 * and are not accessible via reflection. Returns [DbProfileLookup.NotFound] when the id
 * matches neither.
 *
 * The cheap prefix check (`ide-`) catches the common case without a full resolve call.
 * The full [IdeDataSourceResolver.discover] scan is the defensive correctness backstop for
 * any profile whose id was derived from a data-source name that does not start with "ide-".
 */
internal sealed class DbProfileLookup {
    data class Found(val profile: DatabaseProfile) : DbProfileLookup()
    data class IdeManaged(val displayName: String) : DbProfileLookup()
    object NotFound : DbProfileLookup()
}

/**
 * Performs a three-way lookup:
 *
 * 1. Try [DatabaseSettings] (manual profiles persisted in workflowDatabases.xml). If found → [DbProfileLookup.Found].
 * 2. Cheap prefix check: if [profileId] starts with `"ide-"` it cannot be a manual profile →
 *    attempt to find it in [IdeDataSourceResolver.discover] for a display name; fall back to the
 *    raw id as the display name if discover returns empty (no Database plugin on classpath).
 * 3. Full IDE scan: check [IdeDataSourceResolver.discover] for any profile whose id matches
 *    [profileId] regardless of prefix (defensive correctness for atypical id derivations).
 * 4. Neither found → [DbProfileLookup.NotFound].
 */
internal fun lookupDbProfile(project: Project, profileId: String): DbProfileLookup {
    // Step 1: check manual profiles (common case — short-circuit if found)
    val manual = try {
        DatabaseSettings.getInstance(project).getProfile(profileId)
    } catch (_: Exception) {
        null
    }
    if (manual != null) return DbProfileLookup.Found(manual)

    // Steps 2 + 3: check IDE profiles.
    // The `ide-` prefix is a cheap early indicator; the full scan handles edge cases.
    val isIdePrefixed = profileId.startsWith("ide-")
    val ideProfiles = try { IdeDataSourceResolver.discover(project) } catch (_: Exception) { emptyList() }
    val ideMatch = ideProfiles.firstOrNull { it.id == profileId }

    return when {
        ideMatch != null -> DbProfileLookup.IdeManaged(ideMatch.displayName)
        isIdePrefixed -> {
            // Prefix strongly suggests IDE origin even if discover() returned empty
            // (e.g., Database plugin absent at test time). Use the raw id as the label.
            DbProfileLookup.IdeManaged(profileId)
        }
        else -> DbProfileLookup.NotFound
    }
}
