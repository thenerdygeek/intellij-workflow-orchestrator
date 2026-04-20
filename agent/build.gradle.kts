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
            "com.intellij.java",
            "com.intellij.spring",
            "com.intellij.spring.boot",
            "org.jetbrains.kotlin",
            "com.intellij.modules.microservices",
        ))
    }

    implementation(project(":core"))
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.coroutines.jdk8)
    compileOnly(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    // JDBC drivers — bundled in the plugin so the agent can query databases
    // without requiring an external DB tool (IntelliJ DataSources, DBeaver, etc.)
    // PostgreSQL (BSD-2) and SQLite (Apache 2.0) have clean licenses.
    // MySQL (GPL) and SQL Server are user-supplied via Generic JDBC mode.
    implementation(libs.postgresql.jdbc)
    implementation(libs.sqlite.jdbc)

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
