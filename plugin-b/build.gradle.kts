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
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.serialization.json)
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.vintage.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(kotlin("test"))
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
    pluginVerification {
        ides {
            create(
                org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaUltimate,
                providers.gradleProperty("platformVersion"),
            )
        }
    }
}
