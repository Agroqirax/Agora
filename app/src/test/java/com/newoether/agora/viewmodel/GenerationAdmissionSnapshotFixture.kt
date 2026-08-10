package com.newoether.agora.viewmodel

import com.newoether.agora.api.LlmProvider
import io.mockk.mockk

internal fun testGenerationAdmissionSnapshot(
    conversationId: String = "conversation",
    runId: String = "run",
    selectedModelId: String = "provider:model",
    contextWindow: Int = 4096,
    apiKey: String = "active-key",
): GenerationAdmissionSnapshot {
    val providerName = selectedModelId.substringBefore(':')
    val provider = mockk<LlmProvider>()
    val context = GenerationContext(conversationId = conversationId)
    return GenerationAdmissionSnapshot(
        conversationId = conversationId,
        runId = runId,
        selectedModelId = selectedModelId,
        config = GenerationConfig(
            providerName = providerName,
            modelId = selectedModelId.substringAfter(':'),
            apiKey = apiKey,
            effectiveSystemPrompt = "system",
            maxContextWindow = contextWindow,
            codeExecutionEnabled = false,
            googleSearchEnabled = false,
            thinkingEnabled = false,
            baseUrl = "https://provider.invalid",
        ),
        providerInstances = mapOf(providerName to provider),
        context = context,
        automaticCompact = AutomaticCompactConfig(
            enabled = true,
            request = CompactRequest(
                model = selectedModelId,
                prompt = "compact prompt",
                retainLogicalMessages = 3,
            ),
            providerName = providerName,
            apiKey = apiKey,
            baseUrl = "https://provider.invalid",
            provider = provider,
            configured = true,
            generationContext = context,
        ),
        titleGenerationEnabled = true,
    )
}
