package com.arc.xmanager.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Sélecteur de date ; renvoie le début de la journée choisie (fuseau local), en ms. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayPickerDialog(initial: Long?, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val initialUtc = initial?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
    val state = rememberDatePickerState(initialSelectedDateMillis = initialUtc)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { utc ->
                    val day = Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate()
                    onPick(day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
                }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    ) { DatePicker(state = state) }
}

/** Sélecteur date puis heure ; renvoie un instant (ms). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimePickerDialog(onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    var day by remember { mutableStateOf<LocalDate?>(null) }
    if (day == null) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        day = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                }) { Text("Suivant") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
        ) { DatePicker(state = state) }
    } else {
        val now = LocalTime.now().plusMinutes(10)
        val time = rememberTimePickerState(initialHour = now.hour, initialMinute = now.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Heure de publication") },
            text = { TimePicker(state = time) },
            confirmButton = {
                TextButton(onClick = {
                    val at = day!!.atTime(time.hour, time.minute)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    onPick(at)
                    onDismiss()
                }) { Text("Programmer") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
        )
    }
}
