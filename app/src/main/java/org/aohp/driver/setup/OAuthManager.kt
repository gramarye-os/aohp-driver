package org.aohp.driver.setup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import org.aohp.driver.bridge.SecretStore
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * On-device OAuth acquisition for the credential broker (docs/CREDENTIALS.md item 4).
 *
 * Uses AppAuth-Android (Authorization Code + PKCE, no client secret) in a Custom Tab. On success
 * it writes a {@code cred:<provider>} record — kind=oauth — into the same Keystore-backed
 * [SecretStore] the broker reads, so the refresh token never leaves the Driver and the broker
 * owns refresh from then on.
 *
 * Provider OAuth parameters are supplied by [OAuthConfig]; providers without a public third-party
 * OAuth client (e.g. Anthropic today) simply have no config and fall back to the API-key path.
 */
class OAuthManager(private val appContext: Context) {

    companion object { private const val TAG = "AohpOAuth" }

    /** Build the authorization request intent to launch in a Custom Tab. */
    fun buildAuthIntent(service: AuthorizationService, cfg: OAuthConfig): Intent {
        val serviceConfig = AuthorizationServiceConfiguration(
            Uri.parse(cfg.authEndpoint), Uri.parse(cfg.tokenEndpoint))
        val req = AuthorizationRequest.Builder(
            serviceConfig, cfg.clientId, ResponseTypeValues.CODE, Uri.parse(cfg.redirectUri))
            .setScope(cfg.scopes)
            .build()
        return service.getAuthorizationRequestIntent(req)
    }

    /**
     * Handle the redirect intent: exchange the code for tokens (PKCE) and persist the oauth record.
     * @return the account-label-less provider on success; throws on failure.
     */
    suspend fun handleAuthResponse(
        service: AuthorizationService, cfg: OAuthConfig, data: Intent
    ): String {
        val resp = AuthorizationResponse.fromIntent(data)
        val ex = net.openid.appauth.AuthorizationException.fromIntent(data)
        if (resp == null) throw (ex ?: RuntimeException("no authorization response"))

        val tokenResp: TokenResponse = suspendCoroutine { cont ->
            service.performTokenRequest(resp.createTokenExchangeRequest()) { tr, e ->
                if (tr != null) cont.resume(tr) else cont.resumeWithException(e ?: RuntimeException("token exchange failed"))
            }
        }
        persist(cfg, tokenResp)
        return cfg.provider
    }

    private fun persist(cfg: OAuthConfig, tr: TokenResponse) {
        val refresh = tr.refreshToken
            ?: throw RuntimeException("provider returned no refresh token (needs offline_access/consent)")
        val rec = JSONObject().apply {
            put("provider", cfg.provider)
            put("kind", "oauth")
            put("refresh_token", refresh)
            tr.accessToken?.let { put("access_token", it) }
            tr.accessTokenExpirationTime?.let { put("expires_at", it) }
            put("token_url", cfg.tokenEndpoint)
            put("client_id", cfg.clientId)
            put("scopes", cfg.scopes)
            put("needs_reauth", false)
        }
        val store = SecretStore(appContext)
        require(store.set("cred:" + cfg.provider, rec.toString())) { "Keystore rejected the oauth record" }
        Log.i(TAG, "stored oauth credential for " + cfg.provider)
    }
}

/**
 * Per-provider OAuth parameters. PKCE public client, so no client secret. Fill [clientId] and the
 * endpoints from the provider's developer console; a provider with no OAuth config uses API keys.
 *
 * NOTE: Anthropic does not currently publish a third-party OAuth client for arbitrary apps, so its
 * config is intentionally absent — use the API-key (or setup-token) path for Anthropic until a
 * sanctioned flow exists.
 */
data class OAuthConfig(
    val provider: String,
    val authEndpoint: String,
    val tokenEndpoint: String,
    val clientId: String,
    val scopes: String,
    val redirectUri: String,
) {
    companion object {
        /** Returns the OAuth config for a provider, or null if it has no supported OAuth flow. */
        fun forProvider(provider: String): OAuthConfig? = when (provider) {
            // Example (Google): real values come from the provider console.
            // "google" -> OAuthConfig("google",
            //     "https://accounts.google.com/o/oauth2/v2/auth",
            //     "https://oauth2.googleapis.com/token",
            //     "<client-id>.apps.googleusercontent.com",
            //     "openid email https://www.googleapis.com/auth/gmail.readonly",
            //     "aohp://oauth/google")
            else -> null
        }
    }
}
