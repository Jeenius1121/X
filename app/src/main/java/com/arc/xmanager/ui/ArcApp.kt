package com.arc.xmanager.ui

import android.app.Activity
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
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
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
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
