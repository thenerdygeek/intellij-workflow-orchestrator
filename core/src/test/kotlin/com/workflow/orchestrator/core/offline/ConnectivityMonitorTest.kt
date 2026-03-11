package com.workflow.orchestrator.core.offline

import com.workflow.orchestrator.core.model.ServiceType
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConnectivityMonitorTest {

    @Test
    fun `initially all services are UNKNOWN`() {
        val monitor = ConnectivityMonitor(mockk(relaxed = true))
        assertEquals(ServiceStatus.UNKNOWN, monitor.statusOf(ServiceType.JIRA))
    }

    @Test
    fun `markOnline sets service to ONLINE`() {
        val monitor = ConnectivityMonitor(mockk(relaxed = true))
        monitor.markOnline(ServiceType.JIRA)
        assertEquals(ServiceStatus.ONLINE, monitor.statusOf(ServiceType.JIRA))
    }

    @Test
    fun `markOffline sets service to OFFLINE`() {
        val monitor = ConnectivityMonitor(mockk(relaxed = true))
        monitor.markOnline(ServiceType.JIRA)
        monitor.markOffline(ServiceType.JIRA)
        assertEquals(ServiceStatus.OFFLINE, monitor.statusOf(ServiceType.JIRA))
    }

    @Test
    fun `overallState is ONLINE when all configured services are online`() {
        val monitor = ConnectivityMonitor(mockk(relaxed = true))
        monitor.markOnline(ServiceType.JIRA)
        monitor.markOnline(ServiceType.BAMBOO)
        assertEquals(OverallState.ONLINE, monitor.overallState(setOf(ServiceType.JIRA, ServiceType.BAMBOO)))
    }

    @Test
    fun `overallState is DEGRADED when some services offline`() {
        val monitor = ConnectivityMonitor(mockk(relaxed = true))
        monitor.markOnline(ServiceType.JIRA)
        monitor.markOffline(ServiceType.BAMBOO)
        assertEquals(
            OverallState.DEGRADED,
            monitor.overallState(setOf(ServiceType.JIRA, ServiceType.BAMBOO))
        )
    }

    @Test
    fun `overallState is OFFLINE when all services offline`() {
        val monitor = ConnectivityMonitor(mockk(relaxed = true))
        monitor.markOffline(ServiceType.JIRA)
        monitor.markOffline(ServiceType.BAMBOO)
        assertEquals(
            OverallState.OFFLINE,
            monitor.overallState(setOf(ServiceType.JIRA, ServiceType.BAMBOO))
        )
    }
}
