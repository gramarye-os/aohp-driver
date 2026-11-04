package org.aohp.driver.setup

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aohp.driver.DriverApp
import org.aohp.driver.bridge.BridgeService
import org.aohp.driver.bridge.SecretStore
import org.aohp.driver.harness.GATEWAY_COMMAND
import org.aohp.driver.harness.GATEWAY_SERVICE_ID
import org.aohp.driver.harness.GATEWAY_URL
import java.net.HttpURLConnection
import java.net.URL

enum class SetupStep { Env, Credentials, Start, Done }
enum class CredMode { Paste, Git, Skip }
enum class Provider(val label: String, val secretName: String, val primaryModel: String) {
    Anthropic("Anthropic", "ANTHROPIC_API_KEY", "anthropic/claude-sonnet-5"),
    OpenAI("OpenAI", "OPENAI_API_KEY", "openai/gpt-5"),
}

data class SetupState(
    val step: SetupStep = SetupStep.Env,
    // env step
    val envName: String = "oc",
    val template: String = "debian",
    val templates: List<String> = emptyList(),
    val existingEnvs: List<String> = emptyList(),
    val envReady: Boolean = false,          // env exists (created or pre-existing)
    // credentials step
    val credMode: CredMode = CredMode.Paste,
    val provider: Provider = Provider.Anthropic,
    val apiKey: String = "",                // UI field only; never logged
    val gitRepo: String = "",
    val agePassphrase: String = "",
    val ghToken: String = "",
    val credDone: Boolean = false,
    val credSummary: String? = null,
    // start step
    val autostart: Boolean = true,
    val gatewayPid: Long? = null,
    val httpCode: Int? = null,
    val bridgeUp: Boolean = false,
    // shared
    val busy: String? = null,
    val log: String = "",
    val error: String? = null,
)

/**
 * First-run wizard: create env -> credentials -> start gateway (+autostart) -> Web tab.
 * Secrets go into the Driver's Keystore (SecretStore, the same store the bridge serves to
 * 'aohp secret get'); nothing secret is ever put into an execSync command string.
 */
class SetupViewModel(app: Application) : AndroidViewModel(app) {
    private val svc = (app as DriverApp).containers
    private val settings = (app as DriverApp).settings
    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    companion object {
        const val TAG = "AohpDriver"
        private const val PP_SECRET = "AOHP_SETUP_AGE_PASSPHRASE"
        private const val GH_SECRET = "AOHP_SETUP_GH_TOKEN"
        private const val BOOTSTRAP_URL = "https://raw.githubusercontent.com/gramarye-os/aohp-agents/main/bootstrap.sh"
    }

    init {
        viewModelScope.launch {
            val envs = svc.listContainers().getOrDefault(emptyList())
            val tpls = svc.listTemplates()
            _state.update { it.copy(existingEnvs = envs, templates = tpls, template = if ("debian" in tpls) "debian" else tpls.firstOrNull() ?: "debian") }
        }
        viewModelScope.launch { settings.bootstrapRepo.collect { r -> _state.update { it.copy(gitRepo = it.gitRepo.ifEmpty { r }) } } }
        viewModelScope.launch { BridgeService.state.collect { b -> _state.update { it.copy(bridgeUp = b.running) } } }
    }

    // ---- field setters ----
    fun setEnvName(v: String) = _state.update { it.copy(envName = v.trim(), error = null) }
    fun setTemplate(v: String) = _state.update { it.copy(template = v) }
    fun setCredMode(v: CredMode) = _state.update { it.copy(credMode = v, error = null) }
    fun setProvider(v: Provider) = _state.update { it.copy(provider = v) }
    fun setApiKey(v: String) = _state.update { it.copy(apiKey = v) }
    fun setGitRepo(v: String) = _state.update { it.copy(gitRepo = v.trim()) }
    fun setAgePassphrase(v: String) = _state.update { it.copy(agePassphrase = v) }
    fun setGhToken(v: String) = _state.update { it.copy(ghToken = v) }
    fun setAutostart(v: Boolean) = _state.update { it.copy(autostart = v) }
    fun back() = _state.update { s ->
        val prev = when (s.step) { SetupStep.Credentials -> SetupStep.Env; SetupStep.Start -> SetupStep.Credentials; else -> s.step }
        s.copy(step = prev, error = null)
    }
    fun clearError() = _state.update { it.copy(error = null) }

    // ---- step 1: env ----
    fun nextFromEnv() = run("Creating ${_state.value.envName} from ${_state.value.template}… (about 2 minutes)") {
        val s = _state.value
        require(s.envName.matches(Regex("[a-z][a-z0-9_-]{0,31}"))) { "name: lowercase letters, digits, - and _ (max 32)" }
        if (s.envName !in s.existingEnvs) {
            val r = svc.createContainer(s.envName, s.template).getOrThrow()
            if (r.isNotEmpty()) throw RuntimeException(r)
            appendLog("created env ${s.envName} (template ${s.template})")
        } else appendLog("using existing env ${s.envName}")
        settings.setSelectedEnv(s.envName)
        _state.update { it.copy(envReady = true, step = SetupStep.Credentials, existingEnvs = (it.existingEnvs + s.envName).distinct()) }
    }

    // ---- step 2: credentials ----
    fun nextFromCredentials() {
        when (_state.value.credMode) {
            CredMode.Skip -> { _state.update { it.copy(credDone = true, credSummary = "skipped", step = SetupStep.Start) } }
            CredMode.Paste -> pasteKey()
            CredMode.Git -> importFromGit()
        }
    }

    private fun pasteKey() = run("Storing key in Android Keystore…") {
        val s = _state.value
        val key = s.apiKey.trim()
        require(key.length >= 8) { "paste the full API key" }
        val store = SecretStore(getApplication())
        require(store.set(s.provider.secretName, key)) { "Keystore rejected the value" }
        _state.update { it.copy(apiKey = "") }   // drop from UI state as soon as it is stored
        appendLog("stored ${s.provider.secretName} in Keystore (" + key.length + " chars)")
        installKeystoreLauncher(s.envName)
        if (s.provider != Provider.Anthropic) configurePrimaryModel(s.envName, s.provider)
        _state.update { it.copy(credDone = true, credSummary = "${s.provider.label} key in Keystore (${s.provider.secretName})", step = SetupStep.Start) }
    }

    /**
     * Static script (no secrets inside). aohp-containerd's execSync only runs the first line of
     * the command string, so the script travels base64-encoded on one line.
     */
    private suspend fun installKeystoreLauncher(env: String) {
        val script = """#!/bin/sh
# OpenClaw launcher installed by AOHP Driver setup.
# 1) provider keys come from the phone's Android Keystore through the agent bridge (secret.get on
#    ws://127.0.0.1:6666). 'aohp secret get' is used when the env's CLI has it; the template's
#    aohp 0.1.0 does not, so a tiny Node client (openclaw's bundled 'ws'; Node's built-in undici
#    WebSocket rejects Java-WebSocket's handshake) speaks the same JSON-RPC directly.
#    A config-repo bootstrap (aohp-secrets) is honoured too if present.
# 2) aohp-containerd injects NODE_OPTIONS=--jitless; Node 24 fetch() needs WebAssembly, so strip it.
NODE_OPTIONS=${'$'}(printf '%s' "${'$'}{NODE_OPTIONS:-}" | sed -e 's/--jitless//g' -e 's/  */ /g' -e 's/^ //' -e 's/ ${'$'}//'); export NODE_OPTIONS
aohp_secret_get() {
  v=${'$'}(aohp secret get "${'$'}1" 2>/dev/null) && [ -n "${'$'}v" ] && { printf '%s' "${'$'}v"; return 0; }
  node -e 'const n=process.argv[1];let W;try{W=require("/usr/local/lib/node_modules/openclaw/node_modules/ws")}catch(e){W=WebSocket};const ws=new W(process.env.AOHP_WS_URL||"ws://127.0.0.1:6666");
ws.onopen=()=>ws.send(JSON.stringify({id:"1",method:"secret.get",params:{name:n}}));
ws.onmessage=(m)=>{let o={};try{o=JSON.parse(m.data)}catch(e){};if(o.ok&&o.result&&o.result.value!=null)process.stdout.write(String(o.result.value));ws.close();process.exit(o.ok?0:1)};
ws.onerror=()=>process.exit(2);setTimeout(()=>process.exit(3),8000);' "${'$'}1" 2>/dev/null
}
for v in ANTHROPIC_API_KEY OPENAI_API_KEY; do
  k=${'$'}(aohp_secret_get "${'$'}v") && [ -n "${'$'}k" ] && export "${'$'}v=${'$'}k"
done
if command -v aohp-secrets >/dev/null 2>&1; then eval "${'$'}(aohp-secrets env 2>/dev/null)"; fi
exec /usr/local/bin/openclaw.real "${'$'}@"
"""
        val b64 = android.util.Base64.encodeToString(script.toByteArray(), android.util.Base64.NO_WRAP)
        val cmd = "test -x /usr/local/bin/openclaw.real || { echo 'openclaw.real missing in env'; exit 3; }; " +
            "printf '%s' '" + b64 + "' | base64 -d > /usr/local/bin/openclaw.aohp-driver && " +
            "chmod 755 /usr/local/bin/openclaw.aohp-driver && mv -f /usr/local/bin/openclaw.aohp-driver /usr/local/bin/openclaw && echo launcher-ok"
        val r = svc.execSync(env, cmd, 20_000).getOrThrow()
        if (!r.ok || !r.stdout.contains("launcher-ok")) throw RuntimeException("launcher install failed (exit " + r.exitCode + "): " + (r.stderr.ifEmpty { r.stdout }).take(300))
        appendLog("installed Keystore-aware openclaw launcher in ${env}")
    }

    private suspend fun configurePrimaryModel(env: String, p: Provider) {
        val prov = p.name.lowercase()
        val js = "const fs=require('fs');const f='/root/.openclaw/openclaw.json';const o=JSON.parse(fs.readFileSync(f,'utf8'));" +
            "o.models=o.models||{};o.models.providers=o.models.providers||{};o.models.providers['" + prov + "']=o.models.providers['" + prov + "']||{};" +
            "o.agents=o.agents||{};o.agents.defaults=o.agents.defaults||{};o.agents.defaults.model=o.agents.defaults.model||{};o.agents.defaults.model.primary='" + p.primaryModel + "';" +
            "fs.writeFileSync(f,JSON.stringify(o,null,2));console.log('model-ok')"
        val r = svc.execSync(env, "node -e \"" + js.replace("\"", "\\\"") + "\"", 20_000).getOrThrow()
        if (!r.stdout.contains("model-ok")) appendLog("warning: could not set primary model: " + (r.stderr.ifEmpty { r.stdout }).take(200))
        else appendLog("primary model set to ${p.primaryModel}")
    }

    /**
     * aohp-bootstrap <repo> inside the env. The passphrase / token never enter the command
     * line: they are parked in the Keystore under temporary names, the env pulls them with
     * 'aohp secret get' into 0600 files in /tmp, and both are removed afterwards.
     * Output goes to a log file that is polled for streaming.
     */
    private fun importFromGit() {
        val s = _state.value
        if (!s.gitRepo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { _state.update { it.copy(error = "repo must be <user>/<repo>") }; return }
        if (!s.bridgeUp) { _state.update { it.copy(error = "the agent bridge is not running (Runtime tab) — bootstrap needs it") }; return }
        if (_state.value.busy != null) return
        viewModelScope.launch { settings.setBootstrapRepo(s.gitRepo) }
        val env = s.envName
        val store = SecretStore(getApplication())
        val havePp = s.agePassphrase.isNotEmpty(); val haveTok = s.ghToken.isNotEmpty()
        if (havePp) store.set(PP_SECRET, s.agePassphrase)
        if (haveTok) store.set(GH_SECRET, s.ghToken)
        _state.update { it.copy(agePassphrase = "", ghToken = "") }
        val logFile = "/tmp/aohp-setup-bootstrap.log"
        val args = StringBuilder("'" + s.gitRepo + "'")
        val pre = StringBuilder("umask 077; rm -f /tmp/.aohp-pp /tmp/.aohp-tok; ")
        if (havePp) { pre.append("aohp secret get ${PP_SECRET} > /tmp/.aohp-pp || exit 4; "); args.append(" --secrets age --passphrase-file /tmp/.aohp-pp") }
        if (haveTok) { pre.append("aohp secret get ${GH_SECRET} > /tmp/.aohp-tok || exit 4; "); args.append(" --token-file /tmp/.aohp-tok") }
        val fetch = "command -v aohp-bootstrap >/dev/null 2>&1 || { echo '[setup] fetching bootstrap.sh'; curl -fsSL '${BOOTSTRAP_URL}' -o /usr/local/bin/aohp-bootstrap && chmod 755 /usr/local/bin/aohp-bootstrap; } || exit 5; "
        val cmd = "rm -f ${logFile}; ( " + pre + fetch + "aohp-bootstrap " + args + " ) > ${logFile} 2>&1; ec=\$?; rm -f /tmp/.aohp-pp /tmp/.aohp-tok; echo \"__EXIT__=\$ec\" >> ${logFile}; exit \$ec"
        _state.update { it.copy(busy = "Running aohp-bootstrap ${s.gitRepo}…", error = null, log = it.log + "$ aohp-bootstrap ${s.gitRepo}\n") }
        val base = _state.value.log
        val main = viewModelScope.launch {
            val r = svc.execSync(env, cmd, 30 * 60 * 1000)
            val ec = r.getOrNull()?.exitCode ?: -1
            val out = svc.execSync(env, "cat ${logFile} 2>/dev/null", 10_000).getOrNull()?.stdout ?: ""
            store.delete(PP_SECRET); store.delete(GH_SECRET)
            _state.update { it.copy(log = base + out + (r.exceptionOrNull()?.let { e -> "\n" + e.message } ?: ""), busy = null) }
            if (ec == 0) _state.update { it.copy(credDone = true, credSummary = "bootstrapped from ${s.gitRepo}", step = SetupStep.Start) }
            else _state.update { it.copy(error = "aohp-bootstrap exited ${ec} — see log") }
        }
        viewModelScope.launch {
            delay(1500)
            while (main.isActive) {
                val out = svc.execSync(env, "cat ${logFile} 2>/dev/null", 10_000).getOrNull()?.stdout
                if (out != null && main.isActive) _state.update { it.copy(log = base + out) }
                delay(2000)
            }
        }
    }

    // ---- step 3: start ----
    fun startGateway() = run("Starting openclaw-gateway…") {
        val env = _state.value.envName
        settings.setAutostart(env, _state.value.autostart)
        val running = svc.listServices(env).getOrNull()?.firstOrNull { it.serviceId == GATEWAY_SERVICE_ID && it.alive }
        val pid = running?.pid?.toLong() ?: svc.startService(env, GATEWAY_SERVICE_ID, GATEWAY_COMMAND).getOrThrow()
        if (pid <= 0) throw RuntimeException("startService returned ${pid}")
        (getApplication<DriverApp>()).services.record(env, GATEWAY_SERVICE_ID, GATEWAY_COMMAND)
        appendLog((if (running != null) "gateway already running pid " else "started gateway pid ") + pid + ", autostart=" + _state.value.autostart)
        _state.update { it.copy(gatewayPid = pid) }
        // First start in a fresh env can take minutes: openclaw runs `npm install` for plugin deps
        // (~/.openclaw/npm) before it listens. Poll up to 4 min and keep the user informed.
        var code: Int? = null
        val t0 = System.currentTimeMillis()
        var i = 0
        while (System.currentTimeMillis() - t0 < 240_000) {
            code = probe(); if (code != null && code in 200..399) break
            val alive = svc.listServices(env).getOrNull()?.any { it.serviceId == GATEWAY_SERVICE_ID && it.alive } == true
            if (!alive) {
                val tail = svc.serviceLog(env, GATEWAY_SERVICE_ID, 4000).getOrNull() ?: ""
                appendLog("gateway process exited; log tail:\n" + tail.takeLast(1500))
                throw RuntimeException("gateway exited before listening — see log")
            }
            if (++i % 10 == 0) appendLog("still waiting for http://127.0.0.1:18789 (" + ((System.currentTimeMillis() - t0) / 1000) + " s; first start installs plugin deps with npm, be patient)")
            _state.update { it.copy(busy = "Waiting for the gateway to listen… " + ((System.currentTimeMillis() - t0) / 1000) + " s") }
            delay(2000)
        }
        _state.update { it.copy(httpCode = code) }
        if (code == null || code !in 200..399) {
            val tail = svc.serviceLog(env, GATEWAY_SERVICE_ID, 4000).getOrNull() ?: ""
            appendLog("gateway did not answer on 18789 within 4 min; log tail:\n" + tail.takeLast(1500))
            throw RuntimeException("gateway not reachable yet (HTTP " + (code ?: "none") + ") — it is still running; tap Start gateway again to keep waiting, or check the Harness log")
        }
        appendLog("HTTP ${code} on ${GATEWAY_URL}")
        _state.update { it.copy(step = SetupStep.Done) }
    }

    fun setAutostartNow(v: Boolean) { setAutostart(v); viewModelScope.launch { if (_state.value.envReady) settings.setAutostart(_state.value.envName, v) } }

    private suspend fun probe(): Int? = withContext(Dispatchers.IO) {
        try {
            val c = URL(GATEWAY_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 1500; c.readTimeout = 2500; c.instanceFollowRedirects = false
            try { c.responseCode } finally { c.disconnect() }
        } catch (_: Throwable) { null }
    }

    private fun appendLog(s: String) = _state.update { it.copy(log = it.log + s + "\n") }

    private fun run(label: String, block: suspend () -> Unit) = viewModelScope.launch {
        if (_state.value.busy != null) return@launch
        _state.update { it.copy(busy = label, error = null) }
        try { block() } catch (t: Throwable) {
            Log.w(TAG, "setup step failed: " + t.message)
            _state.update { it.copy(error = t.message ?: t.toString()) }
        }
        _state.update { it.copy(busy = null) }
    }
}
