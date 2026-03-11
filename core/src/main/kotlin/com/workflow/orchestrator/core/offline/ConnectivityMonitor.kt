package com.workflow.orchestrator.core.offline

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.ServiceType
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class ConnectivityMonitor(private val project: Project) {

    private val statuses = ConcurrentHashMap<ServiceType, ServiceStatus>()

    fun statusOf(service: ServiceType): ServiceStatus {
        return statuses.getOrDefault(service, ServiceStatus.UNKNOWN)
    }

    fun markOnline(service: ServiceType) {
        statuses[service] = ServiceStatus.ONLINE
    }

    fun markOffline(service: ServiceType) {
        statuses[service] = ServiceStatus.OFFLINE
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
