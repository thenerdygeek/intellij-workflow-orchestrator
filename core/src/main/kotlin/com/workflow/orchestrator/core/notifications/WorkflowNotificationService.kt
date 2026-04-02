package com.workflow.orchestrator.core.notifications

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class WorkflowNotificationService(private val project: Project) {

    private val log = Logger.getInstance(WorkflowNotificationService::class.java)

    fun notifyInfo(groupId: String, title: String, content: String) {
        notify(groupId, title, content, NotificationType.INFORMATION)
    }

    fun notifyWarning(groupId: String, title: String, content: String) {
        notify(groupId, title, content, NotificationType.WARNING)
    }

    fun notifyError(groupId: String, title: String, content: String) {
        notify(groupId, title, content, NotificationType.ERROR)
    }

    private fun notify(groupId: String, title: String, content: String, type: NotificationType) {
        log.info("[Core:Notifications] Sending ${type.name} notification — group=$groupId, title=\"$title\"")
        NotificationGroupManager.getInstance()
            .getNotificationGroup(groupId)
            .createNotification(title, content, type)
            .notify(project)
    }

    companion object {
        const val GROUP_BUILD = "workflow.build"
        const val GROUP_QUALITY = "workflow.quality"

        fun getInstance(project: Project): WorkflowNotificationService {
            return project.getService(WorkflowNotificationService::class.java)
        }
    }
}
