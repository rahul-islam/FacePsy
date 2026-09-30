package com.rahulislam.facepsy.processing

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.Image

/**
 * Source of upright, full-color crops of one frame, in the coordinate space of the image
 * given to ML Kit (i.e. after rotation). Lets [FaceFeatureExtractor] crop faces and eyes
 * without first converting the whole frame to a Bitmap.
 */
interface FrameCropper {
    /** Upright frame size. */
    val width: Int
    val height: Int

    /** Returns [rect] as an ARGB bitmap; throws [IllegalArgumentException] if it's outside the frame. */
    fun crop(rect: Rect): Bitmap
}

/** Crops from an already upright Bitmap (photos). */
class BitmapFrameCropper(private val bitmap: Bitmap) : FrameCropper {
    override val width get() = bitmap.width
    override val height get() = bitmap.height

    override fun crop(rect: Rect): Bitmap =
            Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
}

/**
 * Crops from a decoded YUV_420_888 video frame, converting only the requested region to
 * RGB and applying [rotationDegrees] (clockwise, as passed to ML Kit).
 *
 * The planes are copied into arrays once (on the first crop), and the conversion uses
 * fixed-point integer math: a full face crop takes a few milliseconds.
 *
 * @param bt709 use BT.709 coefficients (HD video) instead of BT.601; limited range.
 */
class YuvFrameCropper(private val image: Image, private val rotationDegrees: Int, bt709: Boolean) : FrameCropper {

    private val crop = image.cropRect
    private val srcWidth = crop.width()
    private val srcHeight = crop.height()

    // Limited-range YUV -> RGB coefficients, scaled by 1024.
    private val kY = 1192                                // 1.164
    private val kRV = if (bt709) 1836 else 1634          // 1.793 / 1.596
    private val kGU = if (bt709) 218 else 400            // 0.213 / 0.391
    private val kGV = if (bt709) 546 else 833            // 0.533 / 0.813
    private val kBU = if (bt709) 2163 else 2066          // 2.112 / 2.018

    override val width = if (rotationDegrees % 180 == 0) srcWidth else srcHeight
    override val height = if (rotationDegrees % 180 == 0) srcHeight else srcWidth

    private var planes: Array<ByteArray>? = null

    private fun planes(): Array<ByteArray> = planes ?: Array(3) { i ->
        val buffer = image.planes[i].buffer.duplicate()
        buffer.rewind()
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    }.also { planes = it }

    override fun crop(rect: Rect): Bitmap {
        require(rect.left >= 0 && rect.top >= 0 && rect.width() > 0 && rect.height() > 0 &&
                rect.right <= width && rect.bottom <= height) { "Crop $rect outside ${width}x$height" }

        val (yArr, uArr, vArr) = planes()
        val yRow = image.planes[0].rowStride
        val yPix = image.planes[0].pixelStride
        val uvRow = image.planes[1].rowStride
        val uvPix = image.planes[1].pixelStride

        // Source position of upright (x, y) = origin + x * step, per rotation.
        val (stepX, stepY) = when (rotationDegrees) {
            90 -> 0 to -1
            180 -> -1 to 0
            270 -> 0 to 1
            else -> 1 to 0
        }

        val w = rect.width()
        val h = rect.height()
        val pixels = IntArray(w * h)
        var i = 0
        for (y in rect.top until rect.bottom) {
            // Source coordinates of the row's first pixel (x = rect.left).
            var sx: Int
            var sy: Int
            when (rotationDegrees) {
                90 -> { sx = y; sy = srcHeight - 1 - rect.left }
                180 -> { sx = srcWidth - 1 - rect.left; sy = srcHeight - 1 - y }
                270 -> { sx = srcWidth - 1 - y; sy = rect.left }
                else -> { sx = rect.left; sy = y }
            }
            for (x in 0 until w) {
                val px = sx + crop.left
                val py = sy + crop.top
                val yv = ((yArr[py * yRow + px * yPix].toInt() and 0xFF) - 16) * kY
                val uvIndex = (py shr 1) * uvRow + (px shr 1) * uvPix
                val u = (uArr[uvIndex].toInt() and 0xFF) - 128
                val v = (vArr[uvIndex].toInt() and 0xFF) - 128
                val r = ((yv + kRV * v) shr 10).coerceIn(0, 255)
                val g = ((yv - kGU * u - kGV * v) shr 10).coerceIn(0, 255)
                val b = ((yv + kBU * u) shr 10).coerceIn(0, 255)
                pixels[i++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                sx += stepX
                sy += stepY
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
