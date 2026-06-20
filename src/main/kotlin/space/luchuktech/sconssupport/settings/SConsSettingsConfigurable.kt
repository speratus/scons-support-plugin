package space.luchuktech.sconssupport.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import space.luchuktech.sconssupport.SConsBundle
import javax.swing.JComponent

class SConsSettingsConfigurable(private val project: Project) : Configurable {
    private var component: SConsSettingsComponent? = null

    override fun getDisplayName(): String = SConsBundle.message("scons.settings.displayName")

    override fun createComponent(): JComponent {
        component = SConsSettingsComponent()
        return component!!.panel
    }

    override fun isModified(): Boolean {
        val state = SConsProjectSettings.getInstance(project).state
        val comp = component ?: return false
        return comp.pythonPath != state.pythonPath ||
                comp.sconsPath != state.sconsPath ||
                comp.sconsArgs != state.sconsArgs ||
                comp.autoSync != state.autoSync ||
                comp.generateCompileCommands != state.generateCompileCommands
    }

    override fun apply() {
        val state = SConsProjectSettings.getInstance(project).state
        val comp = component ?: return
        state.pythonPath = comp.pythonPath
        state.sconsPath = comp.sconsPath
        state.sconsArgs = comp.sconsArgs
        state.autoSync = comp.autoSync
        state.generateCompileCommands = comp.generateCompileCommands
    }

    override fun reset() {
        val state = SConsProjectSettings.getInstance(project).state
        val comp = component ?: return
        comp.pythonPath = state.pythonPath
        comp.sconsPath = state.sconsPath
        comp.sconsArgs = state.sconsArgs
        comp.autoSync = state.autoSync
        comp.generateCompileCommands = state.generateCompileCommands
    }

    override fun disposeUIResources() {
        component = null
    }
}
