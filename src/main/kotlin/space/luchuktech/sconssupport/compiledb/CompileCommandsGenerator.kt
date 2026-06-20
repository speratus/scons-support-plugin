package space.luchuktech.sconssupport.compiledb

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Path
import kotlin.io.path.*

data class CompileCommandEntry(
    val directory: String,
    val command:   String,
    val file:      String,
    val output:    String? = null
)

object CompileCommandsGenerator {
    private val LOG = Logger.getInstance(CompileCommandsGenerator::class.java)

    private val COMPILER_PATTERN = Regex(
        """(?:^|\s)((?:gcc|g\+\+|clang|clang\+\+|cc|c\+\+|cl\.exe)(?:\.exe)?)\s+(.+)""",
        setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)
    )

    fun generate(projectRoot: Path, dryRunOutput: String) {
        val entries = mutableListOf<CompileCommandEntry>()

        val matches = COMPILER_PATTERN.findAll(dryRunOutput)
        for (match in matches) {
            val compiler = match.groupValues[1]
            val args = match.groupValues[2]

            // Skip if it looks like a link step (no -c or /c)
            if (!args.contains("-c") && !args.contains("/c") && !args.lowercase().contains(".c")) continue

            val sourceFile = extractSourceFile(args) ?: continue
            val outputFile = extractOutputFile(args)

            entries += CompileCommandEntry(
                directory = projectRoot.toString(),
                command   = "$compiler $args",
                file      = projectRoot.resolve(sourceFile).normalize().toString(),
                output    = outputFile?.let { projectRoot.resolve(it).normalize().toString() }
            )
        }

        if (entries.isEmpty()) return

        val json = buildString {
            appendLine("[")
            entries.forEachIndexed { i, e ->
                appendLine("  {")
                appendLine("""    "directory": ${jsonQuote(e.directory)},""")
                appendLine("""    "command": ${jsonQuote(e.command)},""")
                appendLine("""    "file": ${jsonQuote(e.file)}""")
                if (i < entries.lastIndex) appendLine("  },") else appendLine("  }")
            }
            appendLine("]")
        }

        val outFile = projectRoot.resolve("compile_commands.json")
        outFile.writeText(json)
        logEntry(projectRoot, "Wrote ${entries.size} entries to $outFile")
    }

    private fun extractSourceFile(args: String): String? {
        val parts = args.split(Regex("""\s+"""))
        for (i in parts.indices) {
            if (parts[i] == "-c" && i + 1 < parts.size) return parts[i + 1]
            if (parts[i].endsWith(".c") || parts[i].endsWith(".cpp") || parts[i].endsWith(".cc")) return parts[i]
        }
        return null
    }

    private fun extractOutputFile(args: String): String? {
        val parts = args.split(Regex("""\s+"""))
        for (i in parts.indices) {
            if (parts[i] == "-o" && i + 1 < parts.size) return parts[i + 1]
        }
        return null
    }

    private fun jsonQuote(s: String): String {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private fun logEntry(projectRoot: Path, message: String) {
        try {
            val logFile = projectRoot.resolve(".idea/scons-plugin.log")
            if (!logFile.parent.exists()) {
                logFile.parent.createDirectories()
            }
            if (!logFile.exists()) {
                logFile.createFile()
            }
            val timestamp = java.time.Instant.now().toString()
            logFile.appendText("$timestamp [INFO ] $message\n")
        } catch (e: Exception) {
            LOG.warn("Failed to write to scons-plugin.log", e)
        }
    }
}
