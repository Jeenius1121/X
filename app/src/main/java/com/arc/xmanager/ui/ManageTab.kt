package com.arc.xmanager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arc.xmanager.data.Filter
import com.arc.xmanager.data.Kind
import com.arc.xmanager.data.MatchMode
import com.arc.xmanager.data.Tweet
import com.arc.xmanager.data.TweetStatus

@Composable
fun ManageTab(vm: AppViewModel, openInX: (String) -> Unit, goToSettings: () -> Unit) {
    val filter by vm.filter.collectAsState()
    val results by vm.results.collectAsState()
    val selection by vm.selection.collectAsState()
    val queue by vm.queue.collectAsState()
    val account by vm.account.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    val update: (Filter) -> Unit = { vm.filter.value = it }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        item {
            Text("Gérer mes tweets", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.padding(4.dp))
        }

        if (queue.counts.queued > 0 || queue.counts.failed > 0 || queue.lastError != null) {
            item { QueueCard(vm, queue, account.loggedIn, goToSettings) }
        }

        if (queue.counts.stored == 0) {
            item {
                Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Aucun tweet en mémoire", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Importe l'archive de ton compte X (Réglages) pour pouvoir rechercher et supprimer " +
                                "tes anciens tweets et réponses. Les tweets publiés depuis l'app sont ajoutés automatiquement."
                        )
                        TextButton(onClick = goToSettings) { Text("Importer mon archive") }
                    }
                }
            }
            return@LazyColumn
        }

        item { FilterPanel(filter, update) }

        item {
            results.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (results.loading) "Recherche…" else "${results.items.size} résultat(s)",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("${selection.size} sélectionné(s)", style = MaterialTheme.typography.bodySmall)
                }
                if (selection.isEmpty()) {
                    TextButton(enabled = results.items.isNotEmpty(), onClick = vm::selectAllResults) {
                        Text("Tout sélectionner")
                    }
                } else {
                    TextButton(onClick = vm::clearSelection) { Text("Aucun") }
                }
            }
            Button(
                onClick = { confirm = true },
                enabled = selection.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Supprimer ${selection.size} tweet(s)") }
            HorizontalDivider(Modifier.padding(top = 12.dp))
        }

        items(results.items, key = { it.id }) { t ->
            TweetRow(t, t.id in selection, onToggle = { vm.toggle(t.id) }, onOpen = { openInX(t.id) })
            HorizontalDivider()
        }
    }

    if (confirm) {
        val preview = results.items.filter { it.id in selection }.take(3)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Supprimer ${selection.size} tweet(s) ?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("La suppression sur X est définitive et irréversible.")
                    preview.forEach {
                        Text("• " + it.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (selection.size > preview.size) {
                        Text("… et ${selection.size - preview.size} autre(s).", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "Les suppressions se font une par une en arrière-plan, au rythme autorisé par ton quota d'API.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; vm.deleteSelected() }) {
                    Text("Supprimer", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun QueueCard(vm: AppViewModel, queue: QueueInfo, loggedIn: Boolean, goToSettings: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("File de suppression", style = MaterialTheme.typography.titleMedium)
            Text("${queue.counts.queued} en attente · ${queue.counts.deleted} supprimés · ${queue.counts.failed} échecs")
            if (queue.counts.queued > 0 && queue.nextAttemptAt > System.currentTimeMillis()) {
                Text(
                    "Quota de l'API atteint : reprise automatique vers ${formatDateTime(queue.nextAttemptAt)}.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            queue.lastError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!loggedIn) {
                    Button(onClick = goToSettings) { Text("Se connecter") }
                }
                if (queue.counts.queued > 0) {
                    OutlinedButton(onClick = vm::cancelQueue) { Text("Annuler") }
                    if (loggedIn) TextButton(onClick = vm::forceRetryNow) { Text("Relancer") }
                }
                if (queue.counts.failed > 0) {
                    TextButton(onClick = vm::retryFailed) { Text("Réessayer les échecs") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(f: Filter, update: (Filter) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var pickAfter by remember { mutableStateOf(false) }
    var pickBefore by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = f.query,
            onValueChange = { update(f.copy(query = it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (f.query.isNotEmpty()) IconButton(onClick = { update(f.copy(query = "")) }) {
                    Icon(Icons.Filled.Clear, "Effacer")
                }
            },
            label = { Text("Mots à rechercher") },
            supportingText = {
                Text(
                    if (f.mode == MatchMode.REGEX) "Expression régulière Java, ex. : (?i)\\bfoot(ball)?\\b"
                    else "Sépare les mots par des espaces ; \"entre guillemets\" pour une expression. Accents ignorés."
                )
            },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MatchMode.entries.forEach { m ->
                FilterChip(selected = f.mode == m, onClick = { update(f.copy(mode = m)) }, label = { Text(m.label) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kind.entries.forEach { k ->
                FilterChip(selected = f.kind == k, onClick = { update(f.copy(kind = k)) }, label = { Text(k.label) })
            }
        }

        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Plus de filtres", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
        }

        if (expanded) {
            OutlinedTextField(
                value = f.exclude,
                onValueChange = { update(f.copy(exclude = it)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Sans ces mots") },
            )
            OutlinedTextField(
                value = f.replyToUser,
                onValueChange = { update(f.copy(replyToUser = it)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Réponses à @compte") },
            )
            OutlinedTextField(
                value = f.maxLikes?.toString() ?: "",
                onValueChange = { v -> update(f.copy(maxLikes = v.filter(Char::isDigit).toIntOrNull())) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text("Au maximum N likes (garder les populaires)") },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { pickAfter = true },
                    label = { Text("Du : " + (f.after?.let(::formatDate) ?: "—")) },
                    trailingIcon = if (f.after != null) ({
                        Icon(Icons.Filled.Clear, "Effacer", Modifier.clickable { update(f.copy(after = null)) })
                    }) else null,
                )
                AssistChip(
                    onClick = { pickBefore = true },
                    label = { Text("Au : " + (f.before?.let(::formatDate) ?: "—")) },
                    trailingIcon = if (f.before != null) ({
                        Icon(Icons.Filled.Clear, "Effacer", Modifier.clickable { update(f.copy(before = null)) })
                    }) else null,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = f.wholeWord, onCheckedChange = { update(f.copy(wholeWord = it)) })
                Text("Mot entier")
                Spacer(Modifier.width(16.dp))
                Checkbox(checked = f.caseSensitive, onCheckedChange = { update(f.copy(caseSensitive = it)) })
                Text("Respecter la casse")
            }
            TextButton(onClick = { update(Filter()) }) { Text("Réinitialiser les filtres") }
        }
    }

    if (pickAfter) DayPickerDialog(f.after, { pickAfter = false }) { update(f.copy(after = it)) }
    if (pickBefore) DayPickerDialog(f.before, { pickBefore = false }) { update(f.copy(before = it)) }
}

@Composable
private fun TweetRow(t: Tweet, selected: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            val meta = buildList {
                if (t.createdAt > 0) add(formatDate(t.createdAt))
                when {
                    t.isRetweet -> add("Retweet")
                    t.isReply -> add("Réponse" + (t.replyToUser?.let { " à @$it" } ?: ""))
                }
                if (t.likes > 0) add("♥ ${t.likes}")
            }.joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(t.text, maxLines = 5, overflow = TextOverflow.Ellipsis)
            if (t.status == TweetStatus.FAILED) {
                Text("Échec précédent : ${t.error}", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        TextButton(onClick = onOpen) { Text("Voir") }
    }
}
