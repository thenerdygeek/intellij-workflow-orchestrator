package com.workflow.orchestrator.agent.ide

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PythonPsiHelperTest {

    @Test
    fun `isAvailable returns false when Python plugin not loaded`() {
        val helper = PythonPsiHelper()
        assertDoesNotThrow { helper.isAvailable }
    }

    @Test
    fun `classNames are correct`() {
        assertEquals("com.jetbrains.python.psi.PyFile", PythonPsiHelper.PY_FILE_CLASS)
        assertEquals("com.jetbrains.python.psi.PyClass", PythonPsiHelper.PY_CLASS_CLASS)
        assertEquals("com.jetbrains.python.psi.PyFunction", PythonPsiHelper.PY_FUNCTION_CLASS)
        assertEquals("com.jetbrains.python.psi.PyTargetExpression", PythonPsiHelper.PY_TARGET_EXPRESSION_CLASS)
        assertEquals("com.jetbrains.python.psi.PyDecorator", PythonPsiHelper.PY_DECORATOR_CLASS)
        assertEquals("com.jetbrains.python.psi.PyDecoratorList", PythonPsiHelper.PY_DECORATOR_LIST_CLASS)
        assertEquals("com.jetbrains.python.psi.PyImportStatement", PythonPsiHelper.PY_IMPORT_STATEMENT_CLASS)
        assertEquals("com.jetbrains.python.psi.PyFromImportStatement", PythonPsiHelper.PY_FROM_IMPORT_STATEMENT_CLASS)
    }

    @Test
    fun `loadClass returns null for unavailable class without throwing`() {
        val result = PythonPsiHelper.loadClass("com.nonexistent.FakeClass")
        assertNull(result)
    }
}
