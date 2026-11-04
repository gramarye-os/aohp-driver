package org.aohp.driver

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.aohp.driver.binder.ContainerService
import org.aohp.driver.binder.VirtualDisplayService
import org.aohp.driver.terminal.PtySessionRegistry

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class Settings(private val ctx: Context) {
    companion object {
        val SELECTED_ENV = stringPreferencesKey("selected_env")
        val BOOTSTRAP_REPO = stringPreferencesKey("bootstrap_repo")
        const val DEFAULT_REPO = "gramarye-os/aohp-config-chris"
        val AUTOSTART_ENVS = stringSetPreferencesKey("autostart_envs")
        /** 0.5.0: autostart is ON by default; this set holds the envs the user switched OFF. */
        val AUTOSTART_OFF_ENVS = stringSetPreferencesKey("autostart_off_envs")
        val BRIDGE_ENABLED = booleanPreferencesKey("bridge_enabled")
    }
    /**
     * Envs the boot receiver must NOT start. Since 0.5.0 every env autostarts (env-start /
     * registry replay) unless its Runtime-card switch was turned off; [AUTOSTART_ENVS] is kept
     * in step for older readers but no longer decides anything.
     */
    val autostartOffEnvs: Flow<Set<String>> = ctx.dataStore.data.map { it[AUTOSTART_OFF_ENVS] ?: emptySet() }
    /** Legacy (<= 0.4.0) explicit opt-in set; superseded by [autostartOffEnvs]. */
    val autostartEnvs: Flow<Set<String>> = ctx.dataStore.data.map { it[AUTOSTART_ENVS] ?: emptySet() }
    fun isAutostart(env: String, off: Set<String>): Boolean = env !in off
    suspend fun setAutostart(env: String, on: Boolean) = ctx.dataStore.edit { p ->
        val off = p[AUTOSTART_OFF_ENVS] ?: emptySet()
        p[AUTOSTART_OFF_ENVS] = if (on) off - env else off + env
        val cur = p[AUTOSTART_ENVS] ?: emptySet()
        p[AUTOSTART_ENVS] = if (on) cur + env else cur - env
    }
    /** Whether the app should bring the bridge up when it starts (default true). */
    val bridgeEnabled: Flow<Boolean> = ctx.dataStore.data.map { it[BRIDGE_ENABLED] ?: true }
    suspend fun setBridgeEnabled(v: Boolean) = ctx.dataStore.edit { it[BRIDGE_ENABLED] = v }
    val selectedEnv: Flow<String?> = ctx.dataStore.data.map { it[SELECTED_ENV] }
    suspend fun setSelectedEnv(name: String?) = ctx.dataStore.edit { p -> if (name == null) p.remove(SELECTED_ENV) else p[SELECTED_ENV] = name }
    val bootstrapRepo: Flow<String> = ctx.dataStore.data.map { it[BOOTSTRAP_REPO] ?: DEFAULT_REPO }
    suspend fun setBootstrapRepo(v: String) = ctx.dataStore.edit { it[BOOTSTRAP_REPO] = v }
}

class DriverApp : Application() {
    val containers by lazy { ContainerService() }
    val virtualDisplays by lazy { VirtualDisplayService() }
    val settings by lazy { Settings(this) }
    val ptySessions by lazy { PtySessionRegistry(containers) }
    /** Per-env record of started services, replayed by the boot autostart. */
    val services by lazy { ServiceRegistry.get(this) }

    companion object {
        lateinit var instance: DriverApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Bring the agent bridge up with the process (unless the user stopped it);
        // the boot receiver also starts it with autostart=true.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            if (settings.bridgeEnabled.first()) org.aohp.driver.bridge.BridgeService.start(this@DriverApp)
        }
    }
}
