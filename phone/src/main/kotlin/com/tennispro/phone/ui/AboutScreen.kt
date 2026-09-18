package com.tennispro.phone.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo

/**
 * Version and update state, for a tester who needs to answer "which build am I
 * on, and is it the current one?" — including on the watch, which has no screen
 * to read a version off.
 */
@Composable
fun AboutScreen(
    diagnostics: WatchDiagnostics,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val phone = remember { phoneVersion(context) }
    var update by remember { mutableStateOf<UpdateState>(UpdateState.Checking) }

    // One ping is enough to learn the watch's build. Reusing the diagnostics
    // burst rather than a bespoke round trip keeps a single tested ping path.
    LaunchedEffect(Unit) { diagnostics.runLatencyBurst(count = 1) }

    LaunchedEffect(Unit) { update = checkForUpdate(context) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("< Back") }
            Text("About", style = MaterialTheme.typography.headlineSmall)
        }

        Spacer(Modifier.height(16.dp))

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) {
                Text("TennisReplay", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Detail("Phone app", phone)
                Detail(
                    "Watch app",
                    diagnostics.watchInfo?.appVersion?.let { "$it" }
                        ?: "Not reached — check the watch is connected",
                )
                Detail("Package", context.packageName)
                Detail("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            }
        }

        Spacer(Modifier.height(16.dp))

        Text("Updates", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        when (val state = update) {
            UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp))
                Spacer(Modifier.height(8.dp))
                Text("  Checking with Google Play…", style = MaterialTheme.typography.bodyMedium)
            }

            UpdateState.UpToDate -> Text(
                "You are on the latest build.",
                style = MaterialTheme.typography.bodyMedium,
            )

            is UpdateState.Available -> Column {
                Text(
                    "Version code ${state.versionCode} is available.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { startUpdate(context, state.manager, state.info) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Update now") }
            }

            // In-app update checks only answer for a build Play itself installed,
            // and can stay quiet on a test track. Never a dead end: the store page
            // below always shows the truth.
            is UpdateState.Unavailable -> Text(
                state.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { openPlayListing(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open in Play Store") }

        Spacer(Modifier.height(24.dp))

        Text("Privacy", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Video, audio and results stay on this phone. The app has no internet " +
                "permission, so nothing it records can leave the device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { openUrl(context, PRIVACY_URL) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Privacy policy") }

        Spacer(Modifier.height(8.dp))
        Text(
            "Questions or bugs: $CONTACT_EMAIL",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private sealed interface UpdateState {
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(
        val versionCode: Int,
        val manager: AppUpdateManager,
        val info: AppUpdateInfo,
    ) : UpdateState
    data class Unavailable(val reason: String) : UpdateState
}

/**
 * Reads the version off the installed package rather than `BuildConfig`, so what
 * is shown is what Android actually has on disk — the question a tester is really
 * asking when a build seems not to have arrived.
 */
private fun phoneVersion(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
    "${info.versionName} ($code)"
}.getOrElse { "unknown" }

private suspend fun checkForUpdate(context: Context): UpdateState {
    val manager = AppUpdateManagerFactory.create(context)
    val info = runCatching { manager.requestAppUpdateInfo() }.getOrElse {
        // Sideloaded build, no Play Store, or the check failed. Not worth an
        // error dialog — the store button below is the fallback.
        return UpdateState.Unavailable("Google Play could not be asked on this build.")
    }
    return when {
        info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE -> UpdateState.UpToDate
        !info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) ->
            UpdateState.Unavailable("An update exists but must be installed from the Play Store.")
        else -> UpdateState.Available(info.availableVersionCode(), manager, info)
    }
}

private fun startUpdate(context: Context, manager: AppUpdateManager, info: AppUpdateInfo) {
    val activity = context.findActivity() ?: run {
        openPlayListing(context)
        return
    }
    // IMMEDIATE, not FLEXIBLE: Play takes the screen, installs, and relaunches.
    // A half-downloaded flexible update sitting behind a snackbar is worse than
    // useless on court.
    runCatching {
        manager.startUpdateFlowForResult(
            info,
            activity,
            AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
            UPDATE_REQUEST_CODE,
        )
    }.onFailure { openPlayListing(context) }
}

private fun openPlayListing(context: Context) {
    // market:// opens the Play Store app directly; the https form is the fallback
    // for a device without it, which a Wear-paired phone always has anyway.
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
        openUrl(context, "https://play.google.com/store/apps/details?id=${context.packageName}")
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val UPDATE_REQUEST_CODE = 4711
private const val CONTACT_EMAIL = "ssoollit@gmail.com"
private const val PRIVACY_URL =
    "https://github.com/solomon123/TennisPro/blob/claude/tennis-match-monitor-63q3fy/docs/PRIVACY.md"
