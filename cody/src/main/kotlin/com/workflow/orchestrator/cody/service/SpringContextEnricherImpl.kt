package com.workflow.orchestrator.cody.service

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiParameter
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.spring.CommonSpringModel
import com.intellij.spring.SpringManager
import com.intellij.spring.model.SpringModelSearchParameters
import com.intellij.spring.model.utils.SpringModelSearchers

class SpringContextEnricherImpl(private val project: Project) : SpringContextEnricher {

    private val springBeanAnnotations = setOf(
        "org.springframework.stereotype.Service",
        "org.springframework.stereotype.Controller",
        "org.springframework.stereotype.RestController",
        "org.springframework.stereotype.Repository",
        "org.springframework.stereotype.Component",
        "org.springframework.context.annotation.Configuration",
        "org.springframework.context.annotation.Bean"
    )

    private val transactionalFqn = "org.springframework.transaction.annotation.Transactional"

    private val requestMappingAnnotations = mapOf(
        "org.springframework.web.bind.annotation.RequestMapping" to null,
        "org.springframework.web.bind.annotation.GetMapping" to "GET",
        "org.springframework.web.bind.annotation.PostMapping" to "POST",
        "org.springframework.web.bind.annotation.PutMapping" to "PUT",
        "org.springframework.web.bind.annotation.DeleteMapping" to "DELETE",
        "org.springframework.web.bind.annotation.PatchMapping" to "PATCH"
    )

    override suspend fun enrich(filePath: String): SpringContextEnricher.SpringContext? {
        // readAction 1: resolve file, PSI, and extract basic class/annotation info
        data class BasicInfo(
            val psiClass: PsiClass,
            val psiFile: PsiFile,
            val beanType: String?
        )

        val basicInfo = readAction {
            val vFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return@readAction null
            val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: return@readAction null
            val psiClass = PsiTreeUtil.findChildOfType(psiFile, PsiClass::class.java)
                ?: return@readAction null
            BasicInfo(psiClass, psiFile, detectBeanType(psiClass))
        } ?: return null

        // readAction 2: Spring model queries (doesBeanExist, getSpringModelByFile)
        data class SpringModelInfo(
            val isBean: Boolean,
            val springModel: CommonSpringModel?
        )

        val springModelInfo = readAction {
            val springModel = SpringManager.getInstance(project)
                .getSpringModelByFile(basicInfo.psiFile)
            if (springModel == null) {
                SpringModelInfo(isBean = false, springModel = null)
            } else {
                SpringModelInfo(
                    isBean = isSpringBean(basicInfo.psiClass, springModel),
                    springModel = springModel
                )
            }
        }

        if (!springModelInfo.isBean || springModelInfo.springModel == null) {
            return SpringContextEnricher.SpringContext(
                isBean = false,
                beanType = null,
                injectedDependencies = emptyList(),
                transactionalMethods = emptyList(),
                requestMappings = emptyList(),
                beanConsumers = emptyList()
            )
        }

        // readAction 3: method scanning (injected deps, transactional methods, request mappings, bean consumers)
        return readAction {
            SpringContextEnricher.SpringContext(
                isBean = true,
                beanType = basicInfo.beanType,
                injectedDependencies = findInjectedDependencies(basicInfo.psiClass),
                transactionalMethods = findTransactionalMethods(basicInfo.psiClass),
                requestMappings = findRequestMappings(basicInfo.psiClass),
                beanConsumers = findBeanConsumers(basicInfo.psiClass, springModelInfo.springModel)
            )
        }
    }

    private fun isSpringBean(psiClass: PsiClass, model: CommonSpringModel): Boolean {
        return SpringModelSearchers.doesBeanExist(model, psiClass)
    }

    private fun detectBeanType(psiClass: PsiClass): String? {
        for (fqn in springBeanAnnotations) {
            if (psiClass.getAnnotation(fqn) != null) {
                return fqn.substringAfterLast('.')
            }
        }
        return null
    }

    private fun findInjectedDependencies(
        psiClass: PsiClass
    ): List<SpringContextEnricher.BeanDependency> {
        val deps = mutableListOf<SpringContextEnricher.BeanDependency>()

        val primaryConstructor = psiClass.constructors.firstOrNull()
        primaryConstructor?.parameterList?.parameters?.forEach { param ->
            deps.add(paramToBeanDependency(param))
        }

        psiClass.fields.filter { field ->
            field.getAnnotation("org.springframework.beans.factory.annotation.Autowired") != null
        }.forEach { field ->
            deps.add(fieldToBeanDependency(field))
        }

        return deps
    }

    private fun paramToBeanDependency(param: PsiParameter): SpringContextEnricher.BeanDependency {
        val qualifier = param.getAnnotation("org.springframework.beans.factory.annotation.Qualifier")
            ?.findAttributeValue("value")?.text?.removeSurrounding("\"")
        return SpringContextEnricher.BeanDependency(
            beanName = param.name,
            beanType = param.type.canonicalText,
            qualifier = qualifier
        )
    }

    private fun fieldToBeanDependency(field: PsiField): SpringContextEnricher.BeanDependency {
        val qualifier = field.getAnnotation("org.springframework.beans.factory.annotation.Qualifier")
            ?.findAttributeValue("value")?.text?.removeSurrounding("\"")
        return SpringContextEnricher.BeanDependency(
            beanName = field.name,
            beanType = field.type.canonicalText,
            qualifier = qualifier
        )
    }

    private fun findTransactionalMethods(psiClass: PsiClass): List<String> {
        val methods = mutableListOf<String>()
        if (psiClass.getAnnotation(transactionalFqn) != null) {
            methods.addAll(psiClass.methods.filter { it.hasModifierProperty("public") }.map { it.name })
        } else {
            psiClass.methods.filter { it.getAnnotation(transactionalFqn) != null }
                .forEach { methods.add(it.name) }
        }
        return methods
    }

    private fun findRequestMappings(psiClass: PsiClass): List<SpringContextEnricher.RequestMappingInfo> {
        val mappings = mutableListOf<SpringContextEnricher.RequestMappingInfo>()

        val classPrefix = psiClass.getAnnotation(
            "org.springframework.web.bind.annotation.RequestMapping"
        )?.let { extractMappingPath(it) } ?: ""

        for (method in psiClass.methods) {
            for ((annotationFqn, httpMethod) in requestMappingAnnotations) {
                val annotation = method.getAnnotation(annotationFqn) ?: continue
                val path = classPrefix + extractMappingPath(annotation)
                val resolvedMethod = httpMethod
                    ?: extractRequestMethod(annotation)
                    ?: "GET"
                mappings.add(
                    SpringContextEnricher.RequestMappingInfo(
                        method = resolvedMethod,
                        path = path,
                        handlerMethod = method.name
                    )
                )
            }
        }
        return mappings
    }

    private fun extractMappingPath(annotation: PsiAnnotation): String {
        val value = annotation.findAttributeValue("value")
            ?: annotation.findAttributeValue("path")
        return value?.text?.removeSurrounding("\"")?.removeSurrounding("{", "}") ?: ""
    }

    private fun extractRequestMethod(annotation: PsiAnnotation): String? {
        val method = annotation.findAttributeValue("method") ?: return null
        return method.text?.removeSurrounding("RequestMethod.", "")
    }

    private fun findBeanConsumers(psiClass: PsiClass, model: CommonSpringModel): List<String> {
        val consumers = mutableListOf<String>()
        val searchParams = SpringModelSearchParameters.byClass(psiClass)
        val beans = SpringModelSearchers.findBeans(model, searchParams)
        for (bean in beans.take(10)) {
            val beanClass = bean.beanClass
            if (beanClass != null && beanClass != psiClass) {
                consumers.add(beanClass.qualifiedName ?: beanClass.name ?: "unknown")
            }
        }
        return consumers.distinct()
    }
}
