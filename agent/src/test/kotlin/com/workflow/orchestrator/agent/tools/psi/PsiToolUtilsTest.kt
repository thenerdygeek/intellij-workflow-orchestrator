package com.workflow.orchestrator.agent.tools.psi

import com.intellij.psi.*
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PsiToolUtilsTest {

    @Test
    fun `dumbModeError returns error ToolResult`() {
        val result = PsiToolUtils.dumbModeError()
        assertTrue(result.isError)
        assertTrue(result.content.contains("indexing"))
        assertEquals(10, result.tokenEstimate)
    }

    @Test
    fun `formatMethodSignature returns concise signature`() {
        val param1 = mockk<PsiParameter> {
            every { type } returns mockPsiType("String")
            every { name } returns "name"
        }
        val param2 = mockk<PsiParameter> {
            every { type } returns mockPsiType("int")
            every { name } returns "age"
        }
        val parameterList = mockk<PsiParameterList> {
            every { parameters } returns arrayOf(param1, param2)
        }
        val modifierList = mockk<PsiModifierList> {
            every { text } returns "public"
        }
        val returnType = mockPsiType("User")
        val method = mockk<PsiMethod> {
            every { this@mockk.modifierList } returns modifierList
            every { this@mockk.returnType } returns returnType
            every { this@mockk.name } returns "findUser"
            every { this@mockk.parameterList } returns parameterList
            every { annotations } returns emptyArray()
        }

        val result = PsiToolUtils.formatMethodSignature(method)

        assertEquals("public User findUser(String name, int age)", result)
    }

    @Test
    fun `formatMethodSignature includes Spring annotations`() {
        val parameterList = mockk<PsiParameterList> {
            every { parameters } returns emptyArray()
        }
        val modifierList = mockk<PsiModifierList> {
            every { text } returns "public"
        }
        val returnType = mockPsiType("ResponseEntity")
        val annotation = mockk<PsiAnnotation> {
            every { qualifiedName } returns "org.springframework.web.bind.annotation.GetMapping"
        }
        val method = mockk<PsiMethod> {
            every { this@mockk.modifierList } returns modifierList
            every { this@mockk.returnType } returns returnType
            every { this@mockk.name } returns "getUsers"
            every { this@mockk.parameterList } returns parameterList
            every { annotations } returns arrayOf(annotation)
        }

        val result = PsiToolUtils.formatMethodSignature(method)

        assertTrue(result.contains("@GetMapping"))
        assertTrue(result.contains("public ResponseEntity getUsers()"))
    }

    @Test
    fun `formatMethodSignature includes Jakarta annotations`() {
        val parameterList = mockk<PsiParameterList> {
            every { parameters } returns emptyArray()
        }
        val modifierList = mockk<PsiModifierList> {
            every { text } returns "public"
        }
        val returnType = mockPsiType("void")
        val annotation = mockk<PsiAnnotation> {
            every { qualifiedName } returns "jakarta.transaction.Transactional"
        }
        val method = mockk<PsiMethod> {
            every { this@mockk.modifierList } returns modifierList
            every { this@mockk.returnType } returns returnType
            every { this@mockk.name } returns "saveUser"
            every { this@mockk.parameterList } returns parameterList
            every { annotations } returns arrayOf(annotation)
        }

        val result = PsiToolUtils.formatMethodSignature(method)

        assertTrue(result.contains("@Transactional"))
    }

    @Test
    fun `formatMethodSignature handles void return type`() {
        val parameterList = mockk<PsiParameterList> {
            every { parameters } returns emptyArray()
        }
        val modifierList = mockk<PsiModifierList> {
            every { text } returns "private"
        }
        val method = mockk<PsiMethod> {
            every { this@mockk.modifierList } returns modifierList
            every { this@mockk.returnType } returns null
            every { this@mockk.name } returns "init"
            every { this@mockk.parameterList } returns parameterList
            every { annotations } returns emptyArray()
        }

        val result = PsiToolUtils.formatMethodSignature(method)

        assertEquals("private void init()", result)
    }

    @Test
    fun `formatClassSkeleton formats class with fields and methods`() {
        // Mock fields
        val fieldModList = mockk<PsiModifierList> { every { text } returns "private" }
        val field = mockk<PsiField> {
            every { modifierList } returns fieldModList
            every { type } returns mockPsiType("String")
            every { name } returns "name"
        }

        // Mock method
        val methodParamList = mockk<PsiParameterList> { every { parameters } returns emptyArray() }
        val methodModList = mockk<PsiModifierList> { every { text } returns "public" }
        val method = mockk<PsiMethod> {
            every { modifierList } returns methodModList
            every { returnType } returns mockPsiType("String")
            every { this@mockk.name } returns "getName"
            every { parameterList } returns methodParamList
            every { annotations } returns emptyArray()
        }

        // Mock supertype
        val superType = mockk<PsiClassType> { every { presentableText } returns "Serializable" }

        // Mock class modifier list
        val classModList = mockk<PsiModifierList> { every { text } returns "public" }

        // Mock containing file (not PsiJavaFile to skip package)
        val containingFile = mockk<PsiFile>()

        val psiClass = mockk<PsiClass> {
            every { this@mockk.containingFile } returns containingFile
            every { this@mockk.name } returns "User"
            every { this@mockk.modifierList } returns classModList
            every { superTypes } returns arrayOf(superType)
            every { fields } returns arrayOf(field)
            every { methods } returns arrayOf(method)
        }

        val result = PsiToolUtils.formatClassSkeleton(psiClass)

        assertTrue(result.contains("class User"))
        assertTrue(result.contains("extends/implements Serializable"))
        assertTrue(result.contains("private String name;"))
        assertTrue(result.contains("public String getName();"))
    }

    @Test
    fun `formatClassSkeleton includes package from PsiJavaFile`() {
        val classModList = mockk<PsiModifierList> { every { text } returns "public" }
        val javaFile = mockk<PsiJavaFile> {
            every { packageName } returns "com.example.model"
        }

        val psiClass = mockk<PsiClass> {
            every { containingFile } returns javaFile
            every { name } returns "Entity"
            every { modifierList } returns classModList
            every { superTypes } returns emptyArray()
            every { fields } returns emptyArray()
            every { methods } returns emptyArray()
        }

        val result = PsiToolUtils.formatClassSkeleton(psiClass)

        assertTrue(result.contains("package com.example.model;"))
        assertTrue(result.contains("class Entity"))
    }

    @Test
    fun `formatMethodSignature excludes non-Spring non-Jakarta annotations`() {
        val parameterList = mockk<PsiParameterList> {
            every { parameters } returns emptyArray()
        }
        val modifierList = mockk<PsiModifierList> {
            every { text } returns "public"
        }
        val returnType = mockPsiType("void")
        val customAnnotation = mockk<PsiAnnotation> {
            every { qualifiedName } returns "com.example.MyCustomAnnotation"
        }
        val method = mockk<PsiMethod> {
            every { this@mockk.modifierList } returns modifierList
            every { this@mockk.returnType } returns returnType
            every { this@mockk.name } returns "doSomething"
            every { this@mockk.parameterList } returns parameterList
            every { annotations } returns arrayOf(customAnnotation)
        }

        val result = PsiToolUtils.formatMethodSignature(method)

        // Custom annotation should NOT be included
        assertFalse(result.contains("MyCustomAnnotation"))
        assertEquals("public void doSomething()", result)
    }

    private fun mockPsiType(name: String): PsiType {
        return mockk<PsiType> {
            every { presentableText } returns name
        }
    }
}
