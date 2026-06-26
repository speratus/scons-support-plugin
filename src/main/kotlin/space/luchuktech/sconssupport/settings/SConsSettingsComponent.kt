package space.luchuktech.sconssupport.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JPanel

class SConsSettingsComponent {
    val panel: JPanel
    private val pythonPathField = TextFieldWithBrowseButton()
    private val sconsPathField = TextFieldWithBrowseButton()
    private val sconsArgsField = JBTextField()
    private val autoSyncCheck = JBCheckBox("Auto-sync when SConstruct changes")
    private val generateCompileCommandsCheck = JBCheckBox("Generate compile_commands.json")

    init {
        sconsPathField.addBrowseFolderListener(
            TextBrowseFolderListener(
                FileChooserDescriptorFactory.singleFile().apply {
                    withFileFilter {
                        it.toNioPath().toFile().canExecute()
                    }
                    title = "Choose SCons executable"
                }
            )
        )

        pythonPathField.addBrowseFolderListener(
            TextBrowseFolderListener(
                FileChooserDescriptorFactory.singleFile().apply {
                    withFileFilter {
                        it.toNioPath().toFile().canExecute()
                    }
                    title = "Choose Python executable"
                }
            )
        )

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Python interpreter path:"), pythonPathField, 1, false)
            .addLabeledComponent(JBLabel("SCons script path:"), sconsPathField, 1, false)
            .addLabeledComponent(JBLabel("Extra SCons arguments:"), sconsArgsField, 1, false)
            .addComponent(autoSyncCheck)
            .addComponent(generateCompileCommandsCheck)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    var pythonPath: String
        get() = pythonPathField.text
        set(value) { pythonPathField.text = value }

    var sconsPath: String
        get() = sconsPathField.text
        set(value) { sconsPathField.text = value }

    var sconsArgs: String
        get() = sconsArgsField.text
        set(value) { sconsArgsField.text = value }

    var autoSync: Boolean
        get() = autoSyncCheck.isSelected
        set(value) { autoSyncCheck.isSelected = value }

    var generateCompileCommands: Boolean
        get() = generateCompileCommandsCheck.isSelected
        set(value) { generateCompileCommandsCheck.isSelected = value }
}
