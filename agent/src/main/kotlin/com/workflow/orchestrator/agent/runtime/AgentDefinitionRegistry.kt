package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Scans for custom subagent definitions in markdown files with YAML frontmatter.
 *
 * Locations (priority order):
 * 1. Project: {basePath}/.workflow/agents/{name}.md
 * 2. User: ~/.workflow-orchestrator/agents/{name}.md
 *
 * Format:
 * ---
 * name: code-reviewer
 * description: Reviews code for quality and best practices
 * tools: read_file, search_code, glob_files
 * disallowed-tools: edit_file, run_command
 * model: sonnet
 * max-turns: 15
 * skills: systematic-debugging, api-conventions
 * memory: project
 * ---
 * You are a code reviewer. Analyze code and provide feedback.
 */
class AgentDefinitionRegistry(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(AgentDefinitionRegistry::class.java)
    }

    data class AgentDefinition(
        val name: String,
        val description: String,
        val systemPrompt: String,
        val tools: List<String>? = null,           // allowlist (null = inherit all)
        val disallowedTools: List<String> = emptyList(), // denylist
        val model: String? = null,                  // null = inherit
        val maxTurns: Int = 10,
        val skills: List<String> = emptyList(),     // skills to preload
        val memory: MemoryScope? = null,
        val filePath: String,
        val scope: AgentScope
    )

    enum class AgentScope { BUILTIN, USER, PROJECT }
    enum class MemoryScope { USER, PROJECT, LOCAL }

    @Volatile
    private var agents: Map<String, AgentDefinition> = emptyMap()

    fun scan() {
        val newAgents = mutableMapOf<String, AgentDefinition>()
        // Builtin agents (lowest priority — shipped with plugin)
        loadBuiltinAgents(newAgents)
        // User-level agents (overwrites builtin)
        val userDir = File(System.getProperty("user.home"), ".workflow-orchestrator/agents")
        scanDirectory(userDir, AgentScope.USER, newAgents)
        // Project-level agents (highest priority — overwrites user + builtin)
        val projectDir = File(project.basePath ?: return, ".workflow/agents")
        scanDirectory(projectDir, AgentScope.PROJECT, newAgents)
        agents = newAgents  // atomic swap
        LOG.info("AgentDefinitionRegistry: loaded ${agents.size} agent definitions (${newAgents.count { it.value.scope == AgentScope.BUILTIN }} builtin)")
    }

    fun getAgent(name: String): AgentDefinition? = agents[name]

    fun getAllAgents(): List<AgentDefinition> = agents.values.sortedBy { it.name }

    fun buildDescriptionIndex(): String {
        val sorted = getAllAgents()
        if (sorted.isEmpty()) return ""
        return "Available subagents:\n" + sorted.joinToString("\n") {
            "- ${it.name} — ${it.description}"
        }
    }

    /**
     * Load built-in agent definitions bundled with the plugin from resources.
     * These ship with the plugin and provide default subagent workflows.
     * Users can override them by creating an agent with the same name in their
     * project (.workflow/agents/) or user (~/.workflow-orchestrator/agents/) directory.
     */
    private fun loadBuiltinAgents(target: MutableMap<String, AgentDefinition>) {
        val builtinAgentNames = listOf(
            "code-reviewer", "architect-reviewer", "test-automator",
            "spring-boot-engineer", "refactoring-specialist",
            "devops-engineer", "security-auditor", "performance-engineer"
        )
        for (agentName in builtinAgentNames) {
            try {
                val resourcePath = "/agents/$agentName.md"
                val content = javaClass.getResourceAsStream(resourcePath)?.bufferedReader()?.readText() ?: continue
                val (frontmatter, body) = parseFrontmatter(content) ?: continue
                val name = frontmatter["name"] ?: agentName
                val description = frontmatter["description"] ?: continue

                target[name] = AgentDefinition(
                    name = name,
                    description = description,
                    systemPrompt = body.trim(),
                    tools = frontmatter["tools"]?.let { parseList(it) }?.takeIf { it.isNotEmpty() },
                    disallowedTools = frontmatter["disallowed-tools"]?.let { parseList(it) } ?: emptyList(),
                    model = frontmatter["model"]?.trim()?.takeIf { it.isNotBlank() },
                    maxTurns = frontmatter["max-turns"]?.toIntOrNull() ?: 32,
                    skills = frontmatter["skills"]?.let { parseList(it) } ?: emptyList(),
                    memory = frontmatter["memory"]?.trim()?.uppercase()?.let {
                        try { MemoryScope.valueOf(it) } catch (_: Exception) { null }
                    },
                    filePath = "builtin:$resourcePath",
                    scope = AgentScope.BUILTIN
                )
            } catch (e: Exception) {
                LOG.warn("AgentDefinitionRegistry: failed to load built-in agent '$agentName'", e)
            }
        }
    }

    private fun scanDirectory(dir: File, scope: AgentScope, target: MutableMap<String, AgentDefinition>) {
        if (!dir.isDirectory) return
        val files = dir.listFiles { f -> f.isFile && f.extension == "md" } ?: return
        for (file in files) {
            try {
                val content = file.readText()
                val (frontmatter, body) = parseFrontmatter(content) ?: continue
                val name = frontmatter["name"] ?: file.nameWithoutExtension
                val description = frontmatter["description"] ?: continue // required

                target[name] = AgentDefinition(
                    name = name,
                    description = description,
                    systemPrompt = body.trim(),
                    tools = frontmatter["tools"]?.let { parseList(it) }?.takeIf { it.isNotEmpty() },
                    disallowedTools = frontmatter["disallowed-tools"]?.let { parseList(it) } ?: emptyList(),
                    model = frontmatter["model"]?.trim()?.takeIf { it.isNotBlank() },
                    maxTurns = frontmatter["max-turns"]?.toIntOrNull() ?: 10,
                    skills = frontmatter["skills"]?.let { parseList(it) } ?: emptyList(),
                    memory = frontmatter["memory"]?.trim()?.uppercase()?.let {
                        try { MemoryScope.valueOf(it) } catch (_: Exception) { null }
                    },
                    filePath = file.absolutePath,
                    scope = scope
                )
            } catch (e: Exception) {
                LOG.warn("AgentDefinitionRegistry: failed to parse ${file.name}: ${e.message}")
            }
        }
    }

    private fun parseFrontmatter(content: String): Pair<Map<String, String>, String>? {
        if (!content.trimStart().startsWith("---")) return null
        val endIdx = content.indexOf("---", content.indexOf("---") + 3)
        if (endIdx < 0) return null
        val frontmatterStr = content.substring(content.indexOf("---") + 3, endIdx).trim()
        val body = content.substring(endIdx + 3)
        val map = mutableMapOf<String, String>()
        for (line in frontmatterStr.lines()) {
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim()
                    .removeSurrounding("\"")
                    .removeSurrounding("'")
                map[key] = value
            }
        }
        return map to body
    }

    /**
     * Resolve the persistent memory directory for a subagent based on its MemoryScope.
     * Returns null if memory is not enabled. Creates the directory if it doesn't exist.
     */
    fun getMemoryDirectory(agentDef: AgentDefinition, project: Project): File? {
        val scope = agentDef.memory ?: return null
        val name = agentDef.name
        return when (scope) {
            MemoryScope.USER -> File(System.getProperty("user.home"), ".workflow-orchestrator/agent-memory/$name")
            MemoryScope.PROJECT -> File(project.basePath ?: return null, ".workflow/agent-memory/$name")
            MemoryScope.LOCAL -> File(project.basePath ?: return null, ".workflow/agent-memory-local/$name")
        }.also { it.mkdirs() }
    }

    private fun parseList(value: String): List<String> =
        value.split(",").map { it.trim() }.filter { it.isNotBlank() }
}
