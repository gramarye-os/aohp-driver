package org.aohp.driver.credentials

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.aohp.driver.bridge.CredentialBroker
import org.aohp.driver.ui.KeyValue

private val PROVIDER_ID = Regex("[a-z][a-z0-9_-]{0,31}")

/**
 * Credentials: every provider the broker knows or has a key for, what is stored, and which envs
 * may use it. Add / Replace / Remove keys and Allow / Revoke per env live here, so a phone user
 * never needs a shell. See docs/CREDENTIALS.md §4.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialsScreen(modifier: Modifier = Modifier, vm: CredentialsViewModel = viewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    var addFor by remember { mutableStateOf<String?>(null) }     // provider id being added/replaced, "" = pick
    var removeFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(st.message) { st.message?.let { snack.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Credentials") },
                actions = { IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") } },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = { FloatingActionButton(onClick = { addFor = "" }) { Icon(Icons.Filled.Add, "Add key") } },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Keys are stored in this phone's Keystore. An environment only receives a token for a provider you have allowed; the key itself never leaves the Driver.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(st.rows, key = { it.provider }) { r ->
                CredCard(r, st.envs, busy = st.busy != null,
                    onReplace = { addFor = r.provider }, onRemove = { removeFor = r.provider },
                    onGrant = { env -> vm.grant("env:$env", r.provider) },
                    onRevoke = { p -> vm.revokeGrant(p, r.provider) })
            }
        }
    }

    addFor?.let { preset ->
        AddKeyDialog(preset = preset, known = st.known, envs = st.envs,
            existing = st.rows.filter { it.kind != null }.map { it.provider }.toSet(),
            onDismiss = { addFor = null },
            onSave = { provider, key, envsToGrant -> addFor = null; vm.saveKey(provider, key, envsToGrant) })
    }
    removeFor?.let { p ->
        AlertDialog(onDismissRequest = { removeFor = null },
            title = { Text("Remove $p key?") },
            text = { Text("Environments allowed to use $p will stop receiving a token; their grants are revoked too.") },
            confirmButton = { TextButton(onClick = { removeFor = null; vm.removeKey(p) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removeFor = null }) { Text("Cancel") } })
    }
}

@Composable
private fun CredCard(r: CredRow, envs: List<String>, busy: Boolean,
                     onReplace: () -> Unit, onRemove: () -> Unit,
                     onGrant: (String) -> Unit, onRevoke: (String) -> Unit) {
    var grantMenu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(r.label, style = MaterialTheme.typography.titleMedium)
            KeyValue("env var", r.envVar, mono = true)
            KeyValue("stored", when (r.kind) { null -> "no"; "oauth" -> "yes (OAuth sign-in)"; else -> "yes (API key)" })
            if (r.grants.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("allowed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    r.grants.forEach { p -> AssistChip(onClick = { if (!busy) onRevoke(p) }, label = { Text(p + "  ✕") }) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.kind == null) Button(onClick = onReplace, enabled = !busy) { Text("Add key") }
                else {
                    OutlinedButton(onClick = onReplace, enabled = !busy) { Text("Replace") }
                    OutlinedButton(onClick = onRemove, enabled = !busy) { Text("Remove") }
                }
                val grantable = envs.filter { "env:$it" !in r.grants }
                if (r.kind != null && grantable.isNotEmpty()) {
                    OutlinedButton(onClick = { grantMenu = true }, enabled = !busy) { Text("Allow env…") }
                    DropdownMenu(expanded = grantMenu, onDismissRequest = { grantMenu = false }) {
                        grantable.forEach { e -> DropdownMenuItem(text = { Text("env:$e") }, onClick = { grantMenu = false; onGrant(e) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddKeyDialog(preset: String, known: List<CredentialBroker.Provider>, envs: List<String>, existing: Set<String>,
                         onDismiss: () -> Unit, onSave: (String, String, List<String>) -> Unit) {
    var provider by remember { mutableStateOf(preset) }
    var custom by remember { mutableStateOf("") }
    var pick by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    val chosen = remember { mutableStateOf(envs.toSet()) }   // default: allow every existing env
    val id = if (provider == "custom") custom.trim().lowercase() else provider
    val idOk = PROVIDER_ID.matches(id)
    val k = known.firstOrNull { it.id == provider }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (preset.isNotEmpty() && preset in existing) "Replace $preset key" else "Add a provider key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (preset.isEmpty()) {
                    OutlinedButton(onClick = { pick = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(when { provider.isEmpty() -> "Choose provider…"; provider == "custom" -> "Other (type an id)"; else -> k?.label ?: provider })
                    }
                    DropdownMenu(expanded = pick, onDismissRequest = { pick = false }) {
                        known.forEach { p -> DropdownMenuItem(text = { Text(p.label + "  (" + p.envVar + ")") }, onClick = { provider = p.id; pick = false }) }
                        DropdownMenuItem(text = { Text("Other…") }, onClick = { provider = "custom"; pick = false })
                    }
                    if (provider == "custom") OutlinedTextField(custom, { custom = it }, label = { Text("provider id (a-z, 0-9, -, _)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (id.isNotEmpty()) {
                    OutlinedTextField(key, { key = it }, label = { Text(CredentialBroker.envVarFor(id)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    if (k?.bearer == true) Text("Sent as  Authorization: Bearer …", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (envs.isNotEmpty()) {
                    Text("Allow these environments to use it:", style = MaterialTheme.typography.labelMedium)
                    envs.forEach { e ->
                        Row { Checkbox(checked = e in chosen.value, onCheckedChange = { on -> chosen.value = if (on) chosen.value + e else chosen.value - e }); Spacer(Modifier.width(4.dp)); Text("env:$e", Modifier.padding(top = 12.dp)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = idOk && key.isNotBlank(), onClick = { onSave(id, key.trim(), chosen.value.toList()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
