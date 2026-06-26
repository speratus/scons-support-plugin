package space.luchuktech.sconssupport.watcher

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import space.luchuktech.sconssupport.settings.SConsProjectSettings

class SConstructFileListener(private val project: Project) : BulkFileListener {

    private val sconsToolWindow: ToolWindow? by lazy {
        ToolWindowManager.getInstance(project).getToolWindow("SCons")
    }

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
        } else {
            return
        }

        val sconsEvents = events.filter { event ->
            val path = event.path

            path.endsWith("SConstruct") ||
                    path.endsWith("SConscript") ||
                    (path.contains("site_scons") && path.endsWith(".py"))
        }

        if (sconsEvents.any { event ->
            event is VFileCreateEvent
        }) {
            sconsToolWindow?.isAvailable = true
        } else if (sconsEvents.any { event ->
            event is VFileDeleteEvent
        }) {
            sconsToolWindow?.isAvailable = false
        }
    }

}
