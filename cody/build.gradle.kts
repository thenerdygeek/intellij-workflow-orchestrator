plugins {
    alias(libs.plugins.kotlin)
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
        bundledPlugins(listOf("Git4Idea", "com.intellij.java", "org.jetbrains.idea.maven", "com.intellij.spring"))
    }

    implementation(project(":core"))
    implementation(project(":sonar"))
    compileOnly(libs.kotlinx.coroutines.core)


    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
    dependsOn(":sonar:prepareTestSandbox")
}
