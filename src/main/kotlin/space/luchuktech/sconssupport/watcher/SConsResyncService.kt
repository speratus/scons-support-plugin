package space.luchuktech.sconssupport.watcher

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import space.luchuktech.sconssupport.SConsBundle
import space.luchuktech.sconssupport.introspection.SConsModelBuilder
import space.luchuktech.sconssupport.introspection.SConsRunner
import space.luchuktech.sconssupport.introspection.SConsShimInstaller
import space.luchuktech.sconssupport.settings.SConsProjectSettings
import space.luchuktech.sconssupport.toolwindow.SConsToolWindowPanel
import java.nio.file.Paths
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class SConsResyncService(private val project: Project) {
    private val executor = AppExecutorUtil.getAppScheduledExecutorService()
    private var pending: ScheduledFuture<*>? = null

    fun scheduleResync(delayMs: Long = 500) {
        pending?.cancel(false)
        pending = executor.schedule({
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, SConsBundle.message("scons.status.syncing")) {
                override fun run(indicator: ProgressIndicator) {
                    val projectRoot = Paths.get(project.basePath!!)
                    val settings = SConsProjectSettings.getInstance(project)
                    
                    SConsShimInstaller.install(projectRoot)
                    val result = SConsRunner.dryRun(projectRoot, settings, emptyList(), indicator)
                    
                    if (result.exitCode == 0) {
                        val model = SConsModelBuilder.parse(result.stdout)
                        project.putUserData(SConsToolWindowPanel.SCONS_MODEL_KEY, model)
                        
                        ApplicationManager.getApplication().invokeLater {
                            // Find tool window and update it
                            val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("SCons")
                            val content = toolWindow?.contentManager?.getContent(0)
                            val panel = content?.component as? SConsToolWindowPanel
                            panel?.updateFromModel()
                        }
                    }
                }
            })
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    companion object {
        fun getInstance(project: Project): SConsResyncService = project.getService(SConsResyncService::class.java)
    }
}
