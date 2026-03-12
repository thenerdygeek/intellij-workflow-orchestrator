package com.workflow.orchestrator.cody.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.cody.protocol.ContextFile
import com.workflow.orchestrator.cody.protocol.Range

@Service(Service.Level.PROJECT)
class CodyContextService(private val project: Project) {

    private val psiEnricher = PsiContextEnricher(project)
    private val springEnricher: SpringContextEnricher = try {
        project.getService(SpringContextEnricher::class.java) ?: SpringContextEnricher.EMPTY
    } catch (_: Exception) {
        SpringContextEnricher.EMPTY
    }

    data class FixContext(
        val instruction: String,
        val contextFiles: List<ContextFile>
    )

    data class TestContext(
        val instruction: String,
        val contextFiles: List<ContextFile>,
        val existingTestFile: String?
    )

    suspend fun gatherFixContext(
        filePath: String,
        issueRange: Range,
        issueType: String,
        issueMessage: String,
        ruleKey: String
    ): FixContext {
        val psiContext = psiEnricher.enrich(filePath)
        val springContext = springEnricher.enrich(filePath)

        val instruction = buildEnrichedFixInstruction(
            issueType, issueMessage, ruleKey, psiContext, springContext
        )

        val contextFiles = buildContextFileList(filePath, issueRange, psiContext)
        return FixContext(instruction, contextFiles)
    }

    suspend fun gatherTestContext(
        filePath: String,
        targetRange: Range
    ): TestContext {
        val psiContext = psiEnricher.enrich(filePath)
        val springContext = springEnricher.enrich(filePath)

        val existingTestFile = psiContext.testFilePath
        val instruction = buildEnrichedTestInstruction(
            targetRange, existingTestFile, psiContext, springContext
        )

        val contextFiles = mutableListOf(
            ContextFile(uri = filePath, range = targetRange)
        )
        if (existingTestFile != null) {
            contextFiles.add(ContextFile(uri = existingTestFile))
        }

        return TestContext(instruction, contextFiles, existingTestFile)
    }

    private fun buildEnrichedFixInstruction(
        issueType: String,
        issueMessage: String,
        ruleKey: String,
        psi: PsiContextEnricher.PsiContext,
        spring: SpringContextEnricher.SpringContext?
    ): String = buildString {
        append("Fix the following SonarQube $issueType issue (rule: $ruleKey):\n")
        append("$issueMessage\n\n")

        if (psi.packageName != null) append("Package: ${psi.packageName}\n")
        if (psi.mavenModule != null) append("Maven module: ${psi.mavenModule}\n")
        if (psi.classAnnotations.isNotEmpty()) {
            append("Class annotations: ${psi.classAnnotations.joinToString(", ") { "@$it" }}\n")
        }

        if (spring != null && spring.isBean) {
            append("\nSpring Context:\n")
            if (spring.beanType != null) append("- This class is a @${spring.beanType} bean\n")
            if (spring.injectedDependencies.isNotEmpty()) {
                append("- Dependencies: ${spring.injectedDependencies.joinToString(", ") { "${it.beanName}: ${it.beanType.substringAfterLast('.')}" }}\n")
            }
            if (spring.transactionalMethods.isNotEmpty()) {
                append("- @Transactional methods: ${spring.transactionalMethods.joinToString(", ")}\n")
            }
            if (spring.requestMappings.isNotEmpty()) {
                append("- REST endpoints:\n")
                spring.requestMappings.forEach {
                    append("  - ${it.method} ${it.path} → ${it.handlerMethod}()\n")
                }
            }
            if (spring.beanConsumers.isNotEmpty()) {
                append("- Injected by: ${spring.beanConsumers.joinToString(", ") { it.substringAfterLast('.') }}\n")
            }
        }

        append("\nProvide a minimal fix that addresses the issue without changing behavior.")
    }

    private fun buildEnrichedTestInstruction(
        range: Range,
        existingTestFile: String?,
        psi: PsiContextEnricher.PsiContext,
        spring: SpringContextEnricher.SpringContext?
    ): String = buildString {
        append("Generate a unit test covering the code at lines ${range.start.line}-${range.end.line}. ")
        append("Use JUnit 5 with standard assertions. ")

        if (existingTestFile != null) {
            append("Add to the existing test file: $existingTestFile. ")
            append("Match the existing test style and imports. ")
        } else {
            append("Create a new test class with proper package and imports. ")
        }

        if (spring != null && spring.isBean) {
            when (spring.beanType) {
                "Service" -> append("Mock injected dependencies with @MockBean or Mockito. ")
                "Controller", "RestController" -> append("Use MockMvc for controller testing. ")
                "Repository" -> append("Use @DataJpaTest for repository testing. ")
            }
            if (spring.transactionalMethods.isNotEmpty()) {
                append("Test transactional behavior — verify rollback on exception. ")
            }
        }

        if (psi.mavenModule != null) {
            append("Module: ${psi.mavenModule}. ")
        }
    }

    private fun buildContextFileList(
        filePath: String,
        issueRange: Range,
        psi: PsiContextEnricher.PsiContext
    ): List<ContextFile> {
        val files = mutableListOf(
            ContextFile(uri = filePath, range = issueRange)
        )
        psi.testFilePath?.let { files.add(ContextFile(uri = it)) }
        psi.relatedFiles.take(3).forEach { files.add(ContextFile(uri = it)) }
        return files
    }
}
