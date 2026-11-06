package org.aohp.driver.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.aohp.driver.Tab
import org.aohp.driver.ui.KeyValue

/** One screen per step with Back/Next; hosted full-screen by MainActivity. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupWizard(onClose: () -> Unit, onFinish: (Tab) -> Unit, vm: SetupViewModel = viewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val stepNo = when (st.step) { SetupStep.Env -> 1; SetupStep.Credentials -> 2; SetupStep.Start -> 3; SetupStep.Done -> 4 }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Set up OpenClaw · step ${stepNo}/4") },
            navigationIcon = { TextButton(onClick = onClose, enabled = st.busy == null) { Text("Cancel") } }) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (st.busy != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(st.busy!!) }
            }
            st.error?.let { Card(Modifier.fillMaxWidth()) { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) } }
            when (st.step) {
                SetupStep.Env -> EnvStep(st, vm)
                SetupStep.Credentials -> CredStep(st, vm)
                SetupStep.Start -> StartStep(st, vm)
                SetupStep.Done -> DoneStep(st, onFinish)
            }
            if (st.log.isNotEmpty()) LogBox(st.log)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EnvStep(st: SetupState, vm: SetupViewModel) {
    Text("1. Environment", style = MaterialTheme.typography.titleLarge)
    Text("OpenClaw runs inside a Linux container (an \"env\") managed by aohp-containerd. Pick a name and a rootfs template. Creating one extracts ~600 MB and takes about two minutes.", style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(st.envName, vm::setEnvName, label = { Text("env name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = st.busy == null)
    if (st.envName in st.existingEnvs) Text("An env named ‘${st.envName}’ already exists — it will be used as is.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    Picker("template", st.template, st.templates.ifEmpty { listOf(st.template) }, enabled = st.busy == null, onPick = vm::setTemplate)
    if (st.existingEnvs.isNotEmpty()) KeyValue("existing envs", st.existingEnvs.joinToString(", "), mono = true)
    NavRow(backEnabled = false, onBack = {}, nextLabel = if (st.envName in st.existingEnvs) "Use existing" else "Create", nextEnabled = st.busy == null && st.envName.isNotEmpty(), onNext = vm::nextFromEnv)
}

@Composable
private fun CredStep(st: SetupState, vm: SetupViewModel) {
    Text("2. Credentials", style = MaterialTheme.typography.titleLarge)
    Text("How should the agent get its provider API key? Keys are kept in the phone's Android Keystore and handed to the gateway at start through the agent bridge; nothing is written into the container rootfs.", style = MaterialTheme.typography.bodyMedium)
    val idle = st.busy == null
    Option(st.credMode == CredMode.Paste, "Paste an API key", "Stored in the Keystore under the provider's variable name.", idle) { vm.setCredMode(CredMode.Paste) }
    if (st.credMode == CredMode.Paste) Column(Modifier.padding(start = 36.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Picker("provider", st.provider.label, Provider.entries.map { it.label }, idle) { l -> vm.setProvider(Provider.entries.first { it.label == l }) }
        OutlinedTextField(st.apiKey, vm::setApiKey, label = { Text(st.provider.secretName) }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = idle,
            visualTransformation = PasswordVisualTransformation())
    }
    Option(st.credMode == CredMode.Oauth, "Sign in (OAuth)", "On-device login; a refresh token is kept in the Keystore and the agent bridge brokers short-lived tokens.", idle) { vm.setCredMode(CredMode.Oauth) }
    if (st.credMode == CredMode.Oauth) Column(Modifier.padding(start = 36.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Picker("provider", st.provider.label, Provider.entries.map { it.label }, idle) { l -> vm.setProvider(Provider.entries.first { it.label == l }) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            res.data?.let { vm.completeOauth(it) }
        }
        OutlinedButton(onClick = { vm.buildOauthIntent()?.let { launcher.launch(it) } }, enabled = idle) { Text("Sign in with " + st.provider.label) }
    }
    Option(st.credMode == CredMode.Git, "Import from a git config repo", "Runs aohp-bootstrap <user>/<repo> (config, skills, secrets method) inside the env.", idle) { vm.setCredMode(CredMode.Git) }
    if (st.credMode == CredMode.Git) Column(Modifier.padding(start = 36.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(st.gitRepo, vm::setGitRepo, label = { Text("GitHub <user>/<repo>") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = idle)
        OutlinedTextField(st.agePassphrase, vm::setAgePassphrase, label = { Text("age passphrase (if the repo's secrets method is age)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = idle, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(st.ghToken, vm::setGhToken, label = { Text("GitHub token (private repo; otherwise a device-login code appears in the log)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = idle, visualTransformation = PasswordVisualTransformation())
        if (!st.bridgeUp) Text("The agent bridge is not running — start it on the Runtime tab first.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Option(st.credMode == CredMode.Skip, "Skip for now", "You can add a key later (Harness tab or ‘aohp secret set’).", idle) { vm.setCredMode(CredMode.Skip) }
    NavRow(backEnabled = idle, onBack = vm::back, nextLabel = when (st.credMode) { CredMode.Paste -> "Store key"; CredMode.Oauth -> "Sign in above"; CredMode.Git -> "Run bootstrap"; CredMode.Skip -> "Next" },
        nextEnabled = idle && st.credMode != CredMode.Oauth && (st.credMode != CredMode.Paste || st.apiKey.length >= 8) && (st.credMode != CredMode.Git || st.gitRepo.contains('/')), onNext = vm::nextFromCredentials)
}

@Composable
private fun StartStep(st: SetupState, vm: SetupViewModel) {
    Text("3. Start the gateway", style = MaterialTheme.typography.titleLarge)
    KeyValue("env", st.envName, mono = true)
    KeyValue("credentials", st.credSummary ?: "—")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Autostart on boot", style = MaterialTheme.typography.bodyLarge)
            Text("After a reboot the Driver brings the bridge up, waits for containerd and starts this env's gateway.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = st.autostart, onCheckedChange = vm::setAutostartNow, enabled = st.busy == null)
    }
    st.gatewayPid?.let { KeyValue("gateway pid", it.toString()) }
    st.httpCode?.let { KeyValue("http", "HTTP ${it} on 127.0.0.1:18789") }
    NavRow(backEnabled = st.busy == null, onBack = vm::back, nextLabel = "Start gateway", nextEnabled = st.busy == null, onNext = vm::startGateway)
}

@Composable
private fun DoneStep(st: SetupState, onFinish: (Tab) -> Unit) {
    Text("4. Ready", style = MaterialTheme.typography.titleLarge)
    Text("The OpenClaw gateway in ‘${st.envName}’ answers on http://127.0.0.1:18789/ (HTTP ${st.httpCode}). The Web tab shows its Control UI; the Harness tab has start/stop and logs.", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onFinish(Tab.Web) }) { Text("Open Control UI") }
        OutlinedButton(onClick = { onFinish(Tab.Harness) }) { Text("Harness") }
    }
}

@Composable
private fun Option(selected: Boolean, title: String, subtitle: String, enabled: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Column { Text(title, style = MaterialTheme.typography.bodyLarge); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Picker(label: String, value: String, options: List<String>, enabled: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { open = true }, enabled = enabled) { Text(value, fontFamily = FontFamily.Monospace) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(o) }) }
        }
    }
}

@Composable
private fun NavRow(backEnabled: Boolean, onBack: () -> Unit, nextLabel: String, nextEnabled: Boolean, onNext: () -> Unit) {
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        OutlinedButton(onClick = onBack, enabled = backEnabled) { Text("Back") }
        Button(onClick = onNext, enabled = nextEnabled) { Text(nextLabel) }
    }
}

@Composable
private fun LogBox(log: String) {
    Text("Progress log", style = MaterialTheme.typography.titleSmall)
    val scroll = rememberScrollState()
    androidx.compose.runtime.LaunchedEffect(log.length) { scroll.scrollTo(scroll.maxValue) }
    Text(log.takeLast(12_000), Modifier.fillMaxWidth().heightIn(max = 260.dp).background(Color(0xFF111111), RoundedCornerShape(6.dp)).padding(8.dp).verticalScroll(scroll),
        fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFFDDDDDD))
}
