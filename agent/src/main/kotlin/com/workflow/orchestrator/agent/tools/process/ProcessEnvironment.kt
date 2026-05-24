package com.workflow.orchestrator.agent.tools.process

/**
 * Environment variable constants and helpers for secure process spawning.
 *
 * Three concerns:
 * 1. **Sensitive vars** — stripped from the inherited environment to prevent credential leaks
 * 2. **Blocked vars** — rejected when provided by the LLM to prevent injection attacks;
 *    BLOCKED is a SUPERSET of SENSITIVE so the LLM cannot re-add stripped credentials
 * 3. **Anti-interactive overrides** — force non-interactive behavior for pagers, editors, git, etc.
 *
 * ## Security invariant
 *
 * SENSITIVE ⊆ BLOCKED. Any key in SENSITIVE is also in BLOCKED (either by exact name or by
 * suffix-pattern match). This prevents the credential re-injection attack where the LLM
 * provides `env: { "ANTHROPIC_API_KEY": "stolen" }` after the key was stripped from the
 * inherited environment.
 *
 * Additionally, [filterUserEnv] applies suffix-pattern matching for common credential patterns
 * (`*_API_KEY`, `*_SECRET`, `*_TOKEN`, `*_PASSWORD`) so newly-introduced credential vars
 * are blocked even if they are not yet in the literal set.
 */
object ProcessEnvironment {

    /**
     * Environment variables stripped before spawn to prevent credential leaks.
     * These are removed from the process's inherited environment regardless of source.
     *
     * Must be a subset of [BLOCKED_ENV_VARS] (SENSITIVE ⊆ BLOCKED).
     */
    val SENSITIVE_ENV_VARS: Set<String> = setOf(
        // AI / LLM API keys
        "ANTHROPIC_API_KEY",
        "OPENAI_API_KEY",
        "COHERE_API_KEY",
        "MISTRAL_API_KEY",
        "GEMINI_API_KEY",
        "SOURCEGRAPH_TOKEN",

        // Source control tokens
        "GITHUB_TOKEN",
        "GH_TOKEN",
        "GITLAB_TOKEN",
        "BITBUCKET_TOKEN",
        "GITHUB_APP_PRIVATE_KEY",

        // Cloud provider credentials — AWS
        "AWS_ACCESS_KEY_ID",
        "AWS_SECRET_ACCESS_KEY",
        "AWS_SESSION_TOKEN",
        "AWS_SECURITY_TOKEN",
        "AWS_PROFILE",
        "AWS_DEFAULT_REGION",    // can reveal org topology
        "AWS_ROLE_ARN",
        "AWS_WEB_IDENTITY_TOKEN_FILE",

        // Cloud provider credentials — GCP
        "GOOGLE_APPLICATION_CREDENTIALS",
        "GCLOUD_SERVICE_KEY",
        "GCP_PROJECT",

        // Cloud provider credentials — Azure
        "AZURE_CLIENT_SECRET",
        "AZURE_CLIENT_ID",
        "AZURE_TENANT_ID",
        "AZURE_SUBSCRIPTION_ID",
        "AZURE_STORAGE_CONNECTION_STRING",

        // Package manager / registry tokens
        "NPM_TOKEN",
        "NUGET_API_KEY",
        "PYPI_TOKEN",
        "CARGO_REGISTRY_TOKEN",
        "DOCKER_PASSWORD",
        "DOCKER_CONFIG",
        "DOCKER_AUTH_CONFIG",

        // CI / workflow tool tokens
        "SONAR_TOKEN",
        "JIRA_TOKEN",
        "BAMBOO_TOKEN",
        "HEROKU_API_KEY",
        "TWILIO_AUTH_TOKEN",
        "SLACK_BOT_TOKEN",
        "CODECOV_TOKEN",
        "SNYK_TOKEN",

        // SSH credentials
        "SSH_AUTH_SOCK",
        "SSH_PRIVATE_KEY",
        "SSH_KEY_PATH",

        // Database credentials
        "DATABASE_URL",
        "DATABASE_PASSWORD",
        "PGPASSWORD",
        "MYSQL_PWD",
        "MONGO_PASSWORD",
        "REDIS_PASSWORD",

        // Infrastructure secrets
        "KUBECONFIG",
        "KUBE_TOKEN",
        "KUBE_CLIENT_CERTIFICATE",
        "KUBE_CLIENT_KEY",
        "VAULT_TOKEN",
        "VAULT_ADDR",
        "VAULT_NAMESPACE",
    )

    /**
     * Environment variables blocked when provided by the LLM via the `env` parameter.
     * These prevent the LLM from hijacking system paths, injecting libraries, or
     * re-adding stripped credentials.
     *
     * **BLOCKED ⊇ SENSITIVE**: all [SENSITIVE_ENV_VARS] are also in this set.
     *
     * Case-insensitive matching is applied via [filterUserEnv]. Additionally,
     * suffix-pattern matching (`*_API_KEY`, `*_SECRET`, `*_TOKEN`, `*_PASSWORD`)
     * covers credential vars not individually listed.
     */
    val BLOCKED_ENV_VARS: Set<String> = buildSet {
        // ── Section 1: all sensitive vars (BLOCKED ⊇ SENSITIVE) ──
        addAll(SENSITIVE_ENV_VARS)

        // ── Section 2: system identity / shell ──
        addAll(setOf(
            "PATH",
            "HOME",
            "SHELL",
            "TERM",
            "USER",
            "LOGNAME",
            "USERNAME",
        ))

        // ── Section 3: Windows system vars ──
        addAll(setOf(
            "SYSTEMROOT",
            "COMSPEC",
            "WINDIR",
            "APPDATA",
            "LOCALAPPDATA",
        ))

        // ── Section 4: native library injection (Linux) ──
        addAll(setOf(
            "LD_PRELOAD",
            "LD_LIBRARY_PATH",
            "LD_AUDIT",
            "LD_DEBUG",
        ))

        // ── Section 5: native library injection (macOS) ──
        addAll(setOf(
            "DYLD_INSERT_LIBRARIES",
            "DYLD_LIBRARY_PATH",
            "DYLD_FRAMEWORK_PATH",
            "DYLD_FORCE_FLAT_NAMESPACE",
        ))

        // ── Section 6: JVM injection ──
        addAll(setOf(
            "JAVA_TOOL_OPTIONS",
            "_JAVA_OPTIONS",
            "CLASSPATH",
            "JDK_JAVA_OPTIONS",
        ))

        // ── Section 7: language path overrides ──
        addAll(setOf(
            "PYTHONPATH",
            "NODE_PATH",
            "GOPATH",
            "CARGO_HOME",
            "PERL5LIB",
            "PERL5OPT",
            "RUBYLIB",
            "RUBYOPT",
        ))

        // ── Section 8: build tool home overrides ──
        addAll(setOf(
            "MAVEN_HOME",
            "GRADLE_HOME",
            "GRADLE_USER_HOME",
        ))
    }

    /** Upper-case lookup set for case-insensitive blocked var matching. */
    private val BLOCKED_UPPER: Set<String> = BLOCKED_ENV_VARS.mapTo(mutableSetOf()) { it.uppercase() }

    /**
     * Credential suffixes that are blocked regardless of prefix, by pattern match.
     * This catches newly-introduced credential vars not yet in [BLOCKED_ENV_VARS].
     * Matching is case-insensitive on the upper-cased key name.
     */
    private val CREDENTIAL_SUFFIX_PATTERNS: List<String> = listOf(
        "_API_KEY",
        "_SECRET_KEY",
        "_SECRET",
        "_TOKEN",
        "_PASSWORD",
        "_PASSWD",
        "_PRIVATE_KEY",
        "_ACCESS_KEY",
        "_AUTH_TOKEN",
        "_AUTH_KEY",
        "_CREDENTIALS",
    )

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
     * Returns true if the variable name matches a known credential suffix pattern.
     * The check is case-insensitive.
     */
    fun isCredentialByPattern(key: String): Boolean {
        val upper = key.uppercase()
        return CREDENTIAL_SUFFIX_PATTERNS.any { suffix -> upper.endsWith(suffix) }
    }

    /**
     * Filters LLM-provided environment variables against [BLOCKED_ENV_VARS] and
     * [CREDENTIAL_SUFFIX_PATTERNS].
     *
     * Rejection criteria (applied in order):
     * 1. Case-insensitive match against [BLOCKED_ENV_VARS] (covers SENSITIVE union BLOCKED).
     * 2. Suffix-pattern match against [CREDENTIAL_SUFFIX_PATTERNS].
     *
     * @param userEnv the env map provided by the LLM
     * @return a pair of (allowed vars, list of rejected var names)
     */
    fun filterUserEnv(userEnv: Map<String, String>): Pair<Map<String, String>, List<String>> {
        val allowed = mutableMapOf<String, String>()
        val rejected = mutableListOf<String>()

        for ((key, value) in userEnv) {
            when {
                key.uppercase() in BLOCKED_UPPER -> rejected.add(key)
                isCredentialByPattern(key) -> rejected.add(key)
                else -> allowed[key] = value
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
