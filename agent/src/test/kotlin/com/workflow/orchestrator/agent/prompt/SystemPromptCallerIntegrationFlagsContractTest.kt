// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.prompt

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Every production call to SystemPrompt.build(...) must pass `integrations =` — else a configured
 *  user silently gets a stack-less prompt (the param defaults to NONE for the pure snapshot tests). */
class SystemPromptCallerIntegrationFlagsContractTest {
    @Test fun `every SystemPrompt_build call site in agent main passes integrations`() {
        val mainSrc = File("src/main/kotlin")
        val offenders = mainSrc.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                // crude but sufficient: find each `SystemPrompt.build(` and check the call (up to its
                // matching close on the same logical block) contains `integrations =`. The 3 known
                // callers use multi-line named args, so scan a generous window.
                Regex("SystemPrompt\\.build\\(").findAll(text).mapNotNull { m ->
                    val window = text.substring(m.range.first, minOf(text.length, m.range.first + 4000))
                    if (window.contains("integrations =")) null else "${file.name} @ ${m.range.first}"
                }
            }.toList()
        assertTrue(offenders.isEmpty(), "SystemPrompt.build() callers missing `integrations =`: $offenders")
    }
}
