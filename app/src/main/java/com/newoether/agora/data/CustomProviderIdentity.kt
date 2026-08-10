package com.newoether.agora.data

import com.newoether.agora.model.ModelId
import com.newoether.agora.model.apiModelName
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

internal data class CustomProviderIdentityMigration(
    val legacyReference: String,
    val providerId: String,
)

internal data class CustomProviderIdentityNormalization(
    val providers: List<CustomProviderConfig>,
    val migrations: List<CustomProviderIdentityMigration>,
)

/** Pure identity policy shared by DataStore normalization, import, runtime lookup, and tests. */
internal object CustomProviderIdentityPolicy {
    private const val PREFIX = "custom-provider-"
    private val stableIdPattern = Regex(
        "^custom-provider-[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        RegexOption.IGNORE_CASE,
    )

    fun newId(): String = PREFIX + UUID.randomUUID().toString()

    /** Stable only for upgrading a legacy name-keyed config; persisted IDs never change on rename. */
    fun legacyId(name: String): String = PREFIX + UUID.nameUUIDFromBytes(
        ("agora/custom-provider/legacy/" + name.trim().lowercase(Locale.ROOT))
            .toByteArray(StandardCharsets.UTF_8),
    ).toString()

    fun isStableId(value: String): Boolean = stableIdPattern.matches(value.trim())

    fun normalize(
        rawProviders: List<CustomProviderConfig>,
        occupiedIds: Set<String> = emptySet(),
        newId: (CustomProviderConfig) -> String = { newId() },
    ): CustomProviderIdentityNormalization {
        val sanitized = CustomProviderNamePolicy.sanitize(rawProviders).accepted
        val usedIds = occupiedIds.filterTo(linkedSetOf(), ::isStableId)
        val normalized = sanitized.map { raw ->
            val originalId = raw.id.trim()
            val retainedId = originalId.takeIf { isStableId(it) && it !in usedIds }
            val stableId = retainedId ?: generateUniqueId(raw, usedIds, newId)
            usedIds += stableId
            val legacyNames = buildSet {
                addAll(raw.legacyNames.map(String::trim).filter(String::isNotEmpty))
                if (retainedId == null) add(raw.name.trim())
            }.filterNotTo(linkedSetOf()) { it == stableId }
            raw.copy(
                name = raw.name.trim(),
                id = stableId,
                legacyNames = legacyNames,
            )
        }
        return CustomProviderIdentityNormalization(
            providers = normalized,
            migrations = normalized.flatMap { provider ->
                provider.legacyNames.map { legacy ->
                    CustomProviderIdentityMigration(legacy, provider.id)
                }
            }.distinct(),
        )
    }

    private fun generateUniqueId(
        provider: CustomProviderConfig,
        usedIds: Set<String>,
        newId: (CustomProviderConfig) -> String,
    ): String {
        repeat(100) { attempt ->
            val candidate = if (attempt == 0) {
                newId(provider).trim()
            } else {
                CustomProviderIdentityPolicy.newId()
            }
            if (isStableId(candidate) && candidate !in usedIds) return candidate
        }
        error("Unable to allocate a unique custom provider ID")
    }
}

internal fun String.remapProviderReference(
    migrations: Map<String, String>,
): String {
    val match = migrations.entries
        .asSequence()
        .filter { (legacy, _) -> legacy.isNotEmpty() && startsWith("$legacy:") }
        .maxByOrNull { (legacy, _) -> legacy.length }
        ?: return this
    return match.value + removePrefix(match.key)
}

internal fun remapModelAliases(
    aliases: Map<String, String>,
    migrations: Map<String, String>,
): Map<String, String> {
    val result = linkedMapOf<String, String>()
    aliases.forEach { (modelId, alias) ->
        if (modelId.remapProviderReference(migrations) == modelId) result[modelId] = alias
    }
    aliases.forEach { (modelId, alias) ->
        val remapped = modelId.remapProviderReference(migrations)
        if (remapped != modelId) result.putIfAbsent(remapped, alias)
    }
    return result
}

internal fun canonicalCustomModelId(
    modelId: String,
    customProviders: List<CustomProviderConfig>,
): String = modelId.remapProviderReference(
    customProviders.flatMap { provider ->
        provider.legacyNames.map { legacy -> legacy to provider.providerId } +
            (provider.name to provider.providerId)
    }.toMap(),
)

/**
 * Repairs the short-lived development-build failure where a stale legacy provider write could
 * replace an already-normalized random ID while aliases remained under the first ID. The repair
 * is intentionally conservative: an orphan namespace moves only when the current model catalog
 * identifies exactly one active custom provider. Ambiguous aliases remain untouched.
 */
internal fun repairOrphanedCustomProviderAliases(
    aliases: Map<String, String>,
    knownModelReferences: Collection<String>,
    activeProviderIds: Set<String>,
): Map<String, String> {
    val activeModels = knownModelReferences.asSequence()
        .map(ModelId::parse)
        .filter { it.providerName in activeProviderIds }
        .groupBy(ModelId::providerName, ModelId::modelName)
        .mapValues { (_, models) -> models.toSet() }
    val orphanModels = aliases.keys.asSequence()
        .map(ModelId::parse)
        .filter {
            CustomProviderIdentityPolicy.isStableId(it.providerName) &&
                it.providerName !in activeProviderIds
        }
        .groupBy(ModelId::providerName, ModelId::modelName)
        .mapValues { (_, models) -> models.toSet() }
    val recoveredProviders = orphanModels.mapNotNull { (orphanId, models) ->
        val scored = activeModels.mapValues { (_, active) -> models.count(active::contains) }
        val bestScore = scored.values.maxOrNull() ?: 0
        val candidates = scored.filterValues { it == bestScore && it > 0 }.keys
        candidates.singleOrNull()?.let { orphanId to it }
    }.toMap()
    if (recoveredProviders.isEmpty()) return aliases

    val result = linkedMapOf<String, String>()
    aliases.forEach { (modelId, alias) ->
        val parsed = ModelId.parse(modelId)
        if (parsed.providerName !in recoveredProviders) result[modelId] = alias
    }
    aliases.forEach { (modelId, alias) ->
        val parsed = ModelId.parse(modelId)
        val targetProvider = recoveredProviders[parsed.providerName] ?: return@forEach
        result.putIfAbsent(ModelId(targetProvider, parsed.modelName).prefixed, alias)
    }
    return result
}

fun providerDisplayName(
    providerReference: String,
    customProviders: List<CustomProviderConfig>,
): String = customProviders.firstOrNull { provider ->
    provider.ownsIdentity(providerReference) || provider.name == providerReference
}?.name ?: if (CustomProviderIdentityPolicy.isStableId(providerReference)) {
    "Custom"
} else {
    providerReference
}

fun modelDisplayName(
    modelId: String,
    aliases: Map<String, String>,
    customProviders: List<CustomProviderConfig>,
): String {
    aliases[modelId]?.takeIf(String::isNotBlank)?.let { return it }
    val parsed = ModelId.parse(modelId)
    return "${parsed.apiModelName} (${providerDisplayName(parsed.providerName, customProviders)})"
}
