package space.luchuktech.sconssupport.introspection

import com.intellij.openapi.diagnostic.Logger
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.*

object SConsShimInstaller {
    private val LOG = Logger.getInstance(SConsShimInstaller::class.java)
    private const val SENTINEL_BEGIN = "# >>>>> scons-plugin introspect begin <<<<<"
    private const val SENTINEL_END   = "# >>>>> scons-plugin introspect end <<<<<"

    fun install(projectRoot: Path) {
        val shimStream = javaClass.getResourceAsStream("/shims/scons_introspect.py")
        if (shimStream == null) {
            LOG.error("Could not find /shims/scons_introspect.py in resources")
            return
        }

        val bomStream = shimStream.buffered()

        val bom = ByteArray(3)
        bomStream.read(bom)

        if (bom[0] != 0xEF.toByte() || bom[1] != 0xBB.toByte() || bom[2] != 0xBF.toByte()) {
            bomStream.reset()
        }

        val shimText = bomStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val siteScons = projectRoot.resolve("site_scons")
        val target = siteScons.resolve("site_init.py")

        if (!siteScons.exists()) {
            siteScons.createDirectories()
        }

        val existing = if (target.exists()) target.readText() else ""
        if (existing.contains(SENTINEL_BEGIN)) return

        val block = "\n$SENTINEL_BEGIN\n$shimText\n$SENTINEL_END\n"
        target.writeText(existing + block, StandardCharsets.UTF_8)
        logEntry(projectRoot, "Installed introspection shim into $target")
    }

    fun uninstall(projectRoot: Path) {
        val target = projectRoot.resolve("site_scons/site_init.py")
        if (!target.exists()) return

        val text = target.readText()
        if (!text.contains(SENTINEL_BEGIN)) return

        val newText = text.replace(Regex("\n?$SENTINEL_BEGIN.*?$SENTINEL_END\n?", RegexOption.DOT_MATCHES_ALL), "")
        target.writeText(newText)
        logEntry(projectRoot, "Uninstalled introspection shim from $target")
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
