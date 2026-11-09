package org.aohp.driver.runtime

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
import org.json.JSONObject
import java.time.Instant

/** One git checkout inside the env: where it is, where origin is, how far behind. */
data class RepoState(val local: String, val remote: String?, val behind: Int?) {
    val current get() = remote != null && remote == local
    fun text(): String = when {
        remote == null -> local + "  (origin unreachable)"
        remote == local -> local + "  (current)"
        else -> local + " \u2192 " + remote + (if (behind != null) "  ($behind behind)" else "  (behind)")
    }
}

/** aohp-update status for one env (see aohp-agents/bin/aohp-update). */
data class UpdateInfo(
    val env: String,
    val ok: Boolean,
    val agents: RepoState? = null,
    val stale: List<String> = emptyList(),          // bin/* newer in the checkout than in /usr/local/bin
    val configRepo: String? = null,                 // owner/name, null when the env has no config repo
    val config: RepoState? = null,
    val configError: String? = null,                // e.g. no GitHub token granted to this env
    val auth: String = "none",                      // broker | env | gh | none
    val lastAt: Long = 0L,
    val lastOk: Boolean? = null,
    val lastSummary: String? = null,
    val error: String? = null,
) {
    val needsUpdate get() = (agents != null && agents.remote != null && !agents.current) || stale.isNotEmpty() || (config != null && config.remote != null && !config.current)
}

enum class UpdateOp { WORKING, OK, FAILED }
data class UpdateState(val envs: List<UpdateInfo> = emptyList(), val refreshing: Boolean = false,
                       val ops: Map<String, UpdateOp> = emptyMap(), val results: Map<String, String> = emptyMap(), val message: String? = null)

/**
 * The Runtime tab's Updates card: per env, whether aohp-agents / its installed bin/* / the config
 * repo are current, whether the config pull has a GitHub token to use (the Keys tab's GITHUB_TOKEN,
 * granted to the env), and an Update button that runs aohp-update over containerd EXEC. The
 * Driver never sees the token: aohp-update fetches it from the broker for its own process only.
 */
class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val containers = AohpContainerClient(app)
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    init { refresh() }

    private fun repo(o: JSONObject?): RepoState? {
        if (o == null) return null
        val local = o.optString("local", ""); if (local.isEmpty() || local == "null") return null
        val remote = o.optString("remote", "").takeIf { it.isNotEmpty() && it != "null" }
        val behind = if (o.isNull("behind")) null else o.optInt("behind")
        return RepoState(local, remote, behind)
    }

    private fun read(env: String): UpdateInfo {
        return try {
            val r = containers.execSync(env, "aohp-update status 2>/dev/null", 60_000)
            val text = (r.output ?: "").trim()
            if (!r.success || !text.startsWith("{")) return UpdateInfo(env, false, error = "no update status: aohp-update predates 'status' here \u2014 run aohp-update once in the env")
            val o = JSONObject(text)
            val a = o.optJSONObject("agents"); val c = o.optJSONObject("config"); val last = o.optJSONObject("last")
            val stale = ArrayList<String>(); a?.optJSONArray("stale")?.let { for (i in 0 until it.length()) stale += it.getString(i) }
            val configRepo = c?.optString("repo", "")?.takeIf { it.isNotEmpty() && it != "null" }
            val lastAt = last?.optString("at", "")?.takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0L) } ?: 0L
            UpdateInfo(env, true,
                agents = repo(a), stale = stale,
                configRepo = configRepo, config = if (configRepo != null) repo(c) else null,
                configError = c?.optString("error", "")?.takeIf { it.isNotEmpty() && it != "null" },
                auth = o.optJSONObject("github")?.optString("source", "none") ?: "none",
                lastAt = lastAt, lastOk = if (last == null || last.isNull("ok")) null else last.optBoolean("ok"),
                lastSummary = last?.optString("summary", "")?.takeIf { it.isNotEmpty() })
        } catch (e: Exception) {
            Log.w(TAG, "aohp-update status in $env: " + e.message); UpdateInfo(env, false, error = e.message)
        }
    }

    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(refreshing = true) }
        val envs = withContext(Dispatchers.IO) {
            val names = containers.listContainers()?.toList()?.sorted() ?: emptyList()
            names.map { read(it) }
        }
        _state.update { it.copy(envs = envs, refreshing = false) }
    }

    /** Runs aohp-update in the env; the button greys to "Updating\u2026", then shows \u2713/\u2717 and the script's last line. */
    fun update(env: String) = viewModelScope.launch {
        _state.update { it.copy(ops = it.ops + (env to UpdateOp.WORKING), results = it.results - env) }
        val (ok, last) = try {
            withContext(Dispatchers.IO) {
                val r = containers.execSync(env, "aohp-update 2>&1", 600_000)
                val lines = (r.output ?: "").trim().lines().filter { it.isNotBlank() }
                Log.i(TAG, "aohp-update in $env -> " + (if (r.success) "ok" else "FAILED") + ": " + lines.takeLast(3).joinToString(" | ").take(300))
                r.success to (lines.lastOrNull() ?: if (r.success) "ok" else "failed (no output)")
            }
        } catch (e: Exception) { false to ("failed: " + (e.message ?: e.toString())) }
        _state.update { it.copy(ops = it.ops + (env to if (ok) UpdateOp.OK else UpdateOp.FAILED), results = it.results + (env to last.removePrefix("[update] "))) }
        refresh()
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    companion object { private const val TAG = "AohpDriver" }
}
