package com.workflow.orchestrator.core.util

import java.io.File
import java.security.MessageDigest

/**
 * Computes stable, human-readable project identifiers for the unified storage root.
 * Format: {directoryName}-{first6OfSHA256(absolutePath)}
 * Example: "MyPlugin-a3f8b2"
 */
object ProjectIdentifier {

    private const val HASH_LENGTH = 6
    private const val ROOT_DIR = ".workflow-orchestrator"

    fun compute(projectBasePath: String): String {
        val dirName = File(projectBasePath).name
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(projectBasePath.toByteArray(Charsets.UTF_8))
            .take(HASH_LENGTH / 2)
            .joinToString("") { "%02x".format(it) }
        return "$dirName-$hash"
    }

    fun rootDir(projectBasePath: String): File {
        return File(System.getProperty("user.home"), "$ROOT_DIR/${compute(projectBasePath)}")
    }

    fun agentDir(projectBasePath: String): File {
        return File(rootDir(projectBasePath), "agent")
    }

    fun sessionsDir(projectBasePath: String): File {
        return File(agentDir(projectBasePath), "sessions")
    }

    fun logsDir(projectBasePath: String): File {
        return File(rootDir(projectBasePath), "logs")
    }
}
