package com.arc.xmanager.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.arc.xmanager.api.OAuth

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Help(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun SettingsTab(vm: AppViewModel) {
    val ctx = LocalContext.current
    val account by vm.account.collectAsState()
    val queue by vm.queue.collectAsState()
    val progress by vm.importProgress.collectAsState()
    val busy by vm.busy.collectAsState()
    val update by vm.update.collectAsState()
    var clientId by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(account.clientId) { if (clientId.isEmpty()) clientId = account.clientId }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importArchive)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Réglages", style = MaterialTheme.typography.headlineSmall)

        Section("Compte X (API officielle)") {
            Text(
                when {
                    busy -> "Connexion en cours…"
                    account.loggedIn -> "Connecté" + (account.username?.let { " : @$it" } ?: "")
                    else -> "Non connecté"
                }
            )
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Client ID OAuth 2.0") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = clientId.isNotBlank() && clientId.trim() != account.clientId,
                    onClick = { vm.saveClientId(clientId) },
                ) { Text("Enregistrer") }
                if (account.loggedIn) {
                    OutlinedButton(onClick = vm::disconnect) { Text("Se déconnecter") }
                } else {
                    Button(enabled = account.clientId.isNotBlank() && !busy, onClick = { vm.connect(ctx) }) {
                        Text("Se connecter à X")
                    }
                }
            }
            Help("Configuration unique (gratuite) sur developer.x.com :")
            Help(
                "1. Crée un projet et une app (offre Free).\n" +
                    "2. Dans « User authentication settings » : permissions « Read and write », " +
                    "type d'app « Native App ».\n" +
                    "3. Callback URI :"
            )
            SelectionContainer { Text(OAuth.REDIRECT_URI, fontFamily = FontFamily.Monospace) }
            Help("4. Website URL : n'importe quelle URL valide (ex. ton profil X).\n5. Copie le « Client ID » ci-dessus.")
            Help("Ton mot de passe est saisi sur la page officielle de X, jamais dans l'app. Les jetons sont chiffrés sur le téléphone.")
        }

        Section("Archive de mes tweets") {
            Text("${queue.counts.stored} tweets en mémoire · ${queue.counts.deleted} supprimés via l'app")
            Help(
                "L'API gratuite ne permet pas de lire tes tweets. Pour les rechercher, demande ton archive à X : " +
                    "x.com > Paramètres > Votre compte > Télécharger une archive de vos données (prêt sous ~24 h). " +
                    "Importe ensuite le fichier .zip reçu (ou data/tweets.js). Tu peux réimporter une archive " +
                    "plus récente : les doublons sont ignorés."
            )
            progress?.let {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Import en cours… $it tweets lus")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = progress == null, onClick = { picker.launch(arrayOf("*/*")) }) {
                    Text("Importer l'archive")
                }
                if (queue.counts.stored > 0) {
                    TextButton(onClick = { confirmClear = true }) { Text("Vider") }
                }
            }
        }

        Section("Mises à jour") {
            Text("Version installée : ${vm.currentVersion}")
            Text(
                when (val u = update) {
                    UpdateState.Idle -> "Vérification automatique à chaque ouverture de l'app."
                    UpdateState.Checking -> "Vérification…"
                    UpdateState.UpToDate -> "L'app est à jour ✔"
                    is UpdateState.Available -> "Nouvelle version disponible : ${u.info.versionName}"
                    is UpdateState.Downloading -> "Téléchargement… ${u.percent} %"
                    is UpdateState.Error -> u.message
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val available = update as? UpdateState.Available
                if (available != null) {
                    Button(onClick = { vm.installUpdate(ctx, available.info) }) { Text("Mettre à jour") }
                } else {
                    OutlinedButton(
                        enabled = update !is UpdateState.Checking && update !is UpdateState.Downloading,
                        onClick = { vm.checkUpdates(manual = true) },
                    ) { Text("Vérifier maintenant") }
                }
            }
        }

        Section("Limites de l'API gratuite") {
            Help(
                "L'offre gratuite de X n'autorise qu'un petit nombre de publications et de suppressions par 24 h " +
                    "(le quota exact dépend de X et peut changer). L'app respecte automatiquement ce quota : " +
                    "la file de suppression se met en pause et reprend toute seule quand le quota se recharge, " +
                    "même si l'app est fermée. Supprimer des milliers de tweets peut donc prendre plusieurs semaines."
            )
            Help(
                "Tout se fait via l'API officielle ou le site x.com : aucune automatisation cachée, " +
                    "donc aucun risque de suspension lié à l'app."
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Vider la mémoire de l'app ?") },
            text = { Text("Efface la liste locale des tweets importés. Rien n'est supprimé sur X.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearStored() }) { Text("Vider") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annuler") } },
        )
    }
}
