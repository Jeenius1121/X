package com.arc.xmanager.ui

import android.app.Activity
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.arc.xmanager.MainActivity

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("X", Icons.Filled.Home),
    Tab("Écrire", Icons.Filled.Edit),
    Tab("Gérer", Icons.Filled.Delete),
    Tab("Réglages", Icons.Filled.Settings),
)

@Composable
fun ArcApp(vm: AppViewModel, webView: WebView) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsState()

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.message.value = null
        }
    }

    UpdateDialog(vm)

    val openInX: (String) -> Unit = { id ->
        webView.loadUrl("https://x.com/i/status/$id")
        tab = 0
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = {
                            // Toucher à nouveau l'onglet X recharge l'accueil (utile si la page bloque).
                            if (i == 0 && tab == 0) webView.loadUrl(MainActivity.HOME)
                            tab = i
                        },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .fillMaxSize()
        ) {
            when (tab) {
                0 -> WebTab(webView)
                1 -> ComposeTab(vm) { tab = 3 }
                2 -> ManageTab(vm, openInX) { tab = 3 }
                3 -> SettingsTab(vm)
            }
        }
    }
}

@Composable
private fun WebTab(webView: WebView) {
    val activity = LocalContext.current as? Activity
    BackHandler(enabled = true) {
        if (webView.canGoBack()) webView.goBack() else activity?.moveTaskToBack(true)
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView
        },
    )
}

@Composable
private fun UpdateDialog(vm: AppViewModel) {
    val state by vm.update.collectAsState()
    val dismissed by vm.updateDialogDismissed.collectAsState()
    val ctx = LocalContext.current
    val info = when (val st = state) {
        is UpdateState.Available -> st.info
        is UpdateState.Downloading -> st.info
        else -> return
    }
    if (dismissed) return
    val downloading = state as? UpdateState.Downloading
    AlertDialog(
        onDismissRequest = { if (downloading == null) vm.updateDialogDismissed.value = true },
        title = { Text("Mise à jour disponible") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text("${info.versionName} (actuelle : ${vm.currentVersion})", style = MaterialTheme.typography.titleSmall)
                if (info.notes.isNotBlank()) Text(info.notes, Modifier.padding(top = 8.dp))
                if (downloading != null) {
                    LinearProgressIndicator(
                        progress = { downloading.percent / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    )
                    Text("Téléchargement… ${downloading.percent} %", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = downloading == null, onClick = { vm.installUpdate(ctx, info) }) {
                Text("Mettre à jour")
            }
        },
        dismissButton = {
            if (downloading == null) TextButton(onClick = { vm.updateDialogDismissed.value = true }) { Text("Plus tard") }
        },
    )
}
