package com.tennispro.phone.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.tennispro.phone.camera.RecordingService

/**
 * The live camera preview, shared between [RecordScreen] and [CalibrateScreen]
 * — both need to see the whole court, and [CalibrateScreen] additionally
 * freezes a frame from it via the exposed [PreviewView] (see its `.bitmap`
 * property), rather than binding a separate CameraX use case.
 *
 * FIT_CENTER, not the default FILL_CENTER: the user has to see the whole
 * frame to know the baseline and both service boxes are inside it — for
 * recording that's framing, for calibration it's the actual court corners
 * that need to be visible and tappable. A cropped preview would hide exactly
 * the part that matters either way.
 */
@Composable
fun CameraPreview(
    service: RecordingService?,
    modifier: Modifier = Modifier,
    onPreviewViewReady: (PreviewView) -> Unit = {},
) {
    val previewView = remember { mutableStateOf<PreviewView?>(null) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FIT_CENTER
                // PERFORMANCE (the default) renders through a SurfaceView, which
                // does its own rotation/transform handling largely outside
                // CameraX's control and — confirmed on-device — got the front
                // camera's orientation wrong even with an explicit correction
                // applied at the CameraX use-case level. COMPATIBLE renders
                // through a TextureView instead, which CameraX can transform
                // directly and reliably for both camera facings.
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                previewView.value = this
                onPreviewViewReady(this)
            }
        },
    )

    DisposableEffect(service, previewView.value) {
        val view = previewView.value
        if (service != null && view != null) {
            service.attachPreview(view.surfaceProvider)
        }
        onDispose { service?.detachPreview() }
    }
}
