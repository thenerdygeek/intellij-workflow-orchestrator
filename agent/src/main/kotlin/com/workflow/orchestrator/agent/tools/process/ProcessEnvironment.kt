package com.workflow.orchestrator.agent.tools.process

/**
 * Environment variable constants and helpers for secure process spawning.
 *
 * Three concerns:
 * 1. **Sensitive vars** — stripped from the inherited environment to prevent credential leaks
 * 2. **Blocked vars** — rejected when provided by the LLM to prevent injection attacks
 * 3. **Anti-interactive overrides** — force non-interactive behavior for pagers, editors, git, etc.
 */
object ProcessEnvironment {

    /**
     * Environment variables stripped before spawn to prevent credential leaks.
     * These are removed from the process's inherited environment regardless of source.
     */
    val SENSITIVE_ENV_VARS: Set<String> = setOf(
        // AI / LLM API keys
        "ANTHROPIC_API_KEY",
        "OPENAI_API_KEY",
        "SOURCEGRAPH_TOKEN",

        // Source control tokens
        "GITHUB_TOKEN",
        "GH_TOKEN",
        "GITLAB_TOKEN",
        "BITBUCKET_TOKEN",
        "GITHUB_APP_PRIVATE_KEY",

        // Cloud provider credentials
        "AWS_SECRET_ACCESS_KEY",
        "AWS_SESSION_TOKEN",
        "AWS_ACCESS_KEY_ID",
        "AWS_PROFILE",
        "AZURE_CLIENT_SECRET",
        "AZURE_SUBSCRIPTION_ID",
        "GOOGLE_APPLICATION_CREDENTIALS",

        // Package manager / registry tokens
        "NPM_TOKEN",
        "NUGET_API_KEY",
        "DOCKER_PASSWORD",
        "DOCKER_CONFIG",

        // CI / workflow tool tokens
        "SONAR_TOKEN",
        "JIRA_TOKEN",
        "BAMBOO_TOKEN",
        "HEROKU_API_KEY",
        "TWILIO_AUTH_TOKEN",
        "SLACK_BOT_TOKEN",

        // SSH credentials
        "SSH_AUTH_SOCK",
        "SSH_PRIVATE_KEY",
        "SSH_KEY_PATH",

        // Database credentials
        "DATABASE_URL",
        "PGPASSWORD",
        "MYSQL_PWD",

        // Infrastructure secrets
        "KUBECONFIG",
        "VAULT_TOKEN",
        "VAULT_ADDR",

        // Additional sensitive credentials
        "CODECOV_TOKEN",
        "SNYK_TOKEN",
    )

    /**
     * Environment variables blocked when provided by the LLM via the `env` parameter.
     * These prevent the LLM from hijacking system paths, injecting libraries, or
     * overriding build tool locations.
     *
     * Case-insensitive matching is applied via [filterUserEnv].
     */
    val BLOCKED_ENV_VARS: Set<String> = setOf(
        // System identity / shell
        "PATH",
        "HOME",
        "SHELL",
        "TERM",
        "USER",
        "LOGNAME",
        "USERNAME",

        // Windows system vars
        "SYSTEMROOT",
        "COMSPEC",
        "WINDIR",
        "APPDATA",
        "LOCALAPPDATA",

        // Native library injection (Linux)
        "LD_PRELOAD",
        "LD_LIBRARY_PATH",

        // Native library injection (macOS)
        "DYLD_INSERT_LIBRARIES",
        "DYLD_LIBRARY_PATH",

        // JVM injection
        "JAVA_TOOL_OPTIONS",
        "_JAVA_OPTIONS",
        "CLASSPATH",

        // Language path overrides
        "PYTHONPATH",
        "NODE_PATH",
        "GOPATH",
        "CARGO_HOME",
        "PERL5LIB",
        "RUBYLIB",

        // Build tool home overrides
        "MAVEN_HOME",
        "GRADLE_HOME",
        "GRADLE_USER_HOME",
    )

    /** Upper-case lookup set for case-insensitive blocked var matching. */
    private val BLOCKED_UPPER: Set<String> = BLOCKED_ENV_VARS.mapTo(mutableSetOf()) { it.uppercase() }

    /**
     * Returns anti-interactive environment overrides that prevent pagers, editors,
     * and interactive prompts from blocking the spawned process.
     *
     * @param isWindows true when running on Windows (affects GIT_ASKPASS value)
     */
    fun antiInteractiveEnv(isWindows: Boolean): Map<String, String> = mapOf(
        // Pager suppression
        "PAGER" to "cat",
        "GIT_PAGER" to "cat",
        "MANPAGER" to "cat",
        "SYSTEMD_PAGER" to "",

        // Editor suppression
        "EDITOR" to "cat",
        "VISUAL" to "cat",
        "GIT_EDITOR" to "cat",

        // Terminal / color
        "LESS" to "-FRX",
        "TERM" to "dumb",
        "NO_COLOR" to "1",

        // Git non-interactive
        "GIT_ASKPASS" to if (isWindows) "echo" else "/bin/false",
        "GIT_SSH_COMMAND" to "ssh -o BatchMode=yes",
        "GIT_TERMINAL_PROMPT" to "0",

        // Package manager / runtime
        "NPM_CONFIG_INTERACTIVE" to "false",
        "PYTHONIOENCODING" to "utf-8",
    )

    /**
     * Filters LLM-provided environment variables against [BLOCKED_ENV_VARS].
     * Uses case-insensitive matching to prevent bypass via casing tricks.
     *
     * @param userEnv the env map provided by the LLM
     * @return a pair of (allowed vars, list of rejected var names)
     */
    fun filterUserEnv(userEnv: Map<String, String>): Pair<Map<String, String>, List<String>> {
        val allowed = mutableMapOf<String, String>()
        val rejected = mutableListOf<String>()

        for ((key, value) in userEnv) {
            if (key.uppercase() in BLOCKED_UPPER) {
                rejected.add(key)
            } else {
                allowed[key] = value
            }
        }

        return allowed to rejected
    }

    /**
     * Applies all environment layers to a mutable env map in the correct order:
     *
     * 1. **Strip sensitive vars** — remove credentials from inherited environment
     * 2. **Apply anti-interactive overrides** — prevent pagers/editors from blocking
     * 3. **Apply user overrides** — LLM-provided env vars (already filtered by caller)
     *
     * @param env the mutable environment map (typically from ProcessBuilder.environment())
     * @param isWindows true when running on Windows
     * @param userOverrides pre-filtered user-provided env vars (default: empty)
     */
    fun applyToEnvironment(
        env: MutableMap<String, String>,
        isWindows: Boolean,
        userOverrides: Map<String, String> = emptyMap(),
    ) {
        // Layer 1: Strip sensitive vars
        for (key in SENSITIVE_ENV_VARS) {
            env.remove(key)
        }

        // Layer 2: Apply anti-interactive overrides
        env.putAll(antiInteractiveEnv(isWindows))

        // Layer 3: Apply user overrides (last wins)
        env.putAll(userOverrides)
    }
}
