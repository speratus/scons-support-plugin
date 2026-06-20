package space.luchuktech.sconssupport.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "SConsProjectSettings", storages = [Storage("scons.xml")])
class SConsProjectSettings : PersistentStateComponent<SConsProjectSettings.State> {
    data class State(
        var pythonPath: String = "",
        var sconsPath: String = "",
        var sconsArgs: String = "",
        var autoSync: Boolean = true,
        var generateCompileCommands: Boolean = true,
        var compileCommandsOnFirstSync: Boolean = true
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(project: Project): SConsProjectSettings = project.getService(SConsProjectSettings::class.java)
    }
}
