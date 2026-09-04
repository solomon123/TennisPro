package com.tennispro.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tennispro.core.scoring.MatchConfig
import com.tennispro.core.scoring.MatchProjection
import com.tennispro.core.scoring.ScoreFormat
import com.tennispro.core.scoring.Side
import com.tennispro.core.scoring.projection
import com.tennispro.phone.score.MatchController

/**
 * Manual scoring, driven from either the buttons here or the watch's tap /
 * double-tap / long-press — see [MatchController] for the shared gesture path.
 */
@Composable
fun ScoreScreen(
    controller: MatchController,
    onBack: () -> Unit,
) {
    val match by controller.match.collectAsState()
    var showNewMatchDialog by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("< Back") }
            Text("Score", style = MaterialTheme.typography.headlineSmall)
        }

        Spacer(Modifier.height(16.dp))

        val current = match
        if (current == null) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "No match in progress. Single tap on the watch scores a point " +
                        "for you, double tap for your opponent, and a long press undoes " +
                        "the last point.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { showNewMatchDialog = true }) { Text("Start match") }
            }
        } else {
            val projection = current.projection()

            ScoreBoard(projection, Modifier.weight(1f))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = projection.winner == null,
                    onClick = { controller.pointFor(Side.A) },
                ) { Text("Point — You") }

                Button(
                    modifier = Modifier.weight(1f),
                    enabled = projection.winner == null,
                    onClick = { controller.pointFor(Side.B) },
                ) { Text("Point — Opponent") }
            }

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    enabled = current.history.isNotEmpty(),
                    onClick = { controller.undo() },
                ) { Text("Undo") }

                TextButton(onClick = { confirmEnd = true }) {
                    Text("End match", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showNewMatchDialog) {
        NewMatchDialog(
            onConfirm = { config ->
                controller.startMatch(config)
                showNewMatchDialog = false
            },
            onDismiss = { showNewMatchDialog = false },
        )
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End this match?") },
            text = { Text("The score is cleared from the phone and the watch. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    controller.endMatch()
                    confirmEnd = false
                }) { Text("End match", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEnd = false }) { Text("Keep playing") }
            },
        )
    }
}

@Composable
private fun ScoreBoard(p: MatchProjection, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))

        if (p.completedSets.isNotEmpty()) {
            Text(
                p.completedSets.joinToString("   ") { "${it.gamesA}-${it.gamesB}" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }

        Text(
            "Games ${p.currentSetGamesA}–${p.currentSetGamesB}",
            style = MaterialTheme.typography.titleLarge,
        )

        Spacer(Modifier.height(12.dp))

        val (pointsA, pointsB) = ScoreFormat.pointLabels(p)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(pointsA, style = MaterialTheme.typography.displayLarge)
            Text(
                "  –  ",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(pointsB, style = MaterialTheme.typography.displayLarge)
        }
        if (p.inTiebreak) {
            Spacer(Modifier.height(4.dp))
            Text("Tiebreak", style = MaterialTheme.typography.labelLarge)
        }

        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (p.server == Side.A) "● You serve" else "You", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(24.dp))
            Text(if (p.server == Side.B) "● Opponent serves" else "Opponent", style = MaterialTheme.typography.bodyMedium)
        }

        p.winner?.let { winner ->
            Spacer(Modifier.height(20.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Text(
                    if (winner == Side.A) "You win the match!" else "Opponent wins the match",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun NewMatchDialog(
    onConfirm: (MatchConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var setsToWin by remember { mutableStateOf(2) } // best of 3
    var noAd by remember { mutableStateOf(false) }
    var finalSetTiebreak by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start a new match") },
        text = {
            Column {
                Text("Format", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormatOption("Best of 1", selected = setsToWin == 1) { setsToWin = 1 }
                    FormatOption("Best of 3", selected = setsToWin == 2) { setsToWin = 2 }
                    FormatOption("Best of 5", selected = setsToWin == 3) { setsToWin = 3 }
                }

                Spacer(Modifier.height(16.dp))

                ToggleRow("No-ad scoring", noAd) { noAd = it }
                ToggleRow("Tiebreak in final set", finalSetTiebreak) { finalSetTiebreak = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    MatchConfig(
                        setsToWin = setsToWin,
                        noAd = noAd,
                        finalSetTiebreak = finalSetTiebreak,
                    ),
                )
            }) { Text("Start") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun FormatOption(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, colors = ButtonDefaults.buttonColors()) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
