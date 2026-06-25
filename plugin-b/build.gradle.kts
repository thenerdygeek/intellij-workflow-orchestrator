import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.intelliJPlatform)
}

kotlin { jvmToolchain(providers.gradleProperty("javaVersion").get().toInt()) }

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdeaUltimate(providers.gradleProperty("platformVersion"))
        // B depends on plugin A. The root project IS plugin A.
        // localPlugin(project(rootProject.path)) is the primary form from the brief.
        // NOTE: when run with --rerun-tasks or from a clean build this works correctly.
        // A stale Gradle build cache can produce a duplicate searchable-options JAR error;
        // the fix is `./gradlew clean` or `--rerun-tasks`. This is a known Gradle cache issue
        // with the IntelliJ Platform Gradle Plugin 2.x and self-referencing local plugins.
        // FALLBACK: if `:plugin-a` extraction is done, switch to localPlugin(project(":plugin-a")).
        localPlugin(project(rootProject.path))
        testFramework(TestFrameworkType.Platform)
    }
    // B compiles against :core's interfaces (WorkflowConfig, ServiceType, ConnectionSettings).
    // A ships :core at runtime (it's part of plugin A's classpath), so compileOnly is correct —
    // :core must NOT be bundled inside B's jar (would be a duplicate-class conflict at runtime).
    compileOnly(project(":core"))
    // B compiles against :agent's agentToolContributor EP (AgentToolContributor,
    // ToolRegistrationContext, AgentTool, FunctionParameters, ToolResult, WorkerType).
    // localPlugin(A) is a runtime/sandbox dep — it does NOT put A's modules on B's compile
    // classpath — so this explicit compileOnly is required (Task 3 lesson). compileOnly because
    // A ships :agent at runtime; bundling it in B's jar would be a duplicate-class conflict.
    compileOnly(project(":agent"))
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.serialization.json)
    // B's behavioral tests exercise the REAL split impls (CompanyBWorkflowConfig +
    // CompanyBToolContributor) against :core's WorkflowConfig and :agent's ToolRegistry /
    // ToolRegistrationContext / ToolContributionRunner, so both modules must be on the TEST
    // classpath (compileOnly above only covers main). serialization.json is needed because
    // AgentTool.execute returns a kotlinx.serialization JsonObject.
    testImplementation(project(":core"))
    testImplementation(project(":agent"))
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.mockk)
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.vintage.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

intellijPlatform {
    pluginConfiguration {
        id = "com.workflow.orchestrator.companyb.plugin"
        name = "Workflow Orchestrator - Company B"
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }
    // NOTE: no pluginVerification block here. Plugin B is private and hard-depends on
    // local plugin A (com.workflow.orchestrator.plugin), which the Marketplace Plugin
    // Verifier cannot resolve (it searches only Local Repo / bundled / Marketplace).
    // The Marketplace verifier therefore does not apply to B; A (the root project) is
    // still verified by `verifyPlugin`. B's gates are :plugin-b:buildPlugin +
    // verifyPluginStructure + the two-plugin runIde smoke.
}

// Plugin B is private and hard-depends on local plugin A, which the Marketplace Plugin
// Verifier cannot resolve (it searches only Local Repo / bundled / Marketplace). The
// Marketplace verifier therefore does not apply to B; A (the root project) is still verified
// by `verifyPlugin`. B's gates are :plugin-b:buildPlugin + structure verification + the
// two-plugin runIde smoke.
tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask>().configureEach {
    enabled = false
}
