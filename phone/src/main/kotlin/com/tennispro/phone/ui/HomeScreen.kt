package com.tennispro.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tennispro.phone.storage.MatchSession
import com.tennispro.phone.storage.MatchStorage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    storage: MatchStorage,
    diagnostics: WatchDiagnostics,
    onRecord: () -> Unit,
    onWatchCheck: () -> Unit,
) {
    var sessions by remember { mutableStateOf<List<MatchSession>>(emptyList()) }
    var totalBytes by remember { mutableStateOf(0L) }
    var freeBytes by remember { mutableStateOf(0L) }
    var confirmDelete by remember { mutableStateOf<MatchSession?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableStateOf(0) }

    LaunchedEffect(reloadToken) {
        sessions = storage.listSessions()
        totalBytes = storage.totalBytes()
        freeBytes = storage.freeBytes()
    }

    LaunchedEffect(Unit) { diagnostics.refresh() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        Text("TennisPro", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Phase 0 — recording and watch link",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            val watchConnected = diagnostics.watches.isNotEmpty()
            Chip(
                text = if (watchConnected) "Watch connected" else "No watch",
                tint = if (watchConnected) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Spacer(Modifier.width(8.dp))
            Chip(
                text = "${formatBytes(totalBytes)} used · ${formatBytes(freeBytes)} free",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRecord) { Text("Record a match") }
            OutlinedButton(onClick = onWatchCheck) { Text("Watch check") }
            OutlinedButton(
                onClick = { reloadToken++ },
            ) { Text("Refresh") }
        }

        Spacer(Modifier.height(20.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Recordings (${sessions.size})",
                style = MaterialTheme.typography.titleMedium,
            )
            if (sessions.isNotEmpty()) {
                TextButton(onClick = { confirmDeleteAll = true }) {
                    Text("Delete all", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (sessions.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Nothing recorded yet. Raw footage is kept in full — clear it here " +
                    "before a match if space is tight.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(sessions, key = { it.meta.id }) { session ->
                SessionRow(session, onDelete = { confirmDelete = session })
            }
        }
    }

    confirmDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this recording?") },
            text = {
                Text(
                    "${session.meta.id} — ${formatBytes(session.sizeBytes)}, " +
                        "${session.bookmarks.size} marked moment(s). This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    storage.deleteSession(session)
                    confirmDelete = null
                    reloadToken++
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Keep") }
            },
        )
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all ${sessions.size} recordings?") },
            text = { Text("Frees ${formatBytes(totalBytes)}. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    sessions.forEach { storage.deleteSession(it) }
                    confirmDeleteAll = false
                    reloadToken++
                }) { Text("Delete all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun SessionRow(session: MatchSession, onDelete: () -> Unit) {
    val started = remember(session.meta.startedAtEpochMs) {
        SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())
            .format(Date(session.meta.startedAtEpochMs))
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(started, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(formatBytes(session.sizeBytes))
                        session.meta.durationMs?.let { append(" · ${formatElapsed(it)}") }
                        session.meta.resolution?.let { append(" · $it") }
                        session.meta.frameRate?.let { append(" @ ${it}fps") }
                        if (session.bookmarks.isNotEmpty()) {
                            append(" · ${session.bookmarks.size} marked")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDelete) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
