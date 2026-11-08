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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.aohp.driver.ui.KeyValue

/**
 * Runtime-screen card: the OpenClaw gateway's network face per env — reachable URLs, the TLS
 * certificate it serves (fingerprint + QR for a browser), pending pairing requests to approve
 * or reject, and paired devices to remove. See aohp-agents/bin/aohp-gateway.
 */
@Composable
fun GatewayCard(onMessage: (String) -> Unit = {}, vm: GatewayViewModel = viewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    var qrFor by remember { mutableStateOf<Pair<GatewayInfo, GatewayUrl>?>(null) }
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
                if (g.urls.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        g.urls.take(2).forEach { u -> OutlinedButton(onClick = { qrFor = g to u }) { Text("QR · " + u.iface) } }
                    }
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

    qrFor?.let { (g, u) ->
        val bmp = remember(u.url) { vm.qr(u.url) }
        AlertDialog(onDismissRequest = { qrFor = null },
            title = { Text("Open on a laptop") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(bmp.asImageBitmap(), contentDescription = u.url, modifier = Modifier.size(260.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(u.url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    g.fingerprint?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("The browser will warn about the certificate. Accept it only if its SHA-256 fingerprint is:", style = MaterialTheme.typography.bodySmall)
                        Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Then come back here: the browser appears under “Waiting to pair” — tap Allow.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { qrFor = null }) { Text("Done") } })
    }
}
