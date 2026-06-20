package space.luchuktech.sconssupport.watcher

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import space.luchuktech.sconssupport.settings.SConsProjectSettings

class SConstructFileListener(private val project: Project) : BulkFileListener {

    override fun after(events: List<VFileEvent>) {
        if (!SConsProjectSettings.getInstance(project).state.autoSync) return

        val relevant = events.any { event ->
            val path = event.path
            path.endsWith("SConstruct") ||
            path.endsWith("SConscript") ||
            (path.contains("site_scons") && path.endsWith(".py"))
        }

        if (relevant) {
            SConsResyncService.getInstance(project).scheduleResync()
        }
    }
}
