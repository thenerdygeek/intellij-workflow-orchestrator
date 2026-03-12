package com.workflow.orchestrator.core.offline

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.ServiceType
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class ConnectivityMonitor(private val project: Project) {

    private val log = Logger.getInstance(ConnectivityMonitor::class.java)
    private val statuses = ConcurrentHashMap<ServiceType, ServiceStatus>()

    fun statusOf(service: ServiceType): ServiceStatus {
        return statuses.getOrDefault(service, ServiceStatus.UNKNOWN)
    }

    fun markOnline(service: ServiceType) {
        val previous = statuses.put(service, ServiceStatus.ONLINE)
        if (previous != ServiceStatus.ONLINE) {
            log.warn("[Core:Connectivity] ${service.name} connectivity changed: ${previous ?: "UNKNOWN"} -> ONLINE")
        }
    }

    fun markOffline(service: ServiceType) {
        val previous = statuses.put(service, ServiceStatus.OFFLINE)
        if (previous != ServiceStatus.OFFLINE) {
            log.warn("[Core:Connectivity] ${service.name} connectivity changed: ${previous ?: "UNKNOWN"} -> OFFLINE")
        }
    }

    fun overallState(configuredServices: Set<ServiceType>): OverallState {
        if (configuredServices.isEmpty()) return OverallState.ONLINE
        val serviceStatuses = configuredServices.map { statusOf(it) }
        return when {
            serviceStatuses.all { it == ServiceStatus.ONLINE } -> OverallState.ONLINE
            serviceStatuses.all { it == ServiceStatus.OFFLINE } -> OverallState.OFFLINE
            else -> OverallState.DEGRADED
        }
    }
}
