// Copyright (C) 2026 Chris Anderson
// SPDX-License-Identifier: Apache-2.0

package org.aohp.driver.runtime

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.aohp.driver.DriverApp
import org.aohp.driver.binder.Diagnose
import org.aohp.driver.binder.ServiceManagerCompat
import org.aohp.driver.binder.Usage
import org.aohp.driver.binder.VirtualDisplayInfo
import org.aohp.driver.bridge.BridgeService
import org.aohp.driver.bridge.BridgeState
import org.aohp.driver.bridge.LegacySecretImport
import org.aohp.driver.bridge.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class EnvCard(
    val name: String,
    val diagnose: Diagnose? = null,
    val usage: Usage? = null,
    val serviceCount: Int = 0,
    val aliveServices: Int = 0,
    val error: String? = null,
)

data class RuntimeState(
    val containerdOk: Boolean? = null,       // null = probing
    val containerdError: String? = null,
    val envs: List<EnvCard> = emptyList(),
    val selected: String? = null,
    val templates: List<String> = emptyList(),
    val displays: List<VirtualDisplayInfo> = emptyList(),
    val displaysError: String? = null,
    val busy: String? = null,                 // description of running action
    val message: String? = null,              // transient snackbar
    val refreshing: Boolean = false,
    val bridge: BridgeState = BridgeState(),
    val secretNames: List<String> = emptyList(),
    val autostartOff: Set<String> = emptySet(),   // envs with the Autostart switch turned off (default on)
)

class RuntimeViewModel(app: Application) : AndroidViewModel(app) {
    private val svc = (app as DriverApp).containers
    private val vds = (app as DriverApp).virtualDisplays
    private val settings = (app as DriverApp).settings

    private val _state = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = _state.asStateFlow()
    private var autoJob: Job? = null

    init {
        viewModelScope.launch { settings.selectedEnv.collect { sel -> _state.update { it.copy(selected = sel) } } }
        viewModelScope.launch { BridgeService.state.collect { b -> _state.update { it.copy(bridge = b) }; refreshSecrets() } }
        viewModelScope.launch { settings.autostartOffEnvs.collect { a -> _state.update { it.copy(autostartOff = a) } } }
        refresh()
        autoJob = viewModelScope.launch { while (true) { delay(10_000); if (_state.value.busy == null) refresh(quiet = true) } }
    }

    fun refresh(quiet: Boolean = false) = viewModelScope.launch {
        if (!quiet) _state.update { it.copy(refreshing = true) }
        val list = svc.listContainers()
        list.onFailure { e ->
            _state.update { it.copy(containerdOk = false, containerdError = e.message ?: ServiceManagerCompat.lastError, refreshing = false) }
            return@launch
        }
        val names = list.getOrThrow().sorted()
        val cards = names.map { n ->
            async {
                val d = svc.diagnose(n)
                val u = svc.getUsage(n)
                val s = svc.listServices(n)
                EnvCard(n, d.getOrNull(), u.getOrNull(), s.getOrNull()?.size ?: 0, s.getOrNull()?.count { it.alive } ?: 0,
                    d.exceptionOrNull()?.message)
            }
        }.awaitAll()
        val tpls = svc.listTemplates()
        val vd = vds.listDisplays()
        _state.update {
            it.copy(containerdOk = true, containerdError = null, envs = cards, templates = tpls,
                displays = vd.getOrDefault(emptyList()), displaysError = vd.exceptionOrNull()?.message, refreshing = false)
        }
        // auto-select the only env if nothing is selected
        if (_state.value.selected == null && names.size == 1) select(names[0])
        else if (_state.value.selected != null && _state.value.selected !in names && names.isNotEmpty()) select(names[0])
    }

    fun select(name: String) = viewModelScope.launch { settings.setSelectedEnv(name) }
    fun setAutostart(name: String, on: Boolean) = viewModelScope.launch { settings.setAutostart(name, on) }

    private fun refreshSecrets() = viewModelScope.launch {
        val names = withContext(Dispatchers.IO) { runCatching { SecretStore(getApplication()).list() }.getOrDefault(emptyList()) }
        _state.update { it.copy(secretNames = names) }
    }

    fun startBridge() = viewModelScope.launch {
        settings.setBridgeEnabled(true)
        BridgeService.start(getApplication())
    }

    fun stopBridge() = viewModelScope.launch {
        settings.setBridgeEnabled(false)
        BridgeService.stop(getApplication())
    }

    /** Copy secret names+values from the stock app's bridge on :6666 into our Keystore. Values are never shown. */
    fun importLegacySecrets() = action("Importing secrets from legacy bridge…") {
        val r = withContext(Dispatchers.IO) { LegacySecretImport.run(getApplication()) }
        refreshSecrets()
        "Imported " + r.imported.size + " secret(s) from " + (r.remoteApp ?: "?") + ": " + r.imported.joinToString(", ") +
            (if (r.failed.isNotEmpty()) "; failed: " + r.failed.joinToString(", ") else "")
    }

    fun create(name: String, template: String) = action("Creating $name from $template…") {
        val r = svc.createContainer(name, template).getOrThrow()
        if (r.isEmpty()) "Created $name" else throw RuntimeException(r)
    }

    /** A reset or destroyed env loses its key permissions: a new env under the same name must be allowed again. */
    private fun forgetKeys(name: String, why: String) {
        runCatching { org.aohp.driver.bridge.CredentialAcl.forgetEnv(getApplication(), name, why) }
    }

    fun reset(name: String) = action("Resetting $name…") {
        if (svc.resetContainer(name).getOrThrow()) { forgetKeys(name, "env-reset"); "Reset $name" } else throw RuntimeException("resetContainer returned false")
    }

    fun destroy(name: String) = action("Destroying $name…") {
        if (svc.destroyContainer(name).getOrThrow()) { (getApplication<DriverApp>()).services.forgetEnv(name); forgetKeys(name, "env-removed"); "Destroyed $name" }
        else throw RuntimeException("destroyContainer returned false")
    }

    private fun action(label: String, block: suspend () -> String) = viewModelScope.launch {
        if (_state.value.busy != null) return@launch
        _state.update { it.copy(busy = label) }
        val msg = runCatching { block() }.getOrElse { "Failed: " + (it.message ?: it.toString()) }
        _state.update { it.copy(busy = null, message = msg) }
        refresh()
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
