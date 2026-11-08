package org.aohp.driver.harness

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.aohp.driver.Tab
import org.aohp.driver.ui.CenteredMessage
import org.aohp.driver.ui.KeyValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarnessScreen(modifier: Modifier = Modifier, onGoToTab: (Tab) -> Unit, onSetup: () -> Unit = {}, vm: HarnessViewModel = viewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    var showBootstrap by remember { mutableStateOf(false) }
    LaunchedEffect(st.message) { st.message?.let { snack.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Harness" + (st.env?.let { " · $it" } ?: "")) },
            actions = {
                IconButton(onClick = onSetup) { Icon(androidx.compose.material.icons.Icons.Filled.AddCircle, "Set up OpenClaw") }
                IconButton(onClick = { vm.refresh(); vm.loadSecrets() }) { Icon(Icons.Filled.Refresh, "Refresh") }
            }) },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        if (st.env == null) {
            Column(Modifier.padding(pad).fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SetupCard("No environment yet", "Create a Linux env, add a provider key and start the OpenClaw gateway.", onSetup)
            }
            return@Scaffold
        }
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (st.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (st.servicesError == null && st.services.none { it.serviceId == GATEWAY_SERVICE_ID })
                SetupCard("OpenClaw is not set up in ‘" + st.env + "’", "No openclaw-gateway service has been started here yet. The wizard adds a provider key (or imports a config repo), starts the gateway and can enable autostart on boot.", onSetup)
            GatewayCard(st, vm, onGoToTab, onBootstrap = { showBootstrap = true })
            if (st.units != null) UnitsCard(st, vm) else ServicesCard(st, vm)
            LogCard(st, vm)
            SecretsCard(st, vm)
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showBootstrap) BootstrapDialog(st.bootstrapRepo, onDismiss = { showBootstrap = false }) { repo -> showBootstrap = false; vm.bootstrap(repo) }
    st.bootstrap?.let { b -> BootstrapProgressDialog(b, onDismiss = { if (!b.running) vm.dismissBootstrap() }) }
}

@Composable
private fun SetupCard(title: String, body: String, onSetup: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onSetup) { Text("Set up OpenClaw…") }
        }
    }
}

@Composable
private fun GatewayCard(st: HarnessState, vm: HarnessViewModel, onGoToTab: (Tab) -> Unit, onBootstrap: () -> Unit) {
    val gw = st.services.firstOrNull { it.serviceId == GATEWAY_SERVICE_ID }
    val httpUp = st.probe?.up == true
    val alive = gw?.alive == true
    // The container shares the host netns, so :18789 answering is a device-wide
    // signal; only call it UP when *this* env's service is the one running.
    val (label, color) = when {
        alive && httpUp -> "UP" to Color(0xFF2E7D32)
        alive -> "STARTING" to Color(0xFFF9A825)
        httpUp -> "PORT BUSY" to Color(0xFF6A1B9A)
        else -> "DOWN" to Color(0xFFC62828)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("OpenClaw gateway", style = MaterialTheme.typography.titleMedium)
                StatusPill(label, color)
            }
            if (!alive && httpUp) Text("Port 18789 is served by another env or process (shared network namespace).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            KeyValue("service", if (gw == null) "not registered" else (if (gw.alive) "running pid " + gw.pid + ", up " + fmtUptime(gw.uptimeSec) else "stopped (last pid " + gw.pid + ")"))
            KeyValue("command", gw?.command?.ifEmpty { GATEWAY_COMMAND } ?: GATEWAY_COMMAND, mono = true)
            KeyValue("http", st.probe?.let { (if (it.up) "reachable" else "unreachable") + " · " + it.detail } ?: "probing…")
            st.probe?.version?.let { KeyValue("version", it, mono = true) }
            KeyValue("url", GatewayEndpoint.url(), mono = true)
            st.busy?.let { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(it) } }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val idle = st.busy == null
                Button(onClick = { vm.start() }, enabled = idle && gw?.alive != true) { Text("Start") }
                OutlinedButton(onClick = { vm.stop() }, enabled = idle && gw?.alive == true) { Text("Stop") }
                OutlinedButton(onClick = { vm.restart() }, enabled = idle) { Text("Restart") }
                FilledTonalButton(onClick = onBootstrap, enabled = idle && st.bootstrap?.running != true) { Text("Bootstrap…") }
                if (httpUp) FilledTonalButton(onClick = { onGoToTab(Tab.Web) }) { Text("Open UI") }
            }
        }
    }
}

@Composable
private fun ServicesCard(st: HarnessState, vm: HarnessViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Services (listServices)", style = MaterialTheme.typography.titleMedium)
            st.servicesError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (st.services.isEmpty()) Text("none", style = MaterialTheme.typography.bodyMedium)
            st.services.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.serviceId, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                        Text((if (s.alive) "running · pid " + s.pid + " · up " + fmtUptime(s.uptimeSec) else "stopped") + (if (s.command.isNotEmpty()) " · " + s.command else ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { vm.setLogService(s.serviceId) }) { Text("Log") }
                    if (s.alive && s.serviceId != GATEWAY_SERVICE_ID) TextButton(onClick = { vm.stopOther(s.serviceId) }) { Text("Stop") }
                }
            }
        }
    }
}

/**
 * Units (docs/UNITS.md): the containerd-supervised services and timers of this env. Replaces the
 * plain Services card on images whose daemon has unitControl; transient (svc-start) services show
 * up here too, with loadState "transient".
 */
@Composable
private fun UnitsCard(st: HarnessState, vm: HarnessViewModel) {
    val units = st.units ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Units", style = MaterialTheme.typography.titleMedium)
                Row {
                    TextButton(onClick = { vm.envUnitsAction("daemon-reload") }) { Text("Reload") }
                    TextButton(onClick = { vm.envUnitsAction("env-start") }) { Text("Start env") }
                    TextButton(onClick = { vm.envUnitsAction("env-stop") }) { Text("Stop env") }
                }
            }
            st.unitsError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            st.unitWarnings.take(3).forEach { Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall) }
            if (units.isEmpty()) Text("no units — /etc/aohp/system is empty in this env", style = MaterialTheme.typography.bodyMedium)
            val now = System.currentTimeMillis() / 1000
            units.sortedWith(compareBy({ it.isTimer }, { it.name })).forEach { u ->
                val (label, color) = when {
                    u.loadState == "error" -> "error" to MaterialTheme.colorScheme.error
                    u.active == "active" -> u.active to Color(0xFF2E7D32)
                    u.active == "failed" -> u.active to MaterialTheme.colorScheme.error
                    u.active == "activating" || u.active == "deactivating" -> u.active to Color(0xFFF9A825)
                    else -> u.active to MaterialTheme.colorScheme.outline
                }
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(u.name + (if (u.enabled) "" else "  (disabled)"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                            if (u.description.isNotEmpty()) Text(u.description, style = MaterialTheme.typography.bodySmall)
                            val detail = buildString {
                                append(u.sub)
                                if (u.isTimer) {
                                    if (u.nextElapse > 0) append(" · next in " + fmtUptime(maxOf(0L, u.nextElapse - now)))
                                    if (u.lastTrigger > 0) append(" · last " + fmtUptime(maxOf(0L, now - u.lastTrigger)) + " ago")
                                    u.timerUnit?.let { append(" · → " + it) }
                                } else {
                                    if (u.mainPid > 0) append(" · pid " + u.mainPid)
                                    if (u.active == "active") append(" · up " + fmtUptime(u.uptimeSec))
                                    if (u.sub == "auto-restart") append(" · retry in " + u.restartInSec.toInt() + "s")
                                    if (u.nRestarts > 0) append(" · " + u.nRestarts + " restart" + (if (u.nRestarts > 1) "s" else ""))
                                    if (u.result != "success") append(" · " + u.result + (if (u.exitCode >= 0 && u.result == "exit-code") " " + u.exitCode else "") + (if (u.exitSignalName.isNotEmpty()) " SIG" + u.exitSignalName else ""))
                                    if (u.restart != "no") append(" · Restart=" + u.restart)
                                }
                                if (u.loadState == "error") append(" · " + u.loadError)
                                if (u.loadState == "transient") append(" · transient")
                            }
                            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusPill(label, color)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        val busy = st.busy != null || u.loadState == "error"
                        if (u.active == "active" || u.active == "activating") {
                            TextButton(enabled = !busy, onClick = { vm.unitAction("stop", u.name) }) { Text("Stop") }
                            TextButton(enabled = !busy, onClick = { vm.unitAction("restart", u.name) }) { Text("Restart") }
                        } else {
                            TextButton(enabled = !busy, onClick = { vm.unitAction("start", u.name) }) { Text("Start") }
                        }
                        if (u.loadState != "transient") {
                            if (u.enabled) TextButton(enabled = !busy, onClick = { vm.unitAction("disable", u.name) }) { Text("Disable") }
                            else TextButton(enabled = !busy, onClick = { vm.unitAction("enable", u.name) }) { Text("Enable") }
                        }
                        if (!u.isTimer) TextButton(onClick = { vm.setLogService(u.baseName) }) { Text("Log") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogCard(st: HarnessState, vm: HarnessViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Log · " + st.logServiceId, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("auto", style = MaterialTheme.typography.labelMedium); Spacer(Modifier.width(4.dp))
                    Switch(st.logAuto, { vm.setLogAuto(it) })
                    IconButton(onClick = { vm.refreshLog() }) { Icon(Icons.Filled.Refresh, "Refresh log") }
                }
            }
            val scroll = rememberScrollState()
            LaunchedEffect(st.log) { scroll.scrollTo(scroll.maxValue) }
            Column(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp).background(Color(0xFF0B0F14), RoundedCornerShape(6.dp)).padding(8.dp).verticalScroll(scroll)) {
                Text(stripAnsi(st.log).ifEmpty { "(empty)" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, color = Color(0xFFD8DEE9),
                    modifier = Modifier.horizontalScroll(rememberScrollState()))
            }
        }
    }
}

@Composable
private fun SecretsCard(st: HarnessState, vm: HarnessViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Secrets (names only · aohp-secrets list)", style = MaterialTheme.typography.titleMedium)
            when {
                st.secrets != null -> if (st.secrets.isEmpty()) Text("none") else st.secrets.forEach { Text(it, fontFamily = FontFamily.Monospace) }
                st.secretsError != null -> Text(st.secretsError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Text("loading…")
            }
        }
    }
}

@Composable
private fun BootstrapDialog(initialRepo: String, onDismiss: () -> Unit, onRun: (String) -> Unit) {
    var repo by remember { mutableStateOf(initialRepo) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Bootstrap harness") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Runs  aohp-bootstrap <repo>  inside the env (clones the config repo, installs OpenClaw, writes config). Can take several minutes.")
                OutlinedTextField(repo, { repo = it }, label = { Text("GitHub user/repo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(enabled = repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")), onClick = { onRun(repo.trim()) }) { Text("Run") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun BootstrapProgressDialog(b: BootstrapRun, onDismiss: () -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(b.output) { scroll.scrollTo(scroll.maxValue) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Row(verticalAlignment = Alignment.CenterVertically) {
            if (b.running) { CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text(if (b.running) "Bootstrapping…" else if (b.exitCode == 0) "Bootstrap done" else "Bootstrap failed (exit " + b.exitCode + ")") } },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp).background(Color(0xFF0B0F14), RoundedCornerShape(6.dp)).padding(8.dp).verticalScroll(scroll)) {
                Text(stripAnsi(b.output).lines().filterNot { it.startsWith("__EXIT__=") }.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, color = Color(0xFFD8DEE9))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !b.running) { Text("Close") } })
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.background(color, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 3.dp))
}

private val ANSI = Regex("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007]*(\u0007|\u001B\\\\)")
fun stripAnsi(s: String): String = s.replace(ANSI, "").replace("\r", "")

fun fmtUptime(s: Long): String = when {
    s < 60 -> "${s}s"; s < 3600 -> "${s / 60}m"; s < 86400 -> "${s / 3600}h ${(s % 3600) / 60}m"; else -> "${s / 86400}d ${(s % 86400) / 3600}h"
}
