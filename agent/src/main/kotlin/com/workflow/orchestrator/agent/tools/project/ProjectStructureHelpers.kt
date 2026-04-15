package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager
import com.intellij.openapi.module.Module
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.module.JpsModuleSourceRootType

internal enum class SourceRootKind { SOURCE, TEST_SOURCE, RESOURCE, TEST_RESOURCE }

internal fun sourceRootKindLabel(kind: SourceRootKind): String = when (kind) {
    SourceRootKind.SOURCE -> "source"
    SourceRootKind.TEST_SOURCE -> "test_source"
    SourceRootKind.RESOURCE -> "resource"
    SourceRootKind.TEST_RESOURCE -> "test_resource"
}

internal fun parseSourceRootKind(raw: String?): SourceRootKind? = when (raw) {
    "source" -> SourceRootKind.SOURCE
    "test_source" -> SourceRootKind.TEST_SOURCE
    "resource" -> SourceRootKind.RESOURCE
    "test_resource" -> SourceRootKind.TEST_RESOURCE
    else -> null
}

internal fun sourceRootKindToJpsType(kind: SourceRootKind): JpsModuleSourceRootType<*> = when (kind) {
    SourceRootKind.SOURCE -> JavaSourceRootType.SOURCE
    SourceRootKind.TEST_SOURCE -> JavaSourceRootType.TEST_SOURCE
    SourceRootKind.RESOURCE -> JavaResourceRootType.RESOURCE
    SourceRootKind.TEST_RESOURCE -> JavaResourceRootType.TEST_RESOURCE
}

/**
 * Strip projectBasePath prefix if present, else return the absolute path.
 *
 * Uses '/' as separator throughout: IntelliJ's [com.intellij.openapi.vfs.VirtualFile.path]
 * and [com.intellij.openapi.project.Project.basePath] both return forward-slash-normalised
 * paths on all platforms (including Windows), so no OS-level separator handling is needed.
 */
internal fun relativizeToProject(absolutePath: String, projectBasePath: String?): String {
    if (projectBasePath == null) return absolutePath
    val base = projectBasePath.trimEnd('/')
    return if (absolutePath.startsWith("$base/")) {
        absolutePath.removePrefix("$base/")
    } else if (absolutePath == base) {
        "."
    } else {
        absolutePath
    }
}

/** Returns the external system id ("GRADLE", "Maven", "sbt", ...) or null when intrinsic. */
internal fun moduleExternalSystemId(module: Module): String? =
    ExternalSystemModulePropertyManager.getInstance(module).getExternalSystemId()
