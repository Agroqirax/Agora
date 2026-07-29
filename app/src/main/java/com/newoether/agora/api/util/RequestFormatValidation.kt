package com.newoether.agora.api.util

import com.newoether.agora.api.ToolDefinition

/**
 * Raised only before opening an HTTP request. A provider request that cannot be proven to satisfy
 * its wire-format grammar must fail locally rather than relying on a remote 400 response.
 */
class RequestFormatException(
    val provider: String,
    val violations: List<String>,
) : IllegalStateException(
    "$provider request validation failed: ${violations.joinToString("; ")}"
)

internal fun requireValidRequestFormat(
    provider: String,
    violations: List<String>,
) {
    if (violations.isNotEmpty()) {
        throw RequestFormatException(provider, violations.distinct())
    }
}

internal fun validateToolDefinitions(tools: List<ToolDefinition>?): List<String> {
    if (tools.isNullOrEmpty()) return emptyList()
    val violations = mutableListOf<String>()
    val names = mutableSetOf<String>()
    tools.forEachIndexed { index, tool ->
        val function = tool.function
        if (tool.type != "function") violations += "tools[$index].type must be function"
        if (function.name.isBlank()) {
            violations += "tools[$index].function.name is blank"
        } else if (!names.add(function.name)) {
            violations += "duplicate tool name ${function.name}"
        }
        if (function.parameters.type != "object") {
            violations += "tool ${function.name} parameters must be an object"
        }
        val unknownRequired =
            function.parameters.required.toSet() - function.parameters.properties.keys
        if (unknownRequired.isNotEmpty()) {
            violations += "tool ${function.name} requires undefined properties"
        }
        function.parameters.properties.forEach { (propertyName, property) ->
            if (propertyName.isBlank()) {
                violations += "tool ${function.name} has a blank property name"
            }
            if (property.type.isBlank()) {
                violations += "tool ${function.name} property $propertyName has no type"
            }
            if (property.type == "array" && property.items == null) {
                violations += "tool ${function.name} array $propertyName has no items schema"
            }
        }
    }
    return violations
}
