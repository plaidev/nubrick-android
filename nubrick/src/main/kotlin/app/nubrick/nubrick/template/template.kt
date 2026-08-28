package app.nubrick.nubrick.template

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private data class Placeholder(val path: String, val formatter: String)

private val placeholderRegex = Regex("\\{\\{\\s*([a-zA-Z0-9_.-]{1,300})\\s*(?:\\|\\s*([a-zA-Z0-9_-]*)\\s*)?\\}\\}")

private fun parseToPlaceholder(value: String): Placeholder? {
    val match = placeholderRegex.matchEntire(value) ?: return null
    return Placeholder(
        path = match.groupValues[1],
        formatter = match.groupValues.getOrElse(2) { "" },
    )
}

internal fun hasPlaceholder(value: String): Boolean {
    return placeholderRegex.containsMatchIn(value)
}

internal fun hasDataPlaceholder(value: String): Boolean {
    return placeholderRegex.findAll(value).any { match ->
        val path = match.groupValues[1]
        path == "data" || path.startsWith("data.")
    }
}

internal fun variableByPath(path: String, variable: JsonElement?): JsonElement? {
    val keys = path.split(".")
    if (keys.isEmpty()) return null
    var current = variable
    for (key in keys) {
        if (key.isEmpty()) continue
        val obj = current as? JsonObject ?: return null
        current = obj[key]
    }
    return current
}

internal fun compile(template: String, variable: JsonElement?): String {
    return template.replace(placeholderRegex) {
        val placeholder = parseToPlaceholder(it.value) ?: return@replace ""
        val value = variableByPath(placeholder.path, variable)
        return@replace formatValue(placeholder.formatter, value)
    }
}
