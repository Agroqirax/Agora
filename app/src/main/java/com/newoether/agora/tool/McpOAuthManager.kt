package com.newoether.agora.tool

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.newoether.agora.MainActivity
import com.newoether.agora.api.HttpClient
import com.newoether.agora.data.McpServerConfig
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ClientAuthentication
import net.openid.appauth.ClientSecretBasic
import net.openid.appauth.ClientSecretPost
import net.openid.appauth.NoClientAuthentication
import net.openid.appauth.RegistrationRequest
import net.openid.appauth.ResponseTypeValues
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** The redirect URI every MCP OAuth flow registers/uses. Caught by AppAuth's own
 *  `net.openid.appauth.RedirectUriReceiverActivity` (declared via the `appAuthRedirectScheme`
 *  manifest placeholder in app/build.gradle.kts, matching on scheme only), which forwards it
 *  into `AuthorizationManagementActivity` and from there into the completed/canceled
 *  `PendingIntent`s built in [McpOAuthManager.buildAuthorizationRequest]. */
const val MCP_OAUTH_REDIRECT_URI = "agora://oauth/callback"

/** Action on the [Intent] AppAuth's completed/canceled `PendingIntent`s redeliver to
 *  [MainActivity] once the sign-in browser flow finishes (success, error, or user
 *  cancellation) — see MainActivity.registerIfMcpOAuthResult(). */
const val MCP_OAUTH_RESULT_ACTION = "com.newoether.agora.action.MCP_OAUTH_RESULT"

/** Extra on that same [Intent] carrying the [McpServerConfig.id] the finished sign-in was
 *  for. AppAuth's own `AuthorizationResponse`/`AuthorizationException` objects have no
 *  concept of Agora's server identity, so it rides along as a plain intent extra instead —
 *  this works uniformly for the success, error, and cancellation cases, unlike trying to
 *  recover it from the response's `state` (which an [AuthorizationException] doesn't carry). */
const val MCP_OAUTH_EXTRA_SERVER_ID = "mcp_oauth_server_id"

/** Metadata resolved via the MCP Authorization discovery chain (RFC 9728 protected-resource
 *  metadata → RFC 8414 authorization-server metadata), or entered manually when discovery
 *  fails/is unsupported. */
data class McpOAuthDiscoveryResult(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val registrationEndpoint: String,
    val scope: String,
    /** The exact `resource` identifier the protected-resource metadata document declares
     *  for itself (RFC 9728) — this is what must be sent as the `resource` parameter on
     *  every authorize/token/refresh request, since it may not be byte-identical to the
     *  MCP server URL the user typed (trailing slash, path normalization, etc.). Falls
     *  back to the MCP server URL when no protected-resource metadata was found. */
    val resource: String,
    /** Best guess at [McpServerConfig.oauthClientAuthMethod] from the AS metadata's
     *  `token_endpoint_auth_methods_supported` (RFC 8414) — "post" unless the server
     *  advertises `client_secret_basic` but not `client_secret_post`. Only matters once a
     *  client secret is actually entered (manual, non-DCR setup); ignored otherwise. */
    val clientAuthMethod: String = "post"
)

data class McpDcrResult(val clientId: String, val clientSecret: String)

/** Everything needed to launch one sign-in attempt via AppAuth's "managed" redirect flow:
 *  [completedIntent]/[canceledIntent] are `PendingIntent`s (targeting [MainActivity], see
 *  [MCP_OAUTH_RESULT_ACTION]) that `AuthorizationManagementActivity` fires once the browser
 *  flow finishes. */
data class McpOAuthAuthorizationRequest(
    val request: AuthorizationRequest,
    val completedIntent: PendingIntent,
    val canceledIntent: PendingIntent
)

/** Launches [authorizationRequest] via AppAuth's managed authorization flow
 *  (`AuthorizationService.performAuthorizationRequest`) — AppAuth's own
 *  `AuthorizationManagementActivity` opens the Custom Tab, tracks the in-flight request
 *  (surviving process death on its own), and fires [McpOAuthAuthorizationRequest.
 *  completedIntent]/[McpOAuthAuthorizationRequest.canceledIntent] once the browser redirects
 *  back. Stateless — a top-level function, not a method on [McpOAuthManager], so callers
 *  don't need a manager instance just to launch a request.
 *
 *  [context] should be an Activity context (e.g. Compose's `LocalContext.current`): AppAuth's
 *  `startActivity` call to open the Custom Tab isn't given `FLAG_ACTIVITY_NEW_TASK`, so an
 *  Application context would crash here rather than silently misbehave. */
fun launchMcpOAuthAuthorization(context: Context, authorizationRequest: McpOAuthAuthorizationRequest) {
    AuthorizationService(context).performAuthorizationRequest(
        authorizationRequest.request,
        authorizationRequest.completedIntent,
        authorizationRequest.canceledIntent,
        CustomTabsIntent.Builder().build()
    )
}

/**
 * OAuth 2.0 authorization-code + PKCE client for MCP servers, per the MCP Authorization
 * spec (aligned with OAuth 2.1): RFC 9728 protected-resource discovery, RFC 8414
 * authorization-server discovery, RFC 7591 dynamic client registration, RFC 7636 PKCE,
 * RFC 8707 resource indicators.
 *
 * PKCE, AS discovery (RFC 8414's `openid-configuration` variant), dynamic client
 * registration, token exchange/refresh, and client-authentication-method selection are
 * all delegated to AppAuth-Android (`net.openid:appauth`) — only the MCP-specific parts
 * AppAuth doesn't model (RFC 9728 protected-resource discovery, RFC 8707 resource-indicator
 * threading, and the RFC 8414 `oauth-authorization-server` discovery path AppAuth's own
 * `fetchFromIssuer` doesn't try) stay hand-rolled here, reusing the shared [HttpClient] and
 * manual `kotlinx.serialization.json` parsing for just those two metadata documents.
 *
 * Drives AppAuth's *managed* redirect-handling mode (`performAuthorizationRequest` +
 * `PendingIntent`s), not the manual mode (`getAuthorizationRequestIntent` +
 * `AuthorizationResponse.Builder`) — AppAuth's own `RedirectUriReceiverActivity` /
 * `AuthorizationManagementActivity` own the whole redirect round-trip, including surviving
 * process death, so Agora no longer needs its own pending-PKCE-request persistence or a
 * custom manifest intent-filter; it only needs to know which [McpServerConfig] a finished
 * sign-in was for, threaded through as a plain intent extra (see [MCP_OAUTH_EXTRA_SERVER_ID]).
 */
class McpOAuthManager(private val context: Context, private val settingsRepository: SettingsRepository) {

    private val json = Json { ignoreUnknownKeys = true }

    // Per-server-id cache of live AuthState instances. AppAuth's AuthState.
    // performActionWithFreshTokens coalesces concurrent refresh calls made against the
    // *same* AuthState object — reloading a fresh AuthState from persisted JSON on every
    // call would defeat that, since two independently-deserialized AuthState instances
    // would race on the same (possibly single-use, rotating) refresh token.
    private val authStates = ConcurrentHashMap<String, AuthState>()

    private val authService by lazy { AuthorizationService(context) }

    private fun loadAuthState(server: McpServerConfig): AuthState = authStates.getOrPut(server.id) {
        if (server.oauthAuthStateJson.isNotBlank()) {
            try { AuthState.jsonDeserialize(server.oauthAuthStateJson) } catch (_: Exception) { AuthState() }
        } else AuthState()
    }

    private fun clientAuthentication(server: McpServerConfig): ClientAuthentication = when (server.oauthClientAuthMethod) {
        "basic" -> ClientSecretBasic(server.oauthClientSecret)
        "none" -> NoClientAuthentication.INSTANCE
        else -> if (server.oauthClientSecret.isNotBlank()) ClientSecretPost(server.oauthClientSecret) else NoClientAuthentication.INSTANCE
    }

    private suspend fun persistAuthState(server: McpServerConfig, state: AuthState): McpServerConfig {
        val updated = server.copy(oauthAuthStateJson = state.jsonSerializeString(), oauthNeedsReauth = false)
        settingsRepository.updateMcpServer(updated)
        return updated
    }

    /** True once a sign-in has produced a still-known access/refresh token for [server]. */
    fun isConnected(server: McpServerConfig): Boolean = loadAuthState(server).isAuthorized

    /** The current access token for [server], if any — does not refresh; callers wanting
     *  a guaranteed-fresh token should call [ensureFreshAccessToken] first. */
    fun accessToken(server: McpServerConfig): String? = loadAuthState(server).accessToken

    // ── Discovery ───────────────────────────────────────────────

    /** Runs the full discovery chain for [mcpServerUrl]: protected-resource metadata to
     *  find the responsible authorization server, then that server's own metadata. Returns
     *  null if any step fails — callers fall back to manual endpoint entry. */
    suspend fun discover(mcpServerUrl: String): McpOAuthDiscoveryResult? = withContext(Dispatchers.IO) {
        val protectedResource = discoverProtectedResourceMetadata(mcpServerUrl)
        val issuer = protectedResource?.issuer ?: guessIssuer(mcpServerUrl) ?: return@withContext null
        val resource = protectedResource?.resource ?: mcpServerUrl
        discoverAuthorizationServerMetadata(issuer, resource)
    }

    private fun guessIssuer(mcpServerUrl: String): String? = try {
        val uri = java.net.URI(mcpServerUrl)
        "${uri.scheme}://${uri.authority}"
    } catch (_: Exception) { null }

    private data class ProtectedResourceInfo(val issuer: String, val resource: String)

    private fun discoverProtectedResourceMetadata(mcpServerUrl: String): ProtectedResourceInfo? {
        val uri = try { java.net.URI(mcpServerUrl) } catch (_: Exception) { return null }
        val origin = "${uri.scheme}://${uri.authority}"
        val path = uri.rawPath?.trimEnd('/').orEmpty()
        val candidates = if (path.isNotBlank()) {
            listOf("$origin/.well-known/oauth-protected-resource$path", "$origin/.well-known/oauth-protected-resource")
        } else {
            listOf("$origin/.well-known/oauth-protected-resource")
        }
        for (url in candidates) {
            val body = try { HttpClient.fetchModels(url) } catch (_: Exception) { null } ?: continue
            val obj = try { json.parseToJsonElement(body).jsonObject } catch (_: Exception) { continue }
            val issuer = try {
                obj["authorization_servers"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content }?.firstOrNull()
            } catch (_: Exception) { null } ?: continue
            // The metadata document's own "resource" claim is the canonical identifier to
            // send back as the `resource` parameter — it may differ from the raw MCP URL
            // (trailing slash, path normalization, etc.), and a mismatch here is a common
            // cause of an otherwise-successful sign-in still getting a 401 "invalid token"
            // from the resource server (the AS mints a token for the wrong audience).
            val resource = (obj["resource"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: mcpServerUrl
            DebugLog.d("McpOAuthManager", "Protected-resource metadata at $url: issuer=$issuer resource=$resource")
            return ProtectedResourceInfo(issuer, resource)
        }
        return null
    }

    /** AppAuth's own `AuthorizationServiceConfiguration.fetchFromIssuer` only tries
     *  `.well-known/openid-configuration`, not RFC 8414's dedicated
     *  `.well-known/oauth-authorization-server` path that OAuth-only (non-OIDC)
     *  authorization servers publish at instead — so that path is tried first here,
     *  hand-rolled, and only falls through to AppAuth's own discovery when it's absent. */
    private suspend fun discoverAuthorizationServerMetadata(issuer: String, resource: String): McpOAuthDiscoveryResult? {
        val trimmed = issuer.trimEnd('/')
        val body = try { HttpClient.fetchModels("$trimmed/.well-known/oauth-authorization-server") } catch (_: Exception) { null }
        val obj = body?.let { try { json.parseToJsonElement(it).jsonObject } catch (_: Exception) { null } }
        val authEndpoint = (obj?.get("authorization_endpoint") as? JsonPrimitive)?.content
        val tokenEndpoint = (obj?.get("token_endpoint") as? JsonPrimitive)?.content
        if (authEndpoint != null && tokenEndpoint != null) {
            val registrationEndpoint = (obj["registration_endpoint"] as? JsonPrimitive)?.content ?: ""
            val scope = try {
                obj["scopes_supported"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content }?.joinToString(" ")
            } catch (_: Exception) { null } ?: ""
            val authMethods = try {
                obj["token_endpoint_auth_methods_supported"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content }
            } catch (_: Exception) { null }
            return McpOAuthDiscoveryResult(trimmed, authEndpoint, tokenEndpoint, registrationEndpoint, scope, resource, preferredClientAuthMethod(authMethods))
        }
        val config = try {
            suspendCancellableCoroutine<AuthorizationServiceConfiguration?> { cont ->
                AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(trimmed)) { serviceConfig, ex ->
                    if (ex != null) DebugLog.e("McpOAuthManager", "fetchFromIssuer failed for $trimmed: ${ex.message}")
                    if (cont.isActive) cont.resume(serviceConfig)
                }
            }
        } catch (_: Exception) { null } ?: return null
        return McpOAuthDiscoveryResult(
            trimmed,
            config.authorizationEndpoint.toString(),
            config.tokenEndpoint.toString(),
            config.registrationEndpoint?.toString() ?: "",
            "",
            resource,
            preferredClientAuthMethod(config.discoveryDoc?.tokenEndpointAuthMethodsSupported)
        )
    }

    /** RFC 8414 lets a server advertise which `token_endpoint_auth_methods` it accepts.
     *  "post" (`client_secret_post`) is preferred since it's what the more common/informally
     *  implemented MCP servers this feature targets tend to expect; only switches to "basic"
     *  (`client_secret_basic`) when the metadata advertises that but not `client_secret_post`.
     *  Defaults to "post" when the server doesn't publish the list at all. */
    private fun preferredClientAuthMethod(methods: List<String>?): String = when {
        methods.isNullOrEmpty() -> "post"
        methods.contains("client_secret_post") -> "post"
        methods.contains("client_secret_basic") -> "basic"
        else -> "post"
    }

    private fun serviceConfiguration(server: McpServerConfig): AuthorizationServiceConfiguration = AuthorizationServiceConfiguration(
        Uri.parse(server.oauthAuthorizationEndpoint),
        Uri.parse(server.oauthTokenEndpoint),
        server.oauthRegistrationEndpoint.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
    )

    // ── Dynamic Client Registration (RFC 7591) ──────────────────

    /** Registers Agora as a public native client (no secret — PKCE covers code
     *  interception). Returns null on failure/unsupported servers; callers fall back to
     *  manual client_id/(optional secret) entry. */
    suspend fun registerClient(registrationEndpoint: String, redirectUri: String): McpDcrResult? = withContext(Dispatchers.IO) {
        val config = AuthorizationServiceConfiguration(
            Uri.parse("about:blank"), Uri.parse("about:blank"), Uri.parse(registrationEndpoint)
        )
        val request = RegistrationRequest.Builder(config, listOf(Uri.parse(redirectUri)))
            .setTokenEndpointAuthenticationMethod("none")
            .build()
        try {
            suspendCancellableCoroutine { cont ->
                authService.performRegistrationRequest(request) { response, ex ->
                    if (ex != null || response == null) {
                        DebugLog.e("McpOAuthManager", "DCR failed: ${ex?.message}")
                        if (cont.isActive) cont.resume(null)
                    } else {
                        if (cont.isActive) cont.resume(McpDcrResult(response.clientId, response.clientSecret ?: ""))
                    }
                }
            }
        } catch (e: Exception) {
            DebugLog.e("McpOAuthManager", "DCR failed: ${e.message}")
            null
        }
    }

    // ── Authorization request ────────────────────────────────────

    /** Builds the AppAuth authorization request and the completed/canceled `PendingIntent`s
     *  that fire back into [MainActivity] once the managed browser flow finishes — no
     *  Agora-side persistence needed here; `AuthorizationManagementActivity` tracks the
     *  in-flight request itself (including across process death), and [server]'s id rides
     *  along as a plain intent extra so the redirect can be matched back to it. */
    fun buildAuthorizationRequest(server: McpServerConfig): McpOAuthAuthorizationRequest {
        val resource = server.oauthResource.ifBlank { server.url }
        val additionalParams = buildMap {
            // RFC 8707 resource indicator — AppAuth doesn't model this natively, so it
            // rides through as an additional (opaque, passed-through) authorize parameter.
            put("resource", resource)
        }
        val requestBuilder = AuthorizationRequest.Builder(
            serviceConfiguration(server), server.oauthClientId, ResponseTypeValues.CODE, Uri.parse(MCP_OAUTH_REDIRECT_URI)
        ).setAdditionalParameters(additionalParams)
        if (server.oauthScope.isNotBlank()) requestBuilder.setScope(server.oauthScope)
        val request = requestBuilder.build()

        fun resultIntent() = Intent(context, MainActivity::class.java)
            .setAction(MCP_OAUTH_RESULT_ACTION)
            .putExtra(MCP_OAUTH_EXTRA_SERVER_ID, server.id)

        val requestCode = server.id.hashCode()
        // Must be mutable, not immutable: AuthorizationManagementActivity fires these via
        // PendingIntent.send(context, 0, resultData), where resultData carries its own
        // EXTRA_RESPONSE/EXTRA_EXCEPTION extras (the actual sign-in outcome) — FLAG_IMMUTABLE
        // would silently drop those, leaving MainActivity only our own server-id extra and
        // no response/exception at all (surfacing as a spurious "cancelled" every time).
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val completedIntent = PendingIntent.getActivity(context, requestCode, resultIntent(), flags)
        val canceledIntent = PendingIntent.getActivity(context, requestCode + 1, resultIntent(), flags)

        return McpOAuthAuthorizationRequest(request, completedIntent, canceledIntent)
    }

    // ── Token exchange / refresh ──────────────────────────────────

    /** Exchanges an authorization [response] (built by [MainActivity] from
     *  `AuthorizationResponse.fromIntent()` on the completed-intent callback) for tokens and
     *  persists them. The response already carries the original request it answers, so no
     *  separate pending-request lookup is needed — AppAuth's own state validation during the
     *  redirect (inside `AuthorizationManagementActivity`) is the CSRF check here. */
    suspend fun exchangeCodeForTokens(
        server: McpServerConfig,
        authResponse: AuthorizationResponse
    ): Result<McpServerConfig> = withContext(Dispatchers.IO) {
        // RFC 8707 resource indicator — must be repeated on the token request, not just
        // the authorize request, or the authorization server may issue a token that isn't
        // actually scoped to this MCP server (→ a 401 "invalid token" from the resource
        // server even though sign-in itself "succeeded"). AppAuth doesn't carry the
        // original request's additionalParameters into the token exchange automatically.
        val tokenRequest = authResponse.createTokenExchangeRequest(
            mapOf("resource" to server.oauthResource.ifBlank { server.url })
        )
        try {
            val tokenResponse = suspendCancellableCoroutine<Pair<net.openid.appauth.TokenResponse?, AuthorizationException?>> { cont ->
                authService.performTokenRequest(tokenRequest, clientAuthentication(server)) { response, ex ->
                    if (cont.isActive) cont.resume(response to ex)
                }
            }
            val (tokenResp, ex) = tokenResponse
            if (ex != null || tokenResp == null) {
                return@withContext Result.failure(Exception(tokenErrorMessage(ex)))
            }
            val state = loadAuthState(server)
            state.update(authResponse, null)
            state.update(tokenResp, null)
            Result.success(persistAuthState(server, state))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Ensures [server] has a non-expired access token, refreshing proactively via AppAuth's
     *  own expiry check + coalescing if needed. Returns the (possibly-unchanged)
     *  [McpServerConfig], with [McpServerConfig.oauthNeedsReauth] set if the refresh itself
     *  failed (refresh token expired/revoked). */
    suspend fun ensureFreshAccessToken(server: McpServerConfig): Result<McpServerConfig> = withContext(Dispatchers.IO) {
        val state = loadAuthState(server)
        if (state.refreshToken.isNullOrBlank() && !state.isAuthorized) {
            return@withContext Result.failure(Exception("no refresh token available"))
        }
        // RFC 8707 resource indicator — repeated on refresh for the same reason as the
        // token exchange above.
        val resourceParams = mapOf("resource" to server.oauthResource.ifBlank { server.url })
        try {
            val outcome = suspendCancellableCoroutine<AuthorizationException?> { cont ->
                state.performActionWithFreshTokens(authService, clientAuthentication(server), resourceParams) { _, _, ex ->
                    if (cont.isActive) cont.resume(ex)
                }
            }
            if (outcome != null) {
                val failed = server.copy(oauthNeedsReauth = true)
                settingsRepository.updateMcpServer(failed)
                return@withContext Result.failure(Exception(tokenErrorMessage(outcome)))
            }
            Result.success(persistAuthState(server, state))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Forces a token refresh regardless of AppAuth's own expiry check — used as a reactive
     *  fallback after an unexpected 401 (e.g. out-of-band server-side revocation an
     *  expiry-based check can't know about). */
    suspend fun refreshAccessToken(server: McpServerConfig): Result<McpServerConfig> = withContext(Dispatchers.IO) {
        val state = loadAuthState(server)
        if (state.refreshToken.isNullOrBlank()) {
            return@withContext Result.failure(Exception("no refresh token available"))
        }
        // RFC 8707 resource indicator — repeated on refresh for the same reason as the
        // token exchange above.
        val tokenRequest = state.createTokenRefreshRequest(mapOf("resource" to server.oauthResource.ifBlank { server.url }))
        try {
            val tokenResponse = suspendCancellableCoroutine<Pair<net.openid.appauth.TokenResponse?, AuthorizationException?>> { cont ->
                authService.performTokenRequest(tokenRequest, clientAuthentication(server)) { response, ex ->
                    if (cont.isActive) cont.resume(response to ex)
                }
            }
            val (response, ex) = tokenResponse
            state.update(response, ex)
            if (ex != null || response == null) {
                val failed = server.copy(oauthAuthStateJson = state.jsonSerializeString(), oauthNeedsReauth = true)
                settingsRepository.updateMcpServer(failed)
                return@withContext Result.failure(Exception(tokenErrorMessage(ex)))
            }
            Result.success(persistAuthState(server, state))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun tokenErrorMessage(ex: AuthorizationException?): String =
        listOfNotNull(ex?.error, ex?.errorDescription).joinToString(": ").ifBlank { ex?.message ?: "token request failed" }

    /** Clears token state (keeps client_id/endpoints so re-auth doesn't require redoing
     *  discovery/DCR). */
    fun signOut(server: McpServerConfig) {
        authStates.remove(server.id)
        settingsRepository.updateMcpServer(
            server.copy(oauthAuthStateJson = "", oauthNeedsReauth = false)
        )
    }
}
