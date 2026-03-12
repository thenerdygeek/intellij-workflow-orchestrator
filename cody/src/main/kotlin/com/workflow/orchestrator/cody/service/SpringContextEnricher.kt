package com.workflow.orchestrator.cody.service

interface SpringContextEnricher {

    data class SpringContext(
        val isBean: Boolean,
        val beanType: String?,
        val injectedDependencies: List<BeanDependency>,
        val transactionalMethods: List<String>,
        val requestMappings: List<RequestMappingInfo>,
        val beanConsumers: List<String>
    )

    data class BeanDependency(
        val beanName: String,
        val beanType: String,
        val qualifier: String?
    )

    data class RequestMappingInfo(
        val method: String,
        val path: String,
        val handlerMethod: String
    )

    suspend fun enrich(filePath: String): SpringContext?

    companion object {
        val EMPTY = object : SpringContextEnricher {
            override suspend fun enrich(filePath: String): SpringContext? = null
        }
    }
}
