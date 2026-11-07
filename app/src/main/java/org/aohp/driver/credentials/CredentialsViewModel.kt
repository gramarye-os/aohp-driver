package org.aohp.driver.credentials

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aohp.driver.bridge.AohpContainerClient
import org.aohp.driver.bridge.CredentialAcl
import org.aohp.driver.bridge.CredentialAuditLog
import org.aohp.driver.bridge.CredentialBroker
import org.aohp.driver.bridge.SecretStore

/** One row of the Credentials screen. */
data class CredRow(
    val provider: String,
    val label: String,
    val envVar: String,
    val kind: String?,            // "apikey" | "oauth" | null (nothing stored)
    val grants: List<String>,     // principals granted this provider (env:oc, org.aohp.search, uid:…)
)

data class CredentialsState(
    val rows: List<CredRow> = emptyList(),
    val envs: List<String> = emptyList(),              // env names -> principal "env:<name>"
    val known: List<CredentialBroker.Provider> = CredentialBroker.knownProviders(),
    val busy: String? = null,
    val message: String? = null,
)

/**
 * The Driver's own view of the credential broker (docs/CREDENTIALS.md). This is the one place a
 * phone user adds, replaces or removes a provider key and decides which env may use it — no
 * shell, no bridge: the secret is written inside the Driver's process. Grants made here are the
 * same ACL the consent screen writes; the broker re-reads it on every check.
 */
class CredentialsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SecretStore(app)
    private val acl = CredentialAcl(store)
    private val broker = CredentialBroker(app, store, acl, CredentialAuditLog())
    private val containers = AohpContainerClient(app)

    private val _state = MutableStateFlow(CredentialsState())
    val state: StateFlow<CredentialsState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val (rows, envs) = withContext(Dispatchers.IO) {
            val stored = broker.localStoredProviders().toSet()
            val ids = LinkedHashSet<String>()
            CredentialBroker.knownProviders().forEach { ids += it.id }
            ids += stored
            val all = acl.all()
            ids.map { id ->
                val k = CredentialBroker.known(id)
                CredRow(
                    provider = id,
                    label = k?.label ?: id,
                    envVar = CredentialBroker.envVarFor(id),
                    kind = if (id in stored) broker.localKind(id) else null,
                    grants = all.filterValues { id in it }.keys.sorted(),
                )
            } to (containers.listContainers()?.toList()?.sorted() ?: emptyList())
        }
        _state.update { it.copy(rows = rows, envs = envs) }
    }

    private fun action(label: String, block: suspend () -> String?) = viewModelScope.launch {
        _state.update { it.copy(busy = label) }
        val msg = try { withContext(Dispatchers.IO) { block() } } catch (e: Exception) { "Failed: " + (e.message ?: e.toString()) }
        _state.update { it.copy(busy = null, message = msg) }
        refresh()
    }

    /** Store (or replace) an API key and optionally grant it to envs in the same step. */
    fun saveKey(provider: String, apikey: String, grantTo: Collection<String>) = action("Saving…") {
        if (!broker.localStoreApiKey(provider, apikey)) return@action "The credential store rejected the key for $provider"
        grantTo.forEach { env -> acl.grant("env:$env", provider); Log.i(TAG, "GRANT  provider=$provider principal=env:$env by=driver-ui") }
        "Stored $provider key" + if (grantTo.isEmpty()) "" else " and allowed " + grantTo.joinToString { "env:$it" }
    }

    fun removeKey(provider: String) = action("Removing…") {
        broker.localRemove(provider)
        // A key that is gone should not leave dangling grants behind.
        acl.principalsFor(provider).forEach { acl.revoke(it, provider) }
        "Removed $provider key"
    }

    fun grant(principal: String, provider: String) = action("Allowing…") {
        acl.grant(principal, provider); Log.i(TAG, "GRANT  provider=$provider principal=$principal by=driver-ui"); "Allowed $principal to use $provider"
    }

    fun revokeGrant(principal: String, provider: String) = action("Revoking…") {
        acl.revoke(principal, provider); Log.i(TAG, "REVOKE provider=$provider principal=$principal by=driver-ui"); "Revoked $provider from $principal"
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    companion object { private const val TAG = "AohpCredAudit" }
}
