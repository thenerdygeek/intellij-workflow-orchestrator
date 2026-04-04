package com.workflow.orchestrator.agent.tools.database

import kotlinx.serialization.Serializable

/**
 * A named database connection profile for the AI agent's read-only query tools.
 * Passwords are NOT stored here — they live in PasswordSafe under the key
 * "WorkflowOrchestrator.DB.{id}".
 */
@Serializable
data class DatabaseProfile(
    val id: String,           // Slug used as PasswordSafe key, e.g. "local", "qa"
    val displayName: String,  // Human-readable, shown in agent tool output
    val dbType: DbType,
    val jdbcUrl: String,      // Full JDBC URL, e.g. "jdbc:postgresql://localhost:5432/mydb"
    val username: String,
)

enum class DbType(val displayName: String, val driverClass: String) {
    POSTGRESQL("PostgreSQL",  "org.postgresql.Driver"),
    MYSQL("MySQL",            "com.mysql.cj.jdbc.Driver"),
    SQLITE("SQLite",          "org.sqlite.JDBC"),
    MSSQL("SQL Server",       "com.microsoft.sqlserver.jdbc.SQLServerDriver"),
    GENERIC("Generic JDBC",   ""),  // user-supplied driver via classpath
}
