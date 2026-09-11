package com.tennispro.phone.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tennispro.phone.storage.MatchSession
import com.tennispro.phone.storage.MatchStorage
import com.tennispro.phone.storage.SessionServes
import com.tennispro.phone.vision.ServeScanService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The recordings on disk, with deletion one at a time or by selection —
 * shared by Home and Replay so both behave the same.
 *
 * Tap a row to open it (when [onOpen] is given); **Select**, or a long press on
 * a row, switches to checkboxes for deleting several at once. A recording still
 * being written ([recordingSessionId]) or being scanned for serves can't be
 * deleted: the recorder or the scanner is reading that file right now.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordingList(
    sessions: List<MatchSession>,
    storage: MatchStorage,
    recordingSessionId: String?,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    emptyText: String = "Nothing recorded yet.",
    onOpen: ((MatchSession) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val scanState by ServeScanService.state.collectAsState()
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var pendingDelete by remember { mutableStateOf<List<MatchSession>?>(null) }
    var deleting by remember { mutableStateOf(false) }

    // Directory sizes are a filesystem walk each; work them out once per list.
    val sizes = remember(sessions) { sessions.associate { it.meta.id to it.sizeBytes } }

    fun lockReason(session: MatchSession): String? = when {
        session.meta.id == recordingSessionId -> "Recording"
        scanState.isScanning(session.meta.id) -> "Finding serves"
        else -> null
    }

    fun toggle(session: MatchSession) {
        if (lockReason(session) != null) return
        selectedIds = if (session.meta.id in selectedIds) selectedIds - session.meta.id else selectedIds + session.meta.id
    }

    fun endSelection() {
        selecting = false
        selectedIds = emptySet()
    }

    // Drop selections that no longer exist (deleted elsewhere, or just became locked).
    val deletable = sessions.filter { lockReason(it) == null }
    val selected = deletable.filter { it.meta.id in selectedIds }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                if (selecting) {
                    Text("${selected.size} selected", style = MaterialTheme.typography.titleMedium)
                    Text(
                        formatBytes(selected.sumOf { sizes[it.meta.id] ?: 0L }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("Recordings (${sessions.size})", style = MaterialTheme.typography.titleMedium)
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (selecting) {
                val allSelected = deletable.isNotEmpty() && selected.size == deletable.size
                TextButton(onClick = {
                    selectedIds = if (allSelected) emptySet() else deletable.map { it.meta.id }.toSet()
                }) { Text(if (allSelected) "None" else "All") }
                TextButton(
                    enabled = selected.isNotEmpty() && !deleting,
                    onClick = { pendingDelete = selected },
                ) { Text("Delete (${selected.size})", color = if (selected.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = { endSelection() }) { Text("Cancel") }
            } else if (sessions.isNotEmpty()) {
                TextButton(onClick = { selecting = true }) { Text("Select") }
            }
        }

        if (sessions.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(emptyText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        LazyColumn(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(sessions, key = { it.meta.id }) { session ->
                val locked = lockReason(session)
                val isSelected = session.meta.id in selectedIds && locked == null
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CardDefaults.shape)
                        .combinedClickable(
                            onClick = {
                                when {
                                    selecting -> toggle(session)
                                    onOpen != null -> onOpen(session)
                                }
                            },
                            onLongClick = {
                                if (locked == null) {
                                    selecting = true
                                    selectedIds = selectedIds + session.meta.id
                                }
                            },
                        ),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selecting) {
                            Checkbox(checked = isSelected, onCheckedChange = { toggle(session) }, enabled = locked == null)
                        }
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(start = if (selecting) 0.dp else 6.dp),
                        ) {
                            Text(
                                startedLabel(session),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                details(session, sizes[session.meta.id] ?: 0L),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        when {
                            locked != null -> Text(
                                locked,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            !selecting -> TextButton(onClick = { pendingDelete = listOf(session) }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { toDelete ->
        val bytes = toDelete.sumOf { sizes[it.meta.id] ?: 0L }
        AlertDialog(
            onDismissRequest = { if (!deleting) pendingDelete = null },
            title = {
                Text(if (toDelete.size == 1) "Delete this recording?" else "Delete ${toDelete.size} recordings?")
            },
            text = {
                val what = if (toDelete.size == 1) "${startedLabel(toDelete[0])} — " else ""
                Text("${what}frees ${formatBytes(bytes)}, with any serves and marked moments found in it. This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        deleting = true
                        scope.launch {
                            // Gigabytes of video: unlinking is quick, but not main-thread quick.
                            withContext(Dispatchers.IO) { toDelete.forEach { storage.deleteSession(it) } }
                            deleting = false
                            pendingDelete = null
                            endSelection()
                            onChanged()
                        }
                    },
                ) { Text(if (deleting) "Deleting…" else "Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { pendingDelete = null }) { Text("Keep") }
            },
        )
    }
}

internal fun startedLabel(session: MatchSession): String =
    SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(session.meta.startedAtEpochMs))

private fun details(session: MatchSession, sizeBytes: Long): String = buildString {
    append(formatBytes(sizeBytes))
    session.meta.durationMs?.let { append(" · ${formatElapsed(it)}") }
    session.serves?.let { append(" · ${servesSummary(it)}") }
    if (session.bookmarks.isNotEmpty()) append(" · ${session.bookmarks.size} marked")
}

internal fun servesSummary(serves: SessionServes): String {
    if (serves.error != null) return "Serve scan didn't run"
    val count = serves.serves.size
    if (count == 0) return "No serves found"
    val fastest = serves.serves.mapNotNull { it.speedKmh }.maxOrNull()
    return "$count serve${if (count == 1) "" else "s"}" + (fastest?.let { " · fastest ${it.roundToInt()} km/h" } ?: "")
}
