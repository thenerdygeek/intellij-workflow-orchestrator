plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinSerialization)
    id("org.jetbrains.intellij.platform.module")
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaUltimate(providers.gradleProperty("platformVersion"))
        bundledPlugins(listOf(
            "Git4Idea",
            "com.intellij.database",
            "com.intellij.grpc",
            "com.intellij.java",
            "com.intellij.persistence",
            "com.intellij.spring",
            "com.intellij.spring.boot",
            "com.intellij.spring.messaging",
            "org.jetbrains.kotlin",
            "org.jetbrains.idea.maven",
            "com.intellij.modules.microservices",
        ))
    }

    implementation(project(":core"))
    // :agent owns the AgentTool wrapping for every feature module. :document is a
    // controlled exception to the "no feature-to-feature imports" rule.
    implementation(project(":document"))
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    // JDBC drivers — only PostgreSQL is bundled (BSD-2, ~1.1 MB). SQLite was previously
    // bundled but its JAR ships native libs for 22 platforms (~13.5 MB compressed) and
    // most users do not need it; SQLite profiles now expect the user to install the
    // driver themselves (Generic JDBC mode + sqlite-jdbc on the IDE classpath, or via
    // IntelliJ's DataSources driver downloader). MySQL/SQL Server already follow this
    // pattern — see DatabaseConnectionManager's SQLITE branch for the user-facing error.
    implementation(libs.postgresql.jdbc)
    testImplementation(libs.sqlite.jdbc)

    testImplementation(libs.junit5.api)
    testImplementation(libs.junit5.params)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
}

tasks.test {
    useJUnitPlatform()
    // :document's sandbox must be prepared before :agent tests run because :agent now
    // depends on :document and the IntelliJ Platform test runner shares the sandbox directory.
    dependsOn(":document:prepareTestSandbox")
}

tasks.register<Exec>("npmInstallWebview") {
    workingDir = file("webview")
    commandLine("npm", "ci")
    inputs.file("webview/package-lock.json")
    outputs.dir("webview/node_modules")
}

tasks.register<Exec>("buildWebview") {
    workingDir = file("webview")
    commandLine("npm", "run", "build")
    dependsOn("npmInstallWebview")
    inputs.dir("webview/src")
    inputs.file("webview/package.json")
    inputs.file("webview/vite.config.ts")
    inputs.file("webview/tsconfig.json")
    outputs.dir("src/main/resources/webview/dist")
}

tasks.named("processResources") {
    dependsOn("buildWebview")
}
