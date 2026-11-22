package com.arc.xmanager.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Ouvre x.com dans un onglet Chrome intégré (Custom Tab) : vraie session Chrome, connexion fiable. */
fun openX(ctx: Context, url: String) {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setDefaultColorSchemeParams(
                CustomTabColorSchemeParams.Builder().setToolbarColor(0xFF000000.toInt()).build()
            )
            .build()
            .launchUrl(ctx, uri)
    } catch (_: ActivityNotFoundException) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }
}

fun openTweet(ctx: Context, id: String) = openX(ctx, "https://x.com/i/status/$id")

@Composable
fun XTab(vm: AppViewModel) {
    val ctx = LocalContext.current
    val account by vm.account.collectAsState()
    val user = account.username

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("X", style = MaterialTheme.typography.headlineSmall)
        Text(
            "X s'ouvre dans Chrome, par-dessus l'app. Si tu es déjà connecté à X dans Chrome, " +
                "tu le seras ici aussi. Reviens dans X Manager avec le bouton retour ou la croix en haut à gauche.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { openX(ctx, "https://x.com/home") }, modifier = Modifier.fillMaxWidth()) {
            Text("Ouvrir X")
        }
        OutlinedButton(onClick = { openX(ctx, "https://x.com/notifications") }, modifier = Modifier.fillMaxWidth()) {
            Text("Notifications")
        }
        OutlinedButton(onClick = { openX(ctx, "https://x.com/notifications/mentions") }, modifier = Modifier.fillMaxWidth()) {
            Text("Mentions et réponses")
        }
        if (user != null) {
            OutlinedButton(onClick = { openX(ctx, "https://x.com/$user/with_replies") }, modifier = Modifier.fillMaxWidth()) {
                Text("Mes tweets et réponses (@$user)")
            }
        }
        OutlinedButton(onClick = { openX(ctx, "https://x.com/explore") }, modifier = Modifier.fillMaxWidth()) {
            Text("Rechercher")
        }
    }
}
