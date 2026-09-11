package com.tennispro.phone.vision

import android.graphics.Bitmap
import com.tennispro.core.vision.GrayscaleFrame

/** Luma of a bitmap as a [GrayscaleFrame], with the same BT.601 weights `DriftDetector` samples with. */
fun Bitmap.toGrayscaleFrame(): GrayscaleFrame {
    // getPixels throws on hardware bitmaps; copy those to a readable config first.
    val readable = if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this
    val pixels = IntArray(readable.width * readable.height)
    readable.getPixels(pixels, 0, readable.width, 0, 0, readable.width, readable.height)
    for (i in pixels.indices) {
        val p = pixels[i]
        pixels[i] = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
    }
    return GrayscaleFrame(readable.width, readable.height, pixels)
}
