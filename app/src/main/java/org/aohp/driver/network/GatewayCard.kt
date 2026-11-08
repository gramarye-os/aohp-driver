package org.aohp.driver.network

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.provider.Settings
import kotlinx.coroutines.launch
import org.aohp.driver.ui.KeyValue

/**
 * Runtime-screen card: the OpenClaw gateway's network face per env — reachable URLs (text: they
 * are short), the TLS certificate it serves, "Pair a browser" (a QR of the URL carrying a
 * 10-minute bootstrap token in the fragment: scan on a laptop, it lands in the Control UI, then
 * appears here under "Waiting to pair" for Allow), the shared token on demand, pending pairing
 * requests, and paired devices. Gateway auth runs before device pairing, so a browser needs the
 * token (or bootstrap token) first and the Allow tap second. See aohp-agents/bin/aohp-gateway.
 */
@Composable
fun GatewayCard(onMessage: (String) -> Unit = {}, vm: GatewayViewModel = viewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pairFor by remember { mutableStateOf<Pair<GatewayInfo, PairCode>?>(null) }
    var minting by remember { mutableStateOf(false) }
    var tokenShown by remember { mutableStateOf<Pair<String, String>?>(null) }   // env -> token
    var removeAsk by remember { mutableStateOf<Pair<String, GatewayDevice>?>(null) }
    var certAsk by remember { mutableStateOf<GatewayInfo?>(null) }
    val ctx = LocalContext.current
    val smMode by ServerMode.mode.collectAsStateWithLifecycle()
    val sm by ServerMode.status.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { ServerMode.load(ctx) }
    LaunchedEffect(st.message) { st.message?.let { onMessage(it); vm.consumeMessage() } }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("OpenClaw gateway", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { vm.refresh() }, enabled = st.busy == null) { Text(st.busy ?: "Refresh") }
            }
            if (st.gateways.isEmpty()) Text("No environments.", style = MaterialTheme.typography.bodySmall)
            st.gateways.forEach { g ->
                if (st.gateways.size > 1) { Spacer(Modifier.height(6.dp)); Text("env:" + g.env, style = MaterialTheme.typography.labelLarge) }
                if (!g.reachable) { KeyValue("status", g.error ?: "unreachable"); return@forEach }
                KeyValue("transport", (if (g.tls) "HTTPS / WSS" else "plain HTTP") + " · auth " + g.auth + " · bind " + g.bind)
                g.urls.forEach { u -> KeyValue(u.iface, u.url, mono = true) }
                // Cert lines with the Cert button beside them (not in the action row: three buttons overflow a phone width).
                if (g.fingerprint != null || g.cert != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        g.fingerprint?.let { KeyValue("cert sha256", it.take(23) + "…", mono = true) }
                        g.cert?.let { c -> KeyValue("cert for", (c.names + c.ips).joinToString(" ").ifEmpty { "(no names — generic cert)" }, mono = true) }
                    }
                    TextButton(onClick = { certAsk = g }, enabled = st.busy == null) { Text("Cert") }
                }
                g.cert?.let { c ->
                    if (!c.covers) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("not valid for " + c.missing.joinToString(" "), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { certAsk = g }, enabled = st.busy == null) { Text("Fix") }
                    }
                }
                val shown = tokenShown?.takeIf { it.first == g.env }?.second
                KeyValue("token", shown ?: "••••••••  (tap Show)", mono = true)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !minting && st.busy == null, onClick = {
                        minting = true
                        scope.launch {
                            val pc = vm.pairCode(g.env, g.urls.firstOrNull { it.iface == "wlan0" }?.iface ?: g.urls.firstOrNull()?.iface ?: "wlan0")
                            minting = false
                            if (pc == null) onMessage("Could not mint a pairing code (is the gateway up?)") else pairFor = g to pc
                        }
                    }) { Text(if (minting) "Minting…" else "Pair a browser") }
                    OutlinedButton(onClick = {
                        if (shown != null) tokenShown = null
                        else scope.launch { val t = vm.token(g.env); if (t == null) onMessage("No token in the keystore") else tokenShown = g.env to t }
                    }) { Text(if (shown != null) "Hide token" else "Show token") }
                }
                if (g.pending.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Waiting to pair", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    g.pending.forEach { d ->
                        val op = st.deviceOps[d.id]
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(d.where() + " · " + d.platform + " · " + d.client, style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = { vm.approve(g.env, d.id) }, enabled = op == null || op == DeviceOp.FAILED) {
                                    Text(when (op) { DeviceOp.WORKING -> "Allowing…"; DeviceOp.OK -> "Allowed ✓"; DeviceOp.FAILED -> "Failed ✗ — retry"; null -> "Allow" })
                                }
                                OutlinedButton(onClick = { vm.reject(g.env, d.id) }, enabled = op == null || op == DeviceOp.FAILED) { Text("Deny") }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("paired devices" + if (g.paired.isEmpty()) ": none" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                g.paired.forEach { d ->
                    val op = st.deviceOps[d.id]
                    // Label by where it is (hostname or IP) and when it was last seen, so two laptops are told apart.
                    val label = (d.label.ifEmpty { d.where() }) + " · " + d.client.removePrefix("openclaw-") +
                            (if (d.connected) " ● now" else d.ago().let { if (it.isEmpty()) "" else " · " + it })
                    AssistChip(onClick = { if (op != DeviceOp.WORKING) removeAsk = g.env to d },
                        label = { Text(label + when (op) { DeviceOp.WORKING -> "  …"; DeviceOp.FAILED -> "  ✗"; else -> "  ✕" }) })
                }
            }
            // Phone-level, not per env: the radio is shared.
            Spacer(Modifier.height(10.dp))
            Text("Server mode — keep Wi-Fi awake with the screen off", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ServerMode.Mode.values().forEach { m ->
                    FilterChip(selected = smMode == m, onClick = { ServerMode.set(ctx, m) }, label = { Text(m.label) })
                }
            }
            Text((if (sm.held) "● " else "○ ") + sm.detail, style = MaterialTheme.typography.bodySmall)
        }
    }

    certAsk?.let { g ->
        val c = g.cert
        val def = remember { deviceName(ctx) }
        var name by remember { mutableStateOf(c?.cn?.takeIf { it.isNotEmpty() && it != "openclaw-gateway" } ?: def) }
        AlertDialog(onDismissRequest = { certAsk = null },
            title = { Text("Gateway certificate") },
            text = {
                Column {
                    if (c != null) {
                        Text("Issued to " + c.cn + (if (c.notAfter.isNotEmpty()) ", valid until " + c.notAfter else ""), style = MaterialTheme.typography.bodySmall)
                        Text("Valid for: " + (c.names + c.ips).joinToString(", ").ifEmpty { "nothing — no subjectAltName, so no browser can match it to a host" }, style = MaterialTheme.typography.bodySmall)
                        if (!c.covers) Text("Does not name this phone's current address: " + c.missing.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    } else Text("No certificate (TLS off?).", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Regenerate issues a new self-signed certificate on the same key, naming this phone and every address it has now (LAN, wg0, tailscale, " + name + ".local). A browser that imports it once then shows a clean padlock. The fingerprint changes: browsers and the OpenClaw app that accepted the old one must accept the new one.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it.lowercase().replace(" ", "").replace(Regex("[^a-z0-9-]"), "-") }, label = { Text("server name") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { certAsk = null; vm.regenCert(g.env, name.replace(Regex("^-+|-+$"), "").ifEmpty { "aohp" }) }) { Text("Regenerate") } },
            dismissButton = { TextButton(onClick = { certAsk = null }) { Text("Close") } })
    }

    removeAsk?.let { (env, d) ->
        AlertDialog(onDismissRequest = { removeAsk = null },
            title = { Text("Unpair this device?") },
            text = { Text(d.where() + " · " + d.platform + " · " + d.client + (if (d.ago().isNotEmpty()) "\nlast seen " + d.ago() else "") + "\n\nIt will need to pair again to connect.") },
            confirmButton = { TextButton(onClick = { removeAsk = null; vm.remove(env, d.id) }) { Text("Unpair") } },
            dismissButton = { TextButton(onClick = { removeAsk = null }) { Text("Cancel") } })
    }

    pairFor?.let { (g, pc) ->
        val bmp = remember(pc.url) { vm.qr(pc.url) }
        val minutes = ((pc.expiresAtMs - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0L)
        AlertDialog(onDismissRequest = { pairFor = null },
            title = { Text("Pair a browser") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(bmp.asImageBitmap(), contentDescription = "pairing QR", modifier = Modifier.size(270.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Scan with the laptop (same Wi-Fi). The link carries a one-time code valid for about " + minutes + " minutes; it admits whoever scans it, so keep the screen to yourself.", style = MaterialTheme.typography.bodySmall)
                    pc.fingerprint?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("The browser will warn about the certificate. Accept it only if its SHA-256 fingerprint is:", style = MaterialTheme.typography.bodySmall)
                        Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Then come back here: the browser appears under “Waiting to pair” — tap Allow. After that it has its own device key and never needs the code again.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { pairFor = null; vm.refresh() }) { Text("Done") } })
    }
}

/** The Android device name (Settings > About > Device name) as a hostname: "OnePlus 13" -> oneplus13. The same name mDNS will advertise. */
private fun deviceName(ctx: android.content.Context): String {
    val raw = Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) ?: android.os.Build.MODEL ?: "aohp"
    return raw.lowercase().replace(" ", "").replace(Regex("[^a-z0-9-]"), "-").replace(Regex("^-+|-+$"), "").ifEmpty { "aohp" }
}
