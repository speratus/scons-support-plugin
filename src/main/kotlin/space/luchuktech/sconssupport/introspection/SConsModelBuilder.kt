package space.luchuktech.sconssupport.introspection

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import space.luchuktech.sconssupport.model.*

object SConsModelBuilder {
    private val LOG = Logger.getInstance(SConsModelBuilder::class.java)
    private val gson = Gson()

    fun parse(stdout: String): SConsProjectModel {
        val jsonStart = stdout.indexOf("---SCONS_INTROSPECT_BEGIN---")
        val jsonEnd = stdout.indexOf("---SCONS_INTROSPECT_END---")

        val json = if (jsonStart != -1 && jsonEnd != -1) {
            stdout.substring(jsonStart + "---SCONS_INTROSPECT_BEGIN---".length, jsonEnd).trim()
        } else {
            // Fallback: maybe it's just raw JSON
            stdout.trim()
        }

        return try {
            val rawModel = gson.fromJson(json, RawProjectModel::class.java)
            if (rawModel.schema_version != 1) {
                LOG.warn("Unexpected schema version: ${rawModel.schema_version}")
            }
            SConsProjectModel(
                targets = rawModel.targets.map { it.toTarget() },
                options = rawModel.options
            )
        } catch (e: Exception) {
            LOG.error("Failed to parse SCons introspection JSON", e)
            SConsProjectModel(emptyList(), emptyList())
        }
    }

    private data class RawProjectModel(
        val schema_version: Int,
        val targets: List<RawTarget>,
        val options: List<SConsOption>
    )

    private data class RawTarget(
        val name: String,
        val type: String,
        val sources: List<String>,
        val env: Map<String, Any>
    ) {
        fun toTarget() = SConsTarget(name, type, sources, env)
    }
}
