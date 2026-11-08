package org.aohp.driver.harness

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aohp.driver.DriverApp
import org.aohp.driver.binder.ServiceInfo
import org.aohp.driver.binder.UnitInfo
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

const val GATEWAY_SERVICE_ID = "openclaw-gateway"
const val GATEWAY_COMMAND = "openclaw gateway"
/** Plain-HTTP form, kept for log text; live callers use GatewayEndpoint.url() (HTTPS once TLS is on). */
const val GATEWAY_URL = "http://127.0.0.1:18789/"

data class HttpProbe(val up: Boolean, val code: Int = 0, val version: String? = null, val detail: String = "")

data class BootstrapRun(
    val repo: String,
    val running: Boolean,
    val startedAt: Long,
    val output: String = "",
    val exitCode: Int? = null,
)

data class HarnessState(
    val env: String? = null,
    val services: List<ServiceInfo> = emptyList(),
    val servicesError: String? = null,
    /** containerd units (docs/UNITS.md); null = image without unitControl (pre-units daemon). */
    val units: List<UnitInfo>? = null,
    val unitsError: String? = null,
    val unitWarnings: List<String> = emptyList(),
    val probe: HttpProbe? = null,
    val log: String = "",
    val logAuto: Boolean = true,
    val logServiceId: String = GATEWAY_SERVICE_ID,
    val secrets: List<String>? = null,       // null = tool not present / not loaded
    val secretsError: String? = null,
    val busy: String? = null,
    val message: String? = null,
    val bootstrap: BootstrapRun? = null,
    val bootstrapRepo: String = org.aohp.driver.Settings.DEFAULT_REPO,
)

class HarnessViewModel(app: Application) : AndroidViewModel(app) {
    private val svc = (app as DriverApp).containers
    private val registry = (app as DriverApp).services
    private val settings = (app as DriverApp).settings
    private val _state = MutableStateFlow(HarnessState())
    val state: StateFlow<HarnessState> = _state.asStateFlow()
    private var pollJob: Job? = null

    init {
        viewModelScope.launch { settings.selectedEnv.collect { e -> _state.update { it.copy(env = e, services = emptyList(), log = "", secrets = null) }; refresh(); loadSecrets() } }
        viewModelScope.launch { settings.bootstrapRepo.collect { r -> _state.update { it.copy(bootstrapRepo = r) } } }
        pollJob = viewModelScope.launch {
            while (isActive) { delay(4_000); if (_state.value.busy == null) { refresh(); } }
        }
    }

    fun refresh() = viewModelScope.launch {
        val env = _state.value.env ?: return@launch
        val s = svc.listServices(env)
        val p = probeHttp()
        // units: one "list" op; on an image without unitControl the first failure pins unitsSupported=false
        val u = if (svc.unitsSupported == false) null else svc.unitControl(env, "list")
        _state.update {
            it.copy(services = s.getOrDefault(it.services), servicesError = s.exceptionOrNull()?.message, probe = p,
                units = when {
                    u == null -> null
                    u.isSuccess -> svc.parseUnits(u.getOrThrow().optJSONArray("units"))
                    else -> it.units
                },
                unitsError = u?.exceptionOrNull()?.message,
                unitWarnings = u?.getOrNull()?.optJSONArray("warnings")?.let { a -> (0 until a.length()).map { i -> a.optString(i) } } ?: it.unitWarnings)
        }
        if (u?.isSuccess == true) svc.unitsSupported = true
        else if (u != null && u.exceptionOrNull()?.message?.contains("not available") == true) svc.unitsSupported = false
        if (_state.value.logAuto) refreshLog()
    }

    fun refreshLog() = viewModelScope.launch {
        val env = _state.value.env ?: return@launch
        // unit logs and legacy service logs share one id space (serviceLog reads the unit log first)
        svc.serviceLog(env, _state.value.logServiceId.removeSuffix(".service").removeSuffix(".timer"), 16 * 1024).onSuccess { l -> _state.update { it.copy(log = l) } }
    }

    /** The gateway as a unit (template ships openclaw-gateway.service), null on old images / transient-only envs. */
    fun gatewayUnit(): UnitInfo? = _state.value.units?.firstOrNull { it.name == "$GATEWAY_SERVICE_ID.service" && it.loadState != "transient" }

    fun unitAction(op: String, unit: String, now: Boolean = false) = action(opLabel(op, unit)) {
        svc.unitOp(env(), op, unit, now).getOrThrow().let { u -> "$op ${u.name}: ${u.active} (${u.sub})" + (if (u.result != "success") " · ${u.result}" else "") }
    }
    fun envUnitsAction(op: String) = action(opLabel(op, "env")) {
        val o = svc.unitEnvOp(env(), op).getOrThrow()
        fun arr(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { i -> a.optString(i) } } ?: emptyList()
        when (op) {
            "env-start" -> "started ${arr("started").size} unit(s)" + (arr("failed").takeIf { it.isNotEmpty() }?.let { ", failed: " + it.joinToString() } ?: "")
            "env-stop" -> "stopped ${arr("stopped").size} unit(s)"
            else -> "reloaded ${o.optJSONArray("units")?.length() ?: 0} unit(s)" + (arr("warnings").takeIf { it.isNotEmpty() }?.let { ", ${it.size} warning(s)" } ?: "")
        }
    }
    private fun opLabel(op: String, unit: String) = when (op) {
        "start" -> "Starting $unit…"; "stop" -> "Stopping $unit…"; "restart" -> "Restarting $unit…"
        "enable" -> "Enabling $unit…"; "disable" -> "Disabling $unit…"; "daemon-reload" -> "Reloading units…"
        "env-start" -> "Starting env units…"; "env-stop" -> "Stopping env units…"; else -> "$op $unit…"
    }

    fun setLogAuto(v: Boolean) = _state.update { it.copy(logAuto = v) }
    fun setLogService(id: String) { _state.update { it.copy(logServiceId = id) }; refreshLog() }

    private suspend fun probeHttp(): HttpProbe = withContext(Dispatchers.IO) {
        GatewayEndpoint.refresh(svc, _state.value.env)
        fun get(path: String): Pair<Int, String> {
            val c = GatewayEndpoint.open(path)
            c.connectTimeout = 1500; c.readTimeout = 2500; c.instanceFollowRedirects = false
            return try {
                val code = c.responseCode
                val body = runCatching { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "" }.getOrDefault("")
                code to body
            } finally { c.disconnect() }
        }
        try {
            val (code, _) = get("")
            var version: String? = null
            var detail = "HTTP $code"
            runCatching { get("health") }.onSuccess { (hc, body) ->
                if (hc in 200..299 && body.trimStart().startsWith("{")) {
                    val o = JSONObject(body)
                    version = o.optString("version", "").ifEmpty { null }
                    detail += " · /health " + (o.optString("status", "").ifEmpty { "ok" })
                }
            }
            HttpProbe(code in 200..399, code, version, detail)
        } catch (t: Throwable) {
            HttpProbe(false, 0, null, t.javaClass.simpleName + ": " + (t.message ?: ""))
        }
    }

    // On a units-capable image startService(openclaw-gateway) runs the template's supervised
    // openclaw-gateway.service (containerd maps the id onto the unit file; the command is ignored),
    // so these keep working on old and new images; the unit path is preferred when the list shows it.
    fun start() = action("Starting gateway…") {
        gatewayUnit()?.let { u -> svc.unitOp(env(), "start", u.name).getOrThrow().let { return@action "Started ${it.name}: ${it.active} (${it.sub})" } }
        val pid = svc.startService(env(), GATEWAY_SERVICE_ID, GATEWAY_COMMAND).getOrThrow()
        if (pid > 0) { registry.record(env(), GATEWAY_SERVICE_ID, GATEWAY_COMMAND); "Started $GATEWAY_SERVICE_ID (pid $pid)" }
        else throw RuntimeException("startService returned $pid")
    }
    fun stop() = action("Stopping gateway…") {
        gatewayUnit()?.let { u -> svc.unitOp(env(), "stop", u.name).getOrThrow().let { return@action "Stopped ${it.name}" } }
        if (svc.stopService(env(), GATEWAY_SERVICE_ID).getOrThrow()) { registry.forget(env(), GATEWAY_SERVICE_ID); "Stopped $GATEWAY_SERVICE_ID" }
        else throw RuntimeException("stopService returned false")
    }
    fun restart() = action("Restarting gateway…") {
        gatewayUnit()?.let { u -> svc.unitOp(env(), "restart", u.name).getOrThrow().let { return@action "Restarted ${it.name}: ${it.active} (${it.sub})" } }
        svc.stopService(env(), GATEWAY_SERVICE_ID)
        delay(1500)
        val pid = svc.startService(env(), GATEWAY_SERVICE_ID, GATEWAY_COMMAND).getOrThrow()
        if (pid > 0) { registry.record(env(), GATEWAY_SERVICE_ID, GATEWAY_COMMAND); "Restarted (pid $pid)" }
        else throw RuntimeException("startService returned $pid")
    }
    fun stopOther(id: String) = action("Stopping $id…") {
        if (svc.stopService(env(), id).getOrThrow()) { registry.forget(env(), id); "Stopped $id" }
        else throw RuntimeException("stopService returned false")
    }

    fun loadSecrets() = viewModelScope.launch {
        val env = _state.value.env ?: return@launch
        val r = svc.execSync(env, "command -v aohp-secrets >/dev/null 2>&1 && aohp-secrets list 2>&1 || echo __NOTOOL__", 15_000)
        r.onSuccess { res ->
            val out = res.stdout.trim()
            if (out.contains("__NOTOOL__")) _state.update { it.copy(secrets = null, secretsError = "aohp-secrets not installed in env") }
            else if (!res.ok) _state.update { it.copy(secrets = null, secretsError = "aohp-secrets list failed (" + res.exitCode + "): " + (res.stderr.ifEmpty { out }).take(200)) }
            else _state.update { it.copy(secrets = out.lines().map { l -> l.trim() }.filter { l -> l.isNotEmpty() }, secretsError = null) }
        }.onFailure { e -> _state.update { it.copy(secrets = null, secretsError = e.message) } }
    }

    /**
     * Bootstrap: runs aohp-bootstrap via execSync with a long timeout. execSync
     * only returns at the end, so for "streamed-ish" progress we redirect output
     * to a log file inside the env and poll it with short execSyncs while the
     * long call is in flight.
     */
    fun bootstrap(repo: String) {
        val env = _state.value.env ?: return
        if (_state.value.bootstrap?.running == true) return
        viewModelScope.launch { settings.setBootstrapRepo(repo) }
        val logFile = "/tmp/aohp-bootstrap.log"
        _state.update { it.copy(bootstrap = BootstrapRun(repo, true, System.currentTimeMillis(), "$ aohp-bootstrap $repo\n")) }
        val main = viewModelScope.launch {
            val cmd = "rm -f $logFile; (aohp-bootstrap '" + repo.replace("'", "") + "') >$logFile 2>&1; ec=\$?; echo \"__EXIT__=\$ec\" >> $logFile; exit \$ec"
            val r = svc.execSync(env, cmd, 30 * 60 * 1000)
            val ec = r.getOrNull()?.exitCode ?: -1
            val tail = r.getOrNull()?.let { if (!it.ok && it.stderr.isNotEmpty()) "\n" + it.stderr else "" } ?: ("\n" + r.exceptionOrNull()?.message)
            // final read of the log
            val finalLog = svc.execSync(env, "cat $logFile 2>/dev/null", 10_000).getOrNull()?.stdout ?: ""
            _state.update { st -> st.copy(bootstrap = st.bootstrap?.copy(running = false, exitCode = ec, output = "$ aohp-bootstrap $repo\n" + finalLog + tail)) }
            refresh(); loadSecrets()
        }
        viewModelScope.launch {
            delay(1500)
            while (main.isActive) {
                val out = svc.execSync(env, "cat $logFile 2>/dev/null", 10_000).getOrNull()?.stdout
                if (out != null && main.isActive) _state.update { st -> st.copy(bootstrap = st.bootstrap?.copy(output = "$ aohp-bootstrap $repo\n" + out)) }
                delay(2000)
            }
        }
    }

    fun dismissBootstrap() = _state.update { it.copy(bootstrap = null) }

    private fun env() = _state.value.env ?: throw IllegalStateException("no env selected")

    private fun action(label: String, block: suspend () -> String) = viewModelScope.launch {
        if (_state.value.busy != null) return@launch
        _state.update { it.copy(busy = label) }
        val msg = runCatching { block() }.getOrElse { Log.w("AohpDriver", "harness action failed", it); "Failed: " + (it.message ?: it.toString()) }
        _state.update { it.copy(busy = null, message = msg) }
        delay(800); refresh()
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
