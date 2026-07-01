package space.luchuktech.sconssupport.notification

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.WindowManager
import space.luchuktech.sconssupport.settings.SConsSettingsConfigurable

object NotificationDispatcher {

    fun createErrorNotification(content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("SCons Error")
            .createNotification(content, NotificationType.ERROR)
            .notify(null)
    }

}