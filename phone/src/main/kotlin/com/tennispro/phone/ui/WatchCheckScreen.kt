package com.tennispro.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tennispro.phone.ui.theme.MonoStat
import kotlinx.coroutines.launch

/**
 * Phase 0's acceptance test, as a screen.
 *
 * It answers two questions that decide whether the watch is a viable output for
 * line calls at all: does a message reach the wrist, and how long does the round
 * trip actually take on this hardware, outdoors, through Bluetooth. Everything in
 * Phases 1 and 4 rides on the answers.
 */
@Composable
fun WatchCheckScreen(
    diagnostics: WatchDiagnostics,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { diagnostics.refresh() }
    LaunchedEffect(Unit) { diagnostics.observeGestures() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("< Back") }
            Spacer(Modifier.height(0.dp))
            Text("Watch check", style = MaterialTheme.typography.headlineSmall)
        }

        Spacer(Modifier.height(12.dp))

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) {
                Text("Connection", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(diagnostics.status, style = MaterialTheme.typography.bodyMedium)

                diagnostics.watchInfo?.let { info ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${info.manufacturer} ${info.model} · API ${info.apiLevel} · app ${info.appVersion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { scope.launch { diagnostics.refresh() } }) {
                Text("Find watch")
            }
            OutlinedButton(onClick = { scope.launch { diagnostics.testBuzz() } }) {
                Text("Test buzz")
            }
            OutlinedButton(onClick = { scope.launch { diagnostics.simulateOutCall() } }) {
                Text("Simulate OUT")
            }
        }

        Spacer(Modifier.height(20.dp))

        Text("Round-trip latency", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "An out-call is only useful if it reaches your wrist while the point is " +
                "still live. Run this on court, not on the sofa — Bluetooth range and " +
                "body position both matter.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        Button(
            enabled = !diagnostics.busy,
            onClick = { scope.launch { diagnostics.runLatencyBurst() } },
        ) { Text(if (diagnostics.busy) "Measuring…" else "Run 10 pings") }

        if (diagnostics.busy) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        diagnostics.latency?.let { stats ->
            Spacer(Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Stat("median", stats.median?.let { "$it ms" } ?: "—")
                        Stat("min", stats.min?.let { "$it ms" } ?: "—")
                        Stat("max", stats.max?.let { "$it ms" } ?: "—")
                        Stat("lost", "${stats.lost}/${stats.requested}")
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        verdict(stats),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Text("Watch input", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            diagnostics.lastGesture?.let { "Last gesture: $it (${diagnostics.gestureCount} total)" }
                ?: "Tap, double-tap or long-press the watch to check input reaches the phone.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MonoStat)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Turns the number into the decision it informs. The thresholds are judgement
 * calls, not measurements — they exist so a bad link is obvious on court rather
 * than being discovered in Phase 4.
 */
private fun verdict(stats: LatencyStats): String {
    val median = stats.median ?: return "No replies came back. The watch app may not be running."
    val lossNote = if (stats.lost > 0) " ${stats.lost} of ${stats.requested} pings were lost." else ""
    return when {
        median < 250 -> "Good. An out-call will land well inside the point.$lossNote"
        median < 600 -> "Usable. The buzz will feel slightly late but still within the point.$lossNote"
        else -> "Too slow to trust for live line calls — the phone's speaker should be the " +
            "primary alert, with the watch as confirmation.$lossNote"
    }
}
