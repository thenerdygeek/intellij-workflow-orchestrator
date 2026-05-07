package com.workflow.orchestrator.core.http

import com.workflow.orchestrator.core.model.ServiceType
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CachePolicyRegistryDevStatusTest {

    @Test
    fun `dev-status branch dataType is cacheable with 60s TTL`() {
        val url = "https://jira.example.com/rest/dev-status/1.0/issue/detail?issueId=12345&applicationType=stash&dataType=branch".toHttpUrl()
        val policy = CachePolicyRegistry.policyFor(ServiceType.JIRA, url)
        assertTrue(policy.isCacheable, "dev-status URL must be cacheable")
        assertEquals(60L, policy.ttlSeconds, "dev-status TTL must be 60s")
    }

    @Test
    fun `dev-status pullrequest dataType is cacheable with 60s TTL`() {
        val url = "https://jira.example.com/rest/dev-status/1.0/issue/detail?issueId=12345&applicationType=stash&dataType=pullrequest".toHttpUrl()
        val policy = CachePolicyRegistry.policyFor(ServiceType.JIRA, url)
        assertTrue(policy.isCacheable)
        assertEquals(60L, policy.ttlSeconds)
    }

    @Test
    fun `dev-status build dataType is cacheable with 60s TTL`() {
        val url = "https://jira.example.com/rest/dev-status/1.0/issue/detail?issueId=12345&applicationType=stash&dataType=build".toHttpUrl()
        val policy = CachePolicyRegistry.policyFor(ServiceType.JIRA, url)
        assertEquals(60L, policy.ttlSeconds)
    }

    @Test
    fun `dev-status URL only matches under JIRA service`() {
        val url = "https://example.com/rest/dev-status/1.0/issue/detail?issueId=12345".toHttpUrl()
        val policy = CachePolicyRegistry.policyFor(ServiceType.BITBUCKET, url)
        assertEquals(CachePolicy.NEVER, policy, "Bitbucket service must not match Jira's dev-status pattern")
    }
}
