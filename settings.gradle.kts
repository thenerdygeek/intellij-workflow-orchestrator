rootProject.name = "intellij-workflow-orchestrator"

plugins {
    // Toolchain resolver for automatic JDK provisioning
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// ---- Submodule declarations ----
include(
    ":core",
    ":document",
    ":jira",
    ":bamboo",
    ":sonar",
    ":pullrequest",
    ":automation",
    ":handover",
    ":agent",
    ":web",
    ":mock-server",
)
