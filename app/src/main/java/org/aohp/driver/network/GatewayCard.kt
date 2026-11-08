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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
                g.fingerprint?.let { KeyValue("cert sha256", it.take(23) + "…", mono = true) }
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
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(d.platform + " · " + d.client + (if (d.remoteIp.isNotEmpty()) " · from " + d.remoteIp else ""), style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.approve(g.env, d.id) }, enabled = st.busy == null) { Text("Allow") }
                                OutlinedButton(onClick = { vm.reject(g.env, d.id) }, enabled = st.busy == null) { Text("Deny") }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("paired devices" + if (g.paired.isEmpty()) ": none" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                g.paired.forEach { d ->
                    AssistChip(onClick = { if (st.busy == null) vm.remove(g.env, d.id) },
                        label = { Text((d.label.ifEmpty { d.platform }) + " · " + d.client + (if (d.connected) " ●" else "") + "  ✕") })
                }
            }
        }
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
