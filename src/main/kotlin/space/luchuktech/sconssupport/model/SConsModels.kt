package space.luchuktech.sconssupport.model

data class SConsTarget(
    val name: String,
    val type: String,
    val sources: List<String>,
    val env: Map<String, Any>
)

data class SConsOption(
    val key: String,
    val help: String,
    val default: String,
    val type: String,
    val allowed_values: List<String>? = null
)

data class SConsProjectModel(
    val targets: List<SConsTarget>,
    val options: List<SConsOption>
)
