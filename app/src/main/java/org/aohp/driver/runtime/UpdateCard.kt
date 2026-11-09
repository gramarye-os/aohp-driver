package org.aohp.driver.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.aohp.driver.ui.KeyValue

private fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
    val m = (now - ms) / 60_000
    return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 1440 -> "${m / 60} h ago"; else -> "${m / 1440} d ago" }
}

/**
 * Runtime-screen card: is the env's tooling current? aohp-agents checkout vs origin, the scripts installed
 * from it, the config repo vs origin, where the GitHub token for that private repo comes from, and
 * the last update's outcome. Update runs aohp-update in the env (pull, install, installers).
 */
@Composable
fun UpdateCard(vm: UpdateViewModel = viewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Updates", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { vm.refresh() }, enabled = !st.refreshing) { Text(if (st.refreshing) "Checking\u2026" else "Refresh") }
            }
            if (st.envs.isEmpty()) Text(if (st.refreshing) "Checking\u2026" else "No environments.", style = MaterialTheme.typography.bodySmall)
            st.envs.forEach { u ->
                if (st.envs.size > 1) { Spacer(Modifier.height(6.dp)); Text("env:" + u.env, style = MaterialTheme.typography.labelLarge) }
                if (!u.ok) { KeyValue("status", u.error ?: "unavailable") }
                else {
                    u.agents?.let { KeyValue("aohp-agents", it.text(), mono = true) }
                    if (u.stale.isNotEmpty()) Text("not installed yet: " + u.stale.joinToString(" "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, fontFamily = FontFamily.Monospace)
                    KeyValue("config", if (u.configRepo == null) "(no config repo)" else u.configRepo + "  " + (u.config?.text() ?: "?"), mono = true)
                    u.configError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    if (u.configRepo != null) KeyValue("github token", when (u.auth) { "broker" -> "Keys tab (GITHUB_TOKEN, granted)"; "env" -> "environment"; "gh" -> "gh login"; else -> "none" })
                    KeyValue("last update", if (u.lastAt > 0) ago(u.lastAt) + (when (u.lastOk) { true -> "  \u2713"; false -> "  \u2717"; null -> "" }) else "not recorded")
                    if (u.lastOk == false) u.lastSummary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                val op = st.ops[u.env]
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { vm.update(u.env) }, enabled = op != UpdateOp.WORKING && !st.refreshing) {
                        Text(when (op) { UpdateOp.WORKING -> "Updating\u2026"; UpdateOp.OK -> "Updated \u2713"; UpdateOp.FAILED -> "Failed \u2717 \u2014 retry"; null -> if (u.ok && !u.needsUpdate) "Update (current)" else "Update" })
                    }
                }
                st.results[u.env]?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}
