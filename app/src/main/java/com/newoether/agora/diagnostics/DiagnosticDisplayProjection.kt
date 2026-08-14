package com.newoether.agora.diagnostics

import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.replaceCustomProviderIdsForDisplay

internal fun DiagnosticSnapshot.forDisplay(
    customProviders: List<CustomProviderConfig>,
): DiagnosticSnapshot = copy(
    events = events.map { it.forDisplay(customProviders) },
)

internal fun DeveloperConversationInspection.forDisplay(
    customProviders: List<CustomProviderConfig>,
): DeveloperConversationInspection = copy(
    model = model.displayValueOrNull(customProviders),
    origin = origin.displayValue(customProviders),
    participantCounts = participantCounts.displayCounts(customProviders),
    statusCounts = statusCounts.displayCounts(customProviders),
    messages = messages.map { message ->
        message.copy(
            participant = message.participant.displayValue(customProviders),
            status = message.status.displayValue(customProviders),
            model = message.model.displayValueOrNull(customProviders),
        )
    },
    runtimeTransitions = runtimeTransitions.map { transition ->
        transition.copy(
            oldState = transition.oldState.displayValue(customProviders),
            commandType = transition.commandType.displayValue(customProviders),
            newState = transition.newState.displayValue(customProviders),
            effectTypes = transition.effectTypes.map { it.displayValue(customProviders) },
        )
    },
)

private fun DiagnosticEvent.forDisplay(
    customProviders: List<CustomProviderConfig>,
): DiagnosticEvent = copy(
    context = context.forDisplay(customProviders),
    payload = payload.forDisplay(customProviders),
)

private fun DiagnosticRequestContext.forDisplay(
    customProviders: List<CustomProviderConfig>,
): DiagnosticRequestContext = copy(
    requestId = requestId.displayValueOrNull(customProviders),
    conversationIdHash = conversationIdHash.displayValueOrNull(customProviders),
    runId = runId.displayValueOrNull(customProviders),
    provider = provider.displayValueOrNull(customProviders),
    model = model.displayValueOrNull(customProviders),
    requestKind = requestKind.displayValueOrNull(customProviders),
)

private fun DiagnosticEventPayload.forDisplay(
    customProviders: List<CustomProviderConfig>,
): DiagnosticEventPayload = when (this) {
    is DiagnosticEventPayload.RuntimeTransition -> copy(
        oldState = oldState.displayValue(customProviders),
        commandType = commandType.displayValue(customProviders),
        newState = newState.displayValue(customProviders),
        effectId = effectId.displayValueOrNull(customProviders),
        effectTypes = effectTypes.map { it.displayValue(customProviders) },
    )
    is DiagnosticEventPayload.HttpStage -> copy(
        stage = stage.displayValue(customProviders),
        attributes = attributes.displayValues(customProviders),
    )
    is DiagnosticEventPayload.HttpRequest -> copy(
        method = method.displayValue(customProviders),
        url = url.forDisplay(customProviders),
        headers = headers.displayValues(customProviders),
        body = body.forDisplay(customProviders),
    )
    is DiagnosticEventPayload.HttpResponseBody -> copy(
        body = body.forDisplay(customProviders),
    )
    is DiagnosticEventPayload.WireLine -> copy(
        line = line.forDisplay(customProviders),
    )
    is DiagnosticEventPayload.ParsedStreamEvent -> copy(
        eventType = eventType.displayValue(customProviders),
        attributes = attributes.displayValues(customProviders),
        content = content?.forDisplay(customProviders),
    )
}

private fun CapturedDiagnosticText.forDisplay(
    customProviders: List<CustomProviderConfig>,
): CapturedDiagnosticText = copy(
    value = value.displayValue(customProviders),
)

private fun Map<String, String>.displayValues(
    customProviders: List<CustomProviderConfig>,
): Map<String, String> = entries.associate { (key, value) ->
    key.displayValue(customProviders) to value.displayValue(customProviders)
}

private fun Map<String, Int>.displayCounts(
    customProviders: List<CustomProviderConfig>,
): Map<String, Int> = entries.associate { (key, value) ->
    key.displayValue(customProviders) to value
}

private fun String.displayValue(
    customProviders: List<CustomProviderConfig>,
): String = replaceCustomProviderIdsForDisplay(this, customProviders)

private fun String?.displayValueOrNull(
    customProviders: List<CustomProviderConfig>,
): String? = this?.displayValue(customProviders)
