package com.newoether.agora

/**
 * `agora://send` deep link contract: lets an external launcher/shortcut (e.g. a Kvaesitso
 * quick action) open Agora into a new chat with a prefilled or auto-sent prompt and an
 * optional model override. See MainActivity.registerIfQuickLaunch() and
 * ChatViewModel.handleQuickLaunch().
 *
 * Shares the `agora` scheme with AppAuth's OAuth redirect (`agora://oauth/callback`, see
 * tool/McpOAuthManager.kt's MCP_OAUTH_REDIRECT_URI) — the host is what separates them.
 * That only works because AndroidManifest.xml carries an explicit override of AppAuth's
 * RedirectUriReceiverActivity that scopes its filter to host "oauth" (its own intent-filter,
 * as merged in from the library, declares only a scheme with no host, which Android treats
 * as matching every host for that scheme — i.e. it would otherwise also swallow this URI).
 * If that override ever gets dropped, host separation silently stops working; pair any
 * change to one with a check of the other.
 *
 * Example: agora://send?prompt=Summarize+this&model=OpenAI%3Agpt-5&autoSend=true
 */
object QuickLaunch {
    const val SCHEME = "agora"
    const val HOST = "send"

    /** Free-text prompt to prefill (or auto-send) into a new chat. */
    const val PARAM_PROMPT = "prompt"

    /** Model id in "Provider:modelId" form (see SettingsManager.SELECTED_MODEL), applied to
     *  the new chat only — does not change the app's default model. */
    const val PARAM_MODEL = "model"

    /** "true" to send [PARAM_PROMPT] immediately instead of just prefilling the composer.
     *  Ignored (treated as prefill-only) when [PARAM_PROMPT] is blank. */
    const val PARAM_AUTO_SEND = "autoSend"
}