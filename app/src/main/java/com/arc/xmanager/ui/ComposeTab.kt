package com.arc.xmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arc.xmanager.data.PostStatus

@Composable
fun ComposeTab(vm: AppViewModel, goToSettings: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var reply by rememberSaveable { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    val account by vm.account.collectAsState()
    val busy by vm.busy.collectAsState()
    val scheduled by vm.scheduled.collectAsState()

    val length = xLength(text)
    val replyId = extractTweetId(reply)
    val replyInvalid = reply.isNotBlank() && replyId == null
    val canSend = text.isNotBlank() && length <= 280 && !replyInvalid && account.loggedIn && !busy
    val reset = { text = ""; reply = "" }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Écrire", style = MaterialTheme.typography.headlineSmall)

        if (!account.loggedIn) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Connecte ton compte X pour publier.")
                    TextButton(onClick = goToSettings) { Text("Aller dans Réglages") }
                }
            }
        }

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 5,
            label = { Text("Quoi de neuf ?") },
            isError = length > 280,
            supportingText = { Text("$length / 280") },
        )
        OutlinedTextField(
            value = reply,
            onValueChange = { reply = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("En réponse à (lien ou ID du tweet, facultatif)") },
            isError = replyInvalid,
            supportingText = if (replyInvalid) ({ Text("Lien ou ID non reconnu") }) else null,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = canSend, onClick = { vm.publishNow(text.trim(), replyId, reset) }) {
                Text(if (busy) "Envoi…" else "Publier")
            }
            OutlinedButton(enabled = canSend, onClick = { picking = true }) { Text("Programmer…") }
        }

        if (scheduled.isNotEmpty()) {
            Text("Posts programmés", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Text(
                "La publication se fait en arrière-plan à l'heure choisie (téléphone allumé et connecté).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            scheduled.forEach { s ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            val status = when (s.status) {
                                PostStatus.PENDING -> "Prévu le ${formatDateTime(s.at)}"
                                PostStatus.SENT -> "Publié ✔"
                                else -> "Échec : ${s.error ?: "inconnu"}"
                            }
                            Text(status, style = MaterialTheme.typography.labelMedium,
                                color = if (s.status == PostStatus.FAILED) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary)
                            Text(s.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { vm.cancelScheduled(s.id) }) {
                            Icon(Icons.Filled.Close, contentDescription = if (s.status == PostStatus.PENDING) "Annuler" else "Retirer")
                        }
                    }
                }
            }
        }
    }

    if (picking) {
        DateTimePickerDialog(onDismiss = { picking = false }) { at ->
            if (at <= System.currentTimeMillis()) vm.say("Choisis une heure dans le futur.")
            else vm.schedule(text.trim(), replyId, at, reset)
        }
    }
}
