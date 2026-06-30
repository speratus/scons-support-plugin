package space.luchuktech.sconssupport.introspection

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import space.luchuktech.sconssupport.settings.SConsProjectSettings
import java.nio.file.Path

data class RunResult(val stdout: String, val stderr: String, val exitCode: Int)

object SConsRunner {

//    private val LOG = logger<SConsRunner>()

    fun dryRun(
        projectRoot: Path,
        settings: SConsProjectSettings,
        extraArgs: List<String> = emptyList(),
        indicator: ProgressIndicator,
        silent: Boolean = true
    ): RunResult {
        val state = settings.state
//        val python = state.pythonPath.ifBlank { "python3" }
         val scons = state.sconsPath.ifBlank {
             if (SystemInfo.isWindows) {
                 return@ifBlank "scons.cmd"
             }
             "scons"
         }

        require(extraArgs.none { it == "--no-dry-run" }) {
            "SCons must never be invoked without --dry-run during introspection"
        }

        val cmd = mutableListOf(scons, "-n")
        if (silent) {
            cmd.add("-Q")
            cmd.add("--silent")
        }
        cmd.addAll(extraArgs)

        var commandLine = GeneralCommandLine(cmd)
            .withWorkDirectory(projectRoot.toFile())
//            .withEnvironment(System.getenv())
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

        commandLine = commandLine.withEnvironment(System.getenv())

        val handler = OSProcessHandler(commandLine)
        val stdoutBuilder = StringBuilder()
        val stderrBuilder = StringBuilder()

        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType.toString() == "stdout") {
                    stdoutBuilder.append(event.text)
                } else if (outputType.toString() == "stderr") {
                    stderrBuilder.append(event.text)
                }
            }
        })

        handler.startNotify()
        
        while (!handler.isProcessTerminated) {
            if (indicator.isCanceled) {
                handler.destroyProcess()
                return RunResult(stdoutBuilder.toString(), stderrBuilder.toString(), -1)
            }
            handler.waitFor(100)
        }

        return RunResult(stdoutBuilder.toString(), stderrBuilder.toString(), handler.exitCode ?: -1)
    }
}
