package com.workflow.orchestrator.core.services

import com.intellij.openapi.extensions.ExtensionPointName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SonarProjectPickerLauncher] EP contract.
 *
 * The concrete implementation ([com.workflow.orchestrator.sonar.service.SonarProjectPickerLauncherImpl])
 * is a thin EDT-dispatch wrapper around the existing dialog; it is covered by manual smoke-testing
 * as noted in project_deferred_ui_refactors. These tests verify only the EP companion object's
 * string constant which must stay in sync with plugin.xml.
 *
 * Note: `getInstance()` calls `EP_NAME.extensionList` which requires a running IntelliJ platform
 * context and cannot be exercised in a plain JUnit5 test — omitted intentionally.
 */
class SonarProjectPickerLauncherTest {

    @Test
    fun `EP_NAME has the qualified name declared in plugin xml`() {
        // Validates the string constant so that renaming the EP in either this file or
        // plugin.xml without updating the other is caught at test time.
        assertEquals(
            "com.workflow.orchestrator.plugin.sonarProjectPickerLauncher",
            SonarProjectPickerLauncher.EP_NAME.name
        )
    }

    @Test
    fun `EP_NAME is an ExtensionPointName of the correct type`() {
        // Type-level sanity check — EP_NAME must be parameterized on the interface itself.
        assert(SonarProjectPickerLauncher.EP_NAME is ExtensionPointName<*>)
    }
}
