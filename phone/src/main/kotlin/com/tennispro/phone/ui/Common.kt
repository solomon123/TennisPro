package com.tennispro.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun Chip(text: String, tint: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(tint.copy(alpha = 0.18f), RoundedCornerShape(50))
            .padding(PaddingValues(horizontal = 12.dp, vertical = 6.dp)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = tint, style = MaterialTheme.typography.labelLarge)
    }
}

fun formatElapsed(millis: Long): String {
    val total = millis.coerceAtLeast(0L)
    val hours = TimeUnit.MILLISECONDS.toHours(total)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(total) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(total) % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
}
