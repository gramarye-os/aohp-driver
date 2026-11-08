package org.aohp.driver.network

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aohp.driver.bridge.AohpContainerClient
import org.json.JSONObject

data class GatewayUrl(val iface: String, val url: String)
data class PairCode(val url: String, val expiresAtMs: Long, val fingerprint: String?)
/** [id] is the deviceId (paired) or the pairing requestId (pending): approve/reject take the request id, remove takes the device id. */
data class GatewayDevice(val id: String, val label: String, val platform: String, val client: String, val remoteIp: String, val connected: Boolean,
                         val host: String = "", val lastSeenMs: Long = 0L) {
    /** What distinguishes two laptops: hostname if the LAN knows one, else the IP; then the client kind. */
    fun where(): String = host.ifEmpty { remoteIp }.ifEmpty { platform }
    fun ago(now: Long = System.currentTimeMillis()): String {
        if (lastSeenMs <= 0) return ""
        val m = (now - lastSeenMs) / 60_000
        return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 1440 -> "${m / 60} h ago"; else -> "${m / 1440} d ago" }
    }
}

/** Outcome of the last Allow/Deny/Remove on a device, shown in place of a snackbar. */
enum class DeviceOp { WORKING, OK, FAILED }

/** One env's OpenClaw gateway as seen from inside the env (aohp-gateway status). */
data class GatewayInfo(
    val env: String,
    val reachable: Boolean,
    val tls: Boolean = false,
    val fingerprint: String? = null,
    val bind: String = "",
    val auth: String = "",
    val urls: List<GatewayUrl> = emptyList(),
    val pending: List<GatewayDevice> = emptyList(),
    val paired: List<GatewayDevice> = emptyList(),
    val error: String? = null,
)

data class GatewayState(val gateways: List<GatewayInfo> = emptyList(), val busy: String? = null, val message: String? = null,
                        val deviceOps: Map<String, DeviceOp> = emptyMap())   // device/request id -> state

/**
 * The gateway's network face: where it is reachable, which certificate it serves, and who is
 * paired. All reads and actions run aohp-gateway inside the env over containerd EXEC — the
 * Driver never holds the gateway token; the env's CLI authenticates over loopback.
 */
class GatewayViewModel(app: Application) : AndroidViewModel(app) {
    private val containers = AohpContainerClient(app)
    private val _state = MutableStateFlow(GatewayState())
    val state: StateFlow<GatewayState> = _state.asStateFlow()

    init { refresh() }

    private fun device(o: JSONObject, pending: Boolean = false): GatewayDevice = GatewayDevice(
        id = if (pending) o.optString("requestId", o.optString("deviceId")) else o.optString("deviceId"),
        label = o.optString("label", ""),
        platform = o.optString("platform", "?"),
        client = o.optString("clientId", o.optString("clientMode", "?")),
        remoteIp = o.optString("remoteIp", ""),
        connected = o.optBoolean("connected", false),
        host = o.optString("host", ""),
        lastSeenMs = o.optLong("lastSeenAtMs", o.optLong("ts", 0L)),
    )

    private fun read(env: String): GatewayInfo {
        return try {
            val r = containers.execSync(env, "aohp-gateway status 2>/dev/null", 40_000)
            val text = (r.output ?: "").trim()
            if (!r.success || !text.startsWith("{")) return GatewayInfo(env, false, error = "no gateway status (is aohp-agents current?)")
            val o = JSONObject(text)
            val urls = ArrayList<GatewayUrl>()
            val ua = o.optJSONArray("urls")
            if (ua != null) for (i in 0 until ua.length()) { val u = ua.getJSONObject(i); urls += GatewayUrl(u.optString("iface"), u.optString("url")) }
            val pend = ArrayList<GatewayDevice>(); val pa = o.optJSONArray("pending")
            if (pa != null) for (i in 0 until pa.length()) pend += device(pa.getJSONObject(i), pending = true)
            val pair = ArrayList<GatewayDevice>(); val pr = o.optJSONArray("paired")
            if (pr != null) for (i in 0 until pr.length()) pair += device(pr.getJSONObject(i))
            // wg0 first: it is the tunnel a laptop reaches the phone over; LAN next; tailscale last.
            val order = listOf("wg0", "wlan0", "eth0", "tailscale0")
            urls.sortBy { order.indexOf(it.iface).let { i -> if (i < 0) 99 else i } }
            GatewayInfo(env, true, o.optBoolean("tls"), o.optString("fingerprint").takeIf { it.isNotEmpty() && it != "null" },
                o.optString("bind"), o.optString("auth"), urls, pend, pair)
        } catch (e: Exception) {
            Log.w(TAG, "gateway status in $env: " + e.message); GatewayInfo(env, false, error = e.message)
        }
    }

    fun refresh() = viewModelScope.launch {
        val gws = withContext(Dispatchers.IO) {
            val envs = containers.listContainers()?.toList()?.sorted() ?: emptyList()
            envs.map { read(it) }
        }
        _state.update { it.copy(gateways = gws) }
    }

    private fun action(label: String, block: suspend () -> String) = viewModelScope.launch {
        _state.update { it.copy(busy = label) }
        val msg = try { withContext(Dispatchers.IO) { block() } } catch (e: Exception) { "Failed: " + (e.message ?: e.toString()) }
        _state.update { it.copy(busy = null, message = msg) }
        refresh()
    }
    private fun run(env: String, args: String): String {
        val r = containers.execSync(env, "aohp-gateway $args 2>&1", 30_000)
        val last = (r.output ?: "").trim().lines().lastOrNull { it.isNotBlank() } ?: ""
        Log.i(TAG, "aohp-gateway $args -> " + (if (r.success) "ok" else "FAILED") + ": " + last.take(160))
        return if (r.success) last.ifEmpty { "ok" } else "failed: $last"
    }

    /** Per-device action: the button greys to "working", then shows ✓ or ✗ in place; the list refreshes after. */
    private fun deviceAction(env: String, id: String, args: String) = viewModelScope.launch {
        _state.update { it.copy(deviceOps = it.deviceOps + (id to DeviceOp.WORKING)) }
        val msg = try { withContext(Dispatchers.IO) { run(env, args) } } catch (e: Exception) { "failed: " + (e.message ?: e.toString()) }
        val ok = !msg.startsWith("failed")
        _state.update { it.copy(deviceOps = it.deviceOps + (id to if (ok) DeviceOp.OK else DeviceOp.FAILED), message = if (ok) null else msg) }
        refresh()
    }
    fun approve(env: String, id: String) = deviceAction(env, id, "approve $id")
    fun reject(env: String, id: String) = deviceAction(env, id, "reject $id")
    fun remove(env: String, id: String) = deviceAction(env, id, "remove $id")
    fun consumeMessage() = _state.update { it.copy(message = null) }

    /** A pairing URL for a laptop: https://<iface ip>:port/#bootstrapToken=… (10-minute token, fragment only). */
    suspend fun pairCode(env: String, iface: String = "wlan0"): PairCode? = withContext(Dispatchers.IO) {
        try {
            val r = containers.execSync(env, "aohp-gateway pair-code $iface 2>/dev/null", 40_000)
            val text = (r.output ?: "").trim()
            if (!r.success || !text.startsWith("{")) return@withContext null
            val o = JSONObject(text)
            PairCode(o.getString("url"), o.optLong("expiresAtMs", 0L), o.optString("fingerprint").takeIf { it.isNotEmpty() && it != "null" })
        } catch (e: Exception) { Log.w(TAG, "pair-code in $env: " + e.message); null }
    }

    /** The shared gateway token, read from the env's keystore on demand; never stored by the Driver. */
    suspend fun token(env: String): String? = withContext(Dispatchers.IO) {
        try {
            val r = containers.execSync(env, "aohp-gateway token 2>/dev/null", 20_000)
            (r.output ?: "").trim().takeIf { r.success && it.isNotEmpty() }
        } catch (e: Exception) { null }
    }

    /** QR of a short text (a URL) as a bitmap; ZXing core, no Play Services. */
    fun qr(text: String, size: Int = 640): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        return bmp
    }

    companion object { private const val TAG = "AohpGateway" }
}
