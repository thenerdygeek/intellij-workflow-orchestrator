package com.workflow.orchestrator.core.http

import com.workflow.orchestrator.core.model.ServiceType
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CachePolicyRegistryTest {

    @Test
    fun `jira issue detail has 60s TTL`() {
        val url = "https://jira.example.com/rest/api/2/issue/PROJ-123".toHttpUrl()
        val policy = CachePolicyRegistry.policyFor(ServiceType.JIRA, url)
        assertEquals(60L, policy.ttlSeconds)
        assertTrue(policy.isCacheable)
    }

    @Test
    fun `jira board listing has 300s TTL`() {
        val url = "https://jira.example.com/rest/agile/1.0/board?type=scrum".toHttpUrl()
        val policy = CachePolicyRegistry.policyFor(ServiceType.JIRA, url)
        assertEquals(300L, policy.ttlSeconds)
    }

    @Test
    fun `jira sensitive path myself returns NEVER`() {
        val url = "https://jira.example.com/rest/api/2/myself".toHttpUrl()
        val policy = CachePolicyRegistry.policyFor(ServiceType.JIRA, url)
        assertEquals(CachePolicy.NEVER, policy)
        assertFalse(policy.isCacheable)
    }

    @Test
    fun `jira auth path returns NEVER`() {
        val url = "https://jira.example.com/rest/auth/1/session".toHttpUrl()
        assertEquals(CachePolicy.NEVER, CachePolicyRegistry.policyFor(ServiceType.JIRA, url))
    }

    @Test
    fun `bamboo finished build has 24-hour TTL`() {
        val url = "https://bamboo.example.com/rest/api/latest/result/PROJ-PLAN-JOB/42".toHttpUrl()
        assertEquals(86400L, CachePolicyRegistry.policyFor(ServiceType.BAMBOO, url).ttlSeconds)
    }

    @Test
    fun `bamboo plan top-level has 0s TTL (always revalidate + hash)`() {
        val url = "https://bamboo.example.com/rest/api/latest/result/PROJ-PLAN".toHttpUrl()
        val policy = CachePolicyRegistry.policyFor(ServiceType.BAMBOO, url)
        assertEquals(0L, policy.ttlSeconds)
        assertTrue(policy.isCacheable)
    }

    @Test
    fun `bitbucket pr activities has 60s TTL`() {
        val url = "https://bitbucket.example.com/rest/api/1.0/projects/P/repos/R/pull-requests/7/activities".toHttpUrl()
        assertEquals(60L, CachePolicyRegistry.policyFor(ServiceType.BITBUCKET, url).ttlSeconds)
    }

    @Test
    fun `sonar compute-engine task is never cached`() {
        val url = "https://sonar.example.com/api/ce/task?id=abc".toHttpUrl()
        assertEquals(CachePolicy.NEVER, CachePolicyRegistry.policyFor(ServiceType.SONARQUBE, url))
    }

    @Test
    fun `sonar quality gate has 300s TTL`() {
        val url = "https://sonar.example.com/api/qualitygates/project_status?projectKey=P".toHttpUrl()
        assertEquals(300L, CachePolicyRegistry.policyFor(ServiceType.SONARQUBE, url).ttlSeconds)
    }

    @Test
    fun `sourcegraph streaming endpoint is never cached`() {
        val url = "https://sg.example.com/.api/completions/stream".toHttpUrl()
        assertEquals(CachePolicy.NEVER, CachePolicyRegistry.policyFor(ServiceType.SOURCEGRAPH, url))
    }

    @Test
    fun `sourcegraph client-config has 10 minute TTL`() {
        val url = "https://sg.example.com/.api/client-config".toHttpUrl()
        assertEquals(600L, CachePolicyRegistry.policyFor(ServiceType.SOURCEGRAPH, url).ttlSeconds)
    }

    @Test
    fun `nexus search has 2 minute TTL`() {
        val url = "https://nexus.example.com/service/rest/v1/search?name=foo".toHttpUrl()
        assertEquals(120L, CachePolicyRegistry.policyFor(ServiceType.NEXUS, url).ttlSeconds)
    }

    @Test
    fun `unknown path on known service returns NEVER`() {
        val url = "https://jira.example.com/rest/api/2/weird-new-endpoint".toHttpUrl()
        assertEquals(CachePolicy.NEVER, CachePolicyRegistry.policyFor(ServiceType.JIRA, url))
    }

    @Test
    fun `graphql endpoint is sensitive on any service`() {
        val url = "https://any.example.com/_api/graphql".toHttpUrl()
        assertEquals(CachePolicy.NEVER, CachePolicyRegistry.policyFor(ServiceType.BITBUCKET, url))
        assertEquals(CachePolicy.NEVER, CachePolicyRegistry.policyFor(ServiceType.SONARQUBE, url))
    }

    @Test
    fun `service rules do not leak across services`() {
        val jiraUrl = "https://host/rest/api/2/issue/PROJ-1".toHttpUrl()
        assertEquals(60L, CachePolicyRegistry.policyFor(ServiceType.JIRA, jiraUrl).ttlSeconds)
        assertEquals(CachePolicy.NEVER, CachePolicyRegistry.policyFor(ServiceType.BAMBOO, jiraUrl))
        assertEquals(CachePolicy.NEVER, CachePolicyRegistry.policyFor(ServiceType.BITBUCKET, jiraUrl))
    }

    @Test
    fun `policy equality for CachePolicy NEVER`() {
        val a = CachePolicy.NEVER
        val b = CachePolicy(-1L)
        assertEquals(a, b)
    }
}
