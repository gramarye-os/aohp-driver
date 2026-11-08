package org.aohp.driver.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.aohp.driver.DriverApp
import org.aohp.driver.MainActivity
import org.aohp.driver.R
import org.aohp.driver.ServiceRegistry
import org.aohp.driver.network.ServerMode
import java.net.InetSocketAddress

data class BridgeState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val port: Int = BridgeService.DEFAULT_PORT,
    val clients: Int = 0,
    val error: String? = null,
    /** Last autostart pass summary (boot path), for the Runtime card. */
    val autostartLog: String? = null,
    /** Secure-settings state of the no-op accessibility keepalive (needed by ui.tree & node actions). */
    val a11yEnabled: Boolean = false,
    val a11yConnected: Boolean = false,
)

/**
 * The agent bridge: the JSON-RPC-over-WebSocket server the `aohp` CLI inside a
 * container talks to (same wire format as the stock AOHPAgentDriver's
 * AohpJsonRpcService, see JsonCommandHandler). Bound to 127.0.0.1 only.
 *
 * Foreground service of type specialUse so it is not subject to the 6-hour
 * dataSync timeout that kills the stock app's bridge.
 */
class BridgeService : Service() {
    companion object {
        const val TAG = "AohpDriver"
        const val DEFAULT_PORT = 6666
        const val ACTION_START = "org.aohp.driver.bridge.START"
        const val ACTION_STOP = "org.aohp.driver.bridge.STOP"
        /** Set on ACTION_START from the boot receiver: also autostart gateways. */
        const val EXTRA_AUTOSTART = "autostart"
        const val EXTRA_PORT = "port"
        private const val CHANNEL = "aohp_bridge"
        private const val NOTIF_ID = 1

        private val _state = MutableStateFlow(BridgeState())
        val state: StateFlow<BridgeState> = _state

        fun start(ctx: Context, autostart: Boolean = false, port: Int = DEFAULT_PORT) {
            val i = Intent(ctx, BridgeService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_AUTOSTART, autostart).putExtra(EXTRA_PORT, port)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, BridgeService::class.java).setAction(ACTION_STOP))
        }
    }

    private var server: BridgeWebSocketServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var powerReceiver: BroadcastReceiver? = null
    private var serverModeJob: kotlinx.coroutines.Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var clientPoller: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "AOHP agent bridge", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIF_ID, buildNotification("starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("AOHP agent bridge")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                A11yKeepalive.disable(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
                val autostart = intent?.getBooleanExtra(EXTRA_AUTOSTART, false) ?: false
                scope.launch {
                    startServer(port)
                    if (autostart) autostartGateways()
                }
                return START_STICKY
            }
        }
    }

    private fun startServer(port: Int) {
        synchronized(this) {
            server?.let {
                if (_state.value.running && _state.value.port == port) return
                stopServerLocked()
            }
            _state.value = _state.value.copy(starting = true, error = null, port = port)
            try {
                val s = BridgeWebSocketServer(InetSocketAddress("127.0.0.1", port))
                s.isReuseAddr = true
                s.connectionLostTimeout = 60
                s.setContext(applicationContext)
                s.start()
                // Java-WebSocket binds in its selector thread; wait until it is up (or fails).
                var waited = 0
                while (waited < 5000 && !s.isListening() && s.lastError == null) {
                    Thread.sleep(50); waited += 50
                }
                if (!s.isListening()) {
                    val e = s.lastError?.toString() ?: "bind timeout"
                    try { s.stopServer() } catch (_: Exception) {}
                    _state.value = BridgeState(port = port, error = "bind 127.0.0.1:$port failed: $e")
                    notify("bridge failed: $e")
                    Log.e(TAG, "bridge bind failed on $port: $e")
                    return
                }
                server = s
                acquireWakeLock()
                startServerMode()
                val a11yErr = A11yKeepalive.ensureEnabled(this)
                _state.value = BridgeState(running = true, port = port, error = a11yErr?.let { "a11y keepalive: $it" },
                    a11yEnabled = A11yKeepalive.isEnabled(this), a11yConnected = BridgeAccessibilityService.connected)
                notify("ws://127.0.0.1:$port")
                Log.i(TAG, "bridge listening on 127.0.0.1:$port")
                clientPoller?.cancel()
                clientPoller = scope.launch {
                    while (true) {
                        delay(2000)
                        val n = server?.connections?.size ?: 0
                        val a = BridgeAccessibilityService.connected
                        if (n != _state.value.clients || a != _state.value.a11yConnected)
                            _state.value = _state.value.copy(clients = n, a11yConnected = a, a11yEnabled = A11yKeepalive.isEnabled(this@BridgeService))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "bridge start failed", e)
                _state.value = BridgeState(port = port, error = e.toString())
                notify("bridge failed: ${e.message}")
            }
        }
    }

    private fun stopServer() = synchronized(this) { stopServerLocked() }

    private fun stopServerLocked() {
        clientPoller?.cancel(); clientPoller = null
        server?.let {
            try { it.stopServer() } catch (e: Exception) { Log.w(TAG, "bridge stop", e) }
        }
        server = null
        releaseWakeLock()
        stopServerMode()
        _state.value = BridgeState(port = _state.value.port)
        Log.i(TAG, "bridge stopped")
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AohpDriver:bridge").also { it.acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * Server mode: keep the Wi-Fi radio out of 802.11 power-save while the screen is off, so the
     * gateway, sshd and wg stay reachable on a shelf. Measured 2026-10 on both the Pixel 6 (BCM4389)
     * and the OnePlus (Qualcomm): after a long screen-off the radio in power-save drops or delays
     * inbound frames; "stay awake while charging" only masked it by keeping the screen on. A
     * FULL_HIGH_PERF WifiLock is honoured whenever STA is connected
     * (WifiLockManager.canActivateHighPerfLock) - unlike LOW_LATENCY, which needs a foreground
     * activity AND the screen on. The CPU side is the partial wakelock above; the Driver is also on
     * the Doze allowlist (vendor/aohp sysconfig) so both hold on battery. Policy: off / while
     * charging (default) / always; re-evaluated on power connect/disconnect and on change.
     */
    private fun startServerMode() {
        if (powerReceiver != null) return
        powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { applyServerMode() }
        }.also {
            val f = IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) }
            registerReceiver(it, f, Context.RECEIVER_NOT_EXPORTED)
        }
        serverModeJob = scope.launch { ServerMode.mode.collect { applyServerMode() } }
    }

    private fun stopServerMode() {
        serverModeJob?.cancel(); serverModeJob = null
        powerReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }; powerReceiver = null
        releaseWifiLock()
        ServerMode.publish(ServerMode.Status(ServerMode.load(this), held = false, plugged = isPlugged(), detail = "bridge not running"))
    }

    private fun isPlugged(): Boolean =
        (registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

    @Suppress("DEPRECATION")
    private fun applyServerMode() {
        synchronized(this) {
            val mode = ServerMode.load(this)
            val plugged = isPlugged()
            val want = mode == ServerMode.Mode.ALWAYS || (mode == ServerMode.Mode.CHARGING && plugged)
            if (want && wifiLock?.isHeld != true) {
                val wm = applicationContext.getSystemService(WifiManager::class.java)
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AohpDriver:server").also { it.setReferenceCounted(false); it.acquire() }
                Log.i(TAG, "server mode: Wi-Fi high-perf lock held (" + mode + (if (plugged) ", charging)" else ", battery)"))
            } else if (!want && wifiLock != null) {
                releaseWifiLock()
                Log.i(TAG, "server mode: Wi-Fi lock released (" + mode + (if (plugged) ", charging)" else ", battery)"))
            }
            val detail = when {
                want -> "Wi-Fi kept awake" + (if (mode == ServerMode.Mode.CHARGING) " (charging)" else "")
                mode == ServerMode.Mode.CHARGING -> "on battery: Wi-Fi may sleep with the screen off; plug in to serve"
                else -> "off: Wi-Fi may sleep with the screen off"
            }
            ServerMode.publish(ServerMode.Status(mode, held = wifiLock?.isHeld == true, plugged = plugged, detail = detail))
        }
    }

    private fun releaseWifiLock() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    /**
     * Boot path: wait for aohp_container (containerd) to answer, then, for every env flagged
     * autostart, "start the env": containerd's env-start runs the env's enabled units
     * (/etc/aohp/system/aohp.target.wants, docs/UNITS.md) in dependency order, supervised
     * (Restart=, timers). Fallback for images without unitControl or envs without any unit
     * file: the 0.3.0 behaviour — replay every service the ServiceRegistry recorded (anything
     * started through the Harness tab or `aohp sandbox svc-start`) in order with the gateway
     * last, or just the gateway when nothing was recorded.
     * The gateway's launcher wrapper reads ANTHROPIC_API_KEY through this bridge, so the ws
     * server must be up first (it is: called after startServer).
     */
    private suspend fun autostartGateways() {
        val app = application as DriverApp
        val off = app.settings.autostartOffEnvs.first()
        if (!_state.value.running) {
            _state.value = _state.value.copy(autostartLog = "autostart: skipped, bridge not running")
            return
        }
        val log = StringBuilder()
        var containers: List<String>? = null
        for (attempt in 1..90) { // up to ~3 min
            containers = app.containers.listContainers().getOrNull()
            if (containers != null) break
            delay(2000)
        }
        if (containers == null) {
            log.append("containerd not reachable after 3 min")
            _state.value = _state.value.copy(autostartLog = log.toString())
            return
        }
        // 0.5.0: every env autostarts unless its switch was turned off.
        val envs = containers.filter { app.settings.isAutostart(it, off) }.sorted()
        if (envs.isEmpty()) {
            _state.value = _state.value.copy(autostartLog = if (containers.isEmpty()) "autostart: no envs" else "autostart: all envs switched off")
            return
        }
        for (env in envs) {
            // units first (docs/UNITS.md)
            val units = if (app.containers.unitsSupported == false) null else app.containers.listUnits(env).getOrNull()
            if (units != null && units.any { it.loadState != "transient" }) {
                val r = app.containers.unitEnvOp(env, "env-start")
                log.append(r.fold({ o ->
                    fun arr(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { i -> a.optString(i) } } ?: emptyList()
                    "$env: env-start: ${arr("started").size} unit(s) started" + (arr("failed").takeIf { it.isNotEmpty() }?.let { ", failed: " + it.joinToString() } ?: "") + "\n"
                }, { "$env: env-start failed: ${it.message}\n" }))
                if (r.isSuccess) continue
            } else if (units != null) {
                log.append("$env: no unit files, registry fallback\n")
            }
            val recorded = app.services.list(env)
            val wanted = if (recorded.isEmpty()) listOf(ServiceRegistry.Entry(GATEWAY_SERVICE_ID, GATEWAY_COMMAND))
                         else recorded.filter { it.serviceId != GATEWAY_SERVICE_ID } + recorded.filter { it.serviceId == GATEWAY_SERVICE_ID }
            val alive = app.containers.listServices(env).getOrNull()?.filter { it.alive }?.map { it.serviceId }?.toSet() ?: emptySet()
            for (e in wanted) {
                if (e.serviceId in alive) { log.append("$env: ${e.serviceId} already up\n"); continue }
                if (e.command.isEmpty()) { log.append("$env: ${e.serviceId} has no command, skipped\n"); continue }
                val r = app.containers.startService(env, e.serviceId, e.command)
                log.append(r.fold({ "$env: started ${e.serviceId} pid $it\n" }, { "$env: ${e.serviceId} start failed: ${it.message}\n" }))
                if (e.serviceId != GATEWAY_SERVICE_ID) delay(500)
            }
        }
        Log.i(TAG, "autostart: " + log.toString().trim().replace('\n', ';'))
        _state.value = _state.value.copy(autostartLog = log.toString().trim())
    }

    override fun onDestroy() {
        stopServer()
        scope.cancel()
        super.onDestroy()
    }
}

const val GATEWAY_SERVICE_ID = "openclaw-gateway"
const val GATEWAY_COMMAND = "openclaw gateway"
