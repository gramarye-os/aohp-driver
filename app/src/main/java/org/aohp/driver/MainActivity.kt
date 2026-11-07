package org.aohp.driver

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.aohp.driver.harness.HarnessScreen
import org.aohp.driver.runtime.RuntimeScreen
import org.aohp.driver.credentials.CredentialsScreen
import org.aohp.driver.terminal.TerminalScreen
import org.aohp.driver.ui.AohpDriverTheme
import org.aohp.driver.web.WebScreen

enum class Tab(val label: String, val icon: ImageVector) {
    Runtime("Runtime", Icons.Filled.Dns),
    Harness("Harness", Icons.Filled.Tune),
    Terminal("Terminal", Icons.Filled.Terminal),
    Web("Web", Icons.Filled.Language),
    Keys("Keys", Icons.Filled.Key),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        probeServices()
        setContent {
            AohpDriverTheme { DriverRoot() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            org.aohp.driver.terminal.TerminalHolder.destroyAll()
            (application as DriverApp).ptySessions.closeAll()
        }
    }

    /** Deliverable-1 proof: log getService + listContainers result. */
    private fun probeServices() {
        val app = application as DriverApp
        CoroutineScope(Dispatchers.IO).launch {
            val b = org.aohp.driver.binder.ServiceManagerCompat.getService("aohp_container")
            Log.i("AohpDriver", "probe: getService(aohp_container)=" + b + " err=" + org.aohp.driver.binder.ServiceManagerCompat.lastError)
            app.containers.listContainers()
                .onSuccess { Log.i("AohpDriver", "probe: listContainers=" + it) }
                .onFailure { Log.e("AohpDriver", "probe: listContainers FAILED", it) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DriverRoot() {
    var tab by rememberSaveable { mutableStateOf(Tab.Runtime) }
    var setup by rememberSaveable { mutableStateOf(false) }
    if (setup) {
        org.aohp.driver.setup.SetupWizard(onClose = { setup = false }, onFinish = { t -> setup = false; tab = t })
        return
    }
    // Hide the tab bar while the soft keyboard is up (terminal / web input);
    // insets consumed here so screens' imePadding() doesn't double-count the nav bar.
    val imeVisible = WindowInsets.isImeVisible
    Scaffold(
        bottomBar = {
            if (!imeVisible) NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding).consumeWindowInsets(padding).imePadding()
        // Screens are swapped, not stacked; Terminal/Web keep their state in
        // app-scoped objects (PtySessionRegistry / WebHolder) so switching is cheap.
        when (tab) {
            Tab.Runtime -> RuntimeScreen(m)
            Tab.Harness -> HarnessScreen(m, onGoToTab = { tab = it }, onSetup = { setup = true })
            Tab.Terminal -> TerminalScreen(m)
            Tab.Web -> WebScreen(m, onGoToTab = { tab = it })
            Tab.Keys -> CredentialsScreen(m)
        }
    }
}
