package space.luchuktech.sconssupport.toolwindow

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import space.luchuktech.sconssupport.SConsBundle
import space.luchuktech.sconssupport.SConsIcons
import space.luchuktech.sconssupport.compiledb.CompileCommandsGenerator
import space.luchuktech.sconssupport.introspection.*
import space.luchuktech.sconssupport.model.SConsProjectModel
import space.luchuktech.sconssupport.model.SConsTarget
import space.luchuktech.sconssupport.settings.SConsProjectSettings
import java.awt.BorderLayout
import java.nio.file.Paths
import java.security.MessageDigest
import javax.swing.*
import javax.swing.table.DefaultTableModel

class SConsToolWindowPanel(private val project: Project) : SimpleToolWindowPanel(true) {
    private val targetList = JBList<String>()
    private val optionsTable = JBTable(DefaultTableModel(arrayOf("Key", "Value", "Help"), 0))
    private val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
    private var currentModel: SConsProjectModel? = null

    private var lastStdoutHash: String? = null

    companion object {
        val SCONS_MODEL_KEY = Key.create<SConsProjectModel>("SCONS_MODEL_KEY")
    }

    init {
        val actionGroup = DefaultActionGroup()
        actionGroup.add(object : AnAction(SConsBundle.message("scons.sync.action"), null, SConsIcons.SCons) {
            override fun actionPerformed(e: AnActionEvent) {
                sync()
            }
        })
        actionGroup.add(object : AnAction(SConsBundle.message("scons.run.action"), null, com.intellij.icons.AllIcons.Actions.Execute) {
            override fun actionPerformed(e: AnActionEvent) {
                runTarget()
            }
        })

        val toolbar = ActionManager.getInstance().createActionToolbar("SConsToolbar", actionGroup, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        val mainSplitter = JBSplitter(true, 0.6f)
        val topSplitter = JBSplitter(false, 0.5f)

        topSplitter.firstComponent = JBScrollPane(targetList)
        topSplitter.secondComponent = JBScrollPane(optionsTable)

        mainSplitter.firstComponent = topSplitter
        mainSplitter.secondComponent = console.component

        setContent(mainSplitter)
    }

    fun updateFromModel() {
        val model = project.getUserData(SCONS_MODEL_KEY) ?: return
        currentModel = model
        updateUI(model)
    }

    private fun sync() {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, SConsBundle.message("scons.status.syncing")) {
            override fun run(indicator: ProgressIndicator) {
                val projectRoot = Paths.get(project.basePath!!)
                val settings = SConsProjectSettings.getInstance(project)
                
                SConsShimInstaller.install(projectRoot)
                // Use verbose output if compile commands generation is enabled
                val silent = !settings.state.generateCompileCommands
                val result = SConsRunner.dryRun(projectRoot, settings, emptyList(), indicator, silent)
                
                if (result.exitCode == 0) {
                    val model = SConsModelBuilder.parse(result.stdout)
                    currentModel = model
                    project.putUserData(SCONS_MODEL_KEY, model)
                    
                    if (settings.state.generateCompileCommands) {
                        val currentHash = sha256(result.stdout)
                        if (currentHash != lastStdoutHash) {
                            CompileCommandsGenerator.generate(projectRoot, result.stdout)
                            lastStdoutHash = currentHash
                            com.intellij.openapi.vfs.VirtualFileManager.getInstance().asyncRefresh(null)
                        }
                    }

                    ApplicationManager.getApplication().invokeLater {
                        updateUI(model)
                    }
                } else {
                    ApplicationManager.getApplication().invokeLater {
                        console.print(result.stderr, com.intellij.execution.ui.ConsoleViewContentType.ERROR_OUTPUT)
                    }
                }
            }
        })
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun updateUI(model: SConsProjectModel) {
        val targetModel = DefaultListModel<String>()
        model.targets.forEach { targetModel.addElement("${it.name} (${it.type})") }
        targetList.model = targetModel

        val tableModel = optionsTable.model as DefaultTableModel
        tableModel.rowCount = 0
        model.options.forEach { option ->
            tableModel.addRow(arrayOf(option.key, option.default, option.help))
        }
    }

    private fun runTarget() {
        val selectedTargetStr = targetList.selectedValue ?: return
        val targetName = selectedTargetStr.substringBefore(" (")
        
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "SCons: running $targetName") {
            override fun run(indicator: ProgressIndicator) {
                val projectRoot = Paths.get(project.basePath!!)
                val settings = SConsProjectSettings.getInstance(project)
                val state = settings.state
                
                val python = state.pythonPath.ifBlank { "python3" }
                val scons = state.sconsPath.ifBlank {
                    if (SystemInfo.isWindows) {
                        return@ifBlank "scons.cmd"
                    }
                    "scons"
                }
                val cmd = mutableListOf(scons, targetName)
                
                // Add options from table
                val tableModel = optionsTable.model as DefaultTableModel
                for (i in 0 until tableModel.rowCount) {
                    val key = tableModel.getValueAt(i, 0) as String
                    val value = tableModel.getValueAt(i, 1) as String
                    cmd.add("$key=$value")
                }
                
                if (state.sconsArgs.isNotBlank()) {
                    cmd.addAll(state.sconsArgs.split(" "))
                }
                
                val commandLine = com.intellij.execution.configurations.GeneralCommandLine(cmd)
                    .withWorkDirectory(projectRoot.toFile())
                    .withParentEnvironmentType(com.intellij.execution.configurations.GeneralCommandLine.ParentEnvironmentType.CONSOLE)

                ApplicationManager.getApplication().invokeLater {
                    console.clear()
                    val handler = com.intellij.execution.process.OSProcessHandler(commandLine)
                    console.attachToProcess(handler)
                    handler.startNotify()
                }
            }
        })
    }
}
