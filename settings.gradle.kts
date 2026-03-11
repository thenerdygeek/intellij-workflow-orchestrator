rootProject.name = "intellij-workflow-orchestrator"

plugins {
    // Toolchain resolver for automatic JDK provisioning
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// ---- Submodule declarations ----
include(
    ":core",
    ":jira",
    ":git-integration",
    ":bamboo",
    ":sonar",
    ":cody",
)
