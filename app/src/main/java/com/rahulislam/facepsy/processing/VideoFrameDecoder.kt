package com.rahulislam.facepsy.processing

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/**
 * Decodes every frame of the video track of [file] in presentation order with the
 * hardware decoder (MediaCodec), handing each frame to the caller as a YUV_420_888
 * [Image]. Much faster than `MediaMetadataRetriever`, which converts every frame to a
 * full-size Bitmap.
 */
class VideoFrameDecoder(private val file: File) {

    /** True if the decoder reports BT.709 color (typical for HD video). */
    var isBt709 = false
        private set

    /**
     * Calls [onFrame] with (frame index, presentation time µs, image) for each frame. The
     * image is only valid during the call.
     */
    fun decode(onFrame: (Int, Long, Image) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var frameIndex = 0
            while (true) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(inIndex)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        isBt709 = out.containsKey(MediaFormat.KEY_COLOR_STANDARD) &&
                                out.getInteger(MediaFormat.KEY_COLOR_STANDARD) == MediaFormat.COLOR_STANDARD_BT709
                    }
                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            codec.getOutputImage(outIndex)?.use { image ->
                                onFrame(frameIndex, info.presentationTimeUs, image)
                            }
                            frameIndex++
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        } finally {
            codec?.let {
                try { it.stop() } catch (e: IllegalStateException) { }
                it.release()
            }
            extractor.release()
        }
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
    }
}
