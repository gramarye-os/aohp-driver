package org.aohp.driver.network

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Server mode: whether the phone keeps its Wi-Fi radio out of 802.11 power-save while the screen
 * is off, so the gateway / sshd / wg stay reachable on a shelf. The policy is persisted here; the
 * agent bridge (BridgeService.applyServerMode, the long-lived foreground service) holds or drops
 * the Wi-Fi lock and publishes the live status for the gateway card.
 */
object ServerMode {
    enum class Mode(val label: String) { OFF("Off"), CHARGING("While charging"), ALWAYS("Always") }
    data class Status(val mode: Mode = Mode.CHARGING, val held: Boolean = false, val plugged: Boolean = false,
                      val detail: String = "bridge not running")

    private const val PREFS = "server_mode"
    private const val KEY = "mode"
    private val _mode = MutableStateFlow<Mode?>(null)
    /** The persisted choice; null until load(). */
    val mode: StateFlow<Mode?> get() = _mode
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> get() = _status

    fun load(ctx: Context): Mode = _mode.value ?: run {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        val m = Mode.values().firstOrNull { it.name == s } ?: Mode.CHARGING
        _mode.value = m
        m
    }

    fun set(ctx: Context, m: Mode) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, m.name).apply()
        _mode.value = m
    }

    fun publish(s: Status) { _status.value = s }
}
