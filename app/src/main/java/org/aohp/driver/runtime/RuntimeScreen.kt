package org.aohp.driver.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.aohp.driver.network.GatewayCard
import org.aohp.driver.ui.CenteredMessage
import org.aohp.driver.ui.KeyValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeScreen(modifier: Modifier = Modifier, vm: RuntimeViewModel = viewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    var showCreate by remember { mutableStateOf(false) }
    var resetTarget by remember { mutableStateOf<String?>(null) }
    var destroyTarget by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(st.message) { st.message?.let { snack.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Runtime") },
                actions = { IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") } },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = {
            if (st.containerdOk == true) FloatingActionButton(onClick = { showCreate = true }) { Icon(Icons.Filled.Add, "Create env") }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (st.refreshing || st.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (st.containerdOk) {
                null -> CenteredMessage("Probing aohp_container…")
                false -> CenteredMessage("aohp_container unavailable:\n" + (st.containerdError ?: "unknown"))
                true -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { ContainerdCard(st) }
                    item { BridgeCard(st, onStart = { vm.startBridge() }, onStop = { vm.stopBridge() }, onImport = { vm.importLegacySecrets() }) }
                    item { GatewayCard() }
                    items(st.envs, key = { it.name }) { env ->
                        EnvCardView(env, selected = env.name == st.selected, busy = st.busy != null,
                            autostart = env.name !in st.autostartOff, onAutostart = { vm.setAutostart(env.name, it) },
                            onSelect = { vm.select(env.name) }, onReset = { resetTarget = env.name }, onDestroy = { destroyTarget = env.name })
                    }
                    if (st.envs.isEmpty()) item { Text("No environments. Use + to create one.", Modifier.padding(8.dp)) }
                    item { DisplaysCard(st) }
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
        }
    }

    if (showCreate) CreateDialog(st.templates, onDismiss = { showCreate = false }) { n, t -> showCreate = false; vm.create(n, t) }
    resetTarget?.let { n ->
        AlertDialog(onDismissRequest = { resetTarget = null }, title = { Text("Reset $n?") },
            text = { Text("All data inside the env will be replaced by a fresh copy of its template. Running services will be killed.") },
            confirmButton = { Button(onClick = { resetTarget = null; vm.reset(n) }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { resetTarget = null }) { Text("Cancel") } })
    }
    destroyTarget?.let { n -> DestroyDialog(n, onDismiss = { destroyTarget = null }) { destroyTarget = null; vm.destroy(n) } }
}

@Composable
private fun ContainerdCard(st: RuntimeState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("aohp-containerd", style = MaterialTheme.typography.titleMedium)
            KeyValue("binder", if (st.containerdOk == true) "connected" else "unavailable")
            KeyValue("envs", st.envs.size.toString())
            KeyValue("templates", if (st.templates.isEmpty()) "(none visible)" else st.templates.joinToString(", "))
            KeyValue("selected env", st.selected ?: "—", mono = true)
            st.busy?.let { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(it) } }
        }
    }
}

@Composable
private fun BridgeCard(st: RuntimeState, onStart: () -> Unit, onStop: () -> Unit, onImport: () -> Unit) {
    val b = st.bridge
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Agent bridge (ws)", style = MaterialTheme.typography.titleMedium)
            KeyValue("state", when { b.running -> "LISTENING"; b.starting -> "starting…"; b.error != null -> "ERROR"; else -> "stopped" })
            KeyValue("bind", "127.0.0.1:" + b.port, mono = true)
            KeyValue("clients", b.clients.toString())
            KeyValue("a11y keepalive", when { b.a11yConnected -> "connected"; b.a11yEnabled -> "enabled, not connected yet"; else -> "off (ui.tree empty)" })
            KeyValue("secrets", if (st.secretNames.isEmpty()) "(none)" else st.secretNames.joinToString(", "), mono = true)
            b.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            b.autostartLog?.let { Text("boot: " + it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (b.running) OutlinedButton(onClick = onStop, enabled = st.busy == null) { Text("Stop") }
                else Button(onClick = onStart, enabled = st.busy == null && !b.starting) { Text("Start") }
                OutlinedButton(onClick = onImport, enabled = st.busy == null && !b.running) { Text("Import legacy secrets") }
            }
            Text((if (b.running) "Import is available while this bridge is stopped: it " else "Import ") + "connects to the stock AOHPAgentDriver bridge on :6666 and copies its secrets into this app's Keystore (stop this bridge first; values are never displayed).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EnvCardView(env: EnvCard, selected: Boolean, busy: Boolean, autostart: Boolean, onAutostart: (Boolean) -> Unit, onSelect: () -> Unit, onReset: () -> Unit, onDestroy: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(env.name, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                if (selected) Text("SELECTED", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            val d = env.diagnose
            KeyValue("template", d?.template?.ifEmpty { "?" } ?: "?")
            KeyValue("rootfs", if (d?.rootfsExists == true) "ok" else "missing")
            KeyValue("services", "${env.aliveServices} running / ${env.serviceCount}")
            d?.cgroup?.let { cg ->
                KeyValue("cgroup", if (cg.enabled) "on (mem max ${cg.memoryMaxConfigured})" else if (cg.v2Detected) "v2 detected, off" else "off")
            }
            if (env.usage?.cgroupEnabled == false) KeyValue("usage", "cgroup dir missing (" + env.usage.cgroupPath + ")")
            env.usage?.takeIf { it.cgroupEnabled }?.let { u ->
                KeyValue("mem", human(u.memoryCurrent) + " / " + u.memoryMax.ifEmpty { "?" } + "  (peak " + human(u.memoryPeak) + ")")
                KeyValue("cpu", (u.cpuUsageUsec / 1_000_000).toString() + " s")
                KeyValue("pids", u.pidsCurrent.toString())
            }
            KeyValue("host dirs", listOfNotNull(if (d?.npmCacheHostDir == true) "npm-cache" else null, if (d?.openclawDevHostDir == true) "openclaw-dev" else null).ifEmpty { listOf("—") }.joinToString(", "))
            env.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Autostart gateway on boot", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.Switch(checked = autostart, onCheckedChange = onAutostart)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onSelect, enabled = !selected) { Text(if (selected) "Selected" else "Select") }
                OutlinedButton(onClick = onReset, enabled = !busy) { Text("Reset") }
                OutlinedButton(onClick = onDestroy, enabled = !busy, colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Destroy") }
            }
        }
    }
}

@Composable
private fun DisplaysCard(st: RuntimeState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Virtual displays (read-only)", style = MaterialTheme.typography.titleMedium)
            when {
                st.displaysError != null -> Text(st.displaysError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                st.displays.isEmpty() -> Text("none", style = MaterialTheme.typography.bodyMedium)
                else -> st.displays.forEach { d ->
                    val kind = when (d.type) { 1 -> "internal"; 2 -> "external"; 5 -> "virtual"; else -> "type " + d.type }
                    KeyValue("#" + d.displayId + " " + d.name.ifEmpty { "(unnamed)" }, (if (d.width > 0) "${d.width}×${d.height} " else "") + kind, mono = true)
                    if (d.topActivity.isNotEmpty()) Text("   top: " + d.topActivity, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun CreateDialog(templates: List<String>, onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf(templates.firstOrNull() ?: "") }
    var menu by remember { mutableStateOf(false) }
    val valid = name.matches(Regex("[a-zA-Z0-9_-]{1,32}")) && template.isNotBlank()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Create environment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(template, { template = it }, label = { Text("Template") }, singleLine = true, modifier = Modifier.weight(1f))
                    if (templates.isNotEmpty()) {
                        TextButton(onClick = { menu = true }) { Text("▾") }
                        DropdownMenu(menu, { menu = false }) { templates.forEach { t -> DropdownMenuItem(text = { Text(t) }, onClick = { template = t; menu = false }) } }
                    }
                }
                Text("Templates are read from " + org.aohp.driver.binder.ContainerService.TEMPLATE_DIR + ". Extraction of a large template can take minutes.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(enabled = valid, onClick = { onCreate(name.trim(), template.trim()) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun DestroyDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Destroy $name?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This permanently deletes the env's rootfs, services and logs. Type the env name to confirm.")
                OutlinedTextField(typed, { typed = it }, singleLine = true, label = { Text("Type \"$name\"") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(enabled = typed == name, onClick = onConfirm, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Destroy") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

fun human(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MiB"
    else -> String.format("%.1f GiB", bytes / (1024.0 * 1024 * 1024))
}
