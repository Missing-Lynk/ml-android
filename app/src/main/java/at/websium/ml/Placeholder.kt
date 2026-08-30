package at.websium.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * The image that stands in for a picture which is not arriving: on screen while the feed is lost,
 * and on the wire as the still the broadcast carries through the same gap.
 *
 * One bitmap serves both, so the pilot and the viewers see the same thing. [encode] turns it into
 * a single access unit in the codec the session negotiated, framed the way the egress appsrc
 * carries the goggle's own access units: byte-stream, with the parameter sets in front of the
 * picture. That framing is what lets a still from the phone's encoder ride the same pipeline as
 * pictures from the goggle's; see the tee in jni/gstplayer.c.
 *
 * The still is encoded at [WIDTH] x [HEIGHT] rather than at whatever the goggle is sending. The
 * two descriptions differ regardless, because the parameter sets come from a different encoder,
 * and the muxer re-sends its sequence header on either kind of change.
 */
object Placeholder {

    /** what the still is encoded at, which is what the goggle sends at its default DVR setting */
    const val WIDTH = 1920
    const val HEIGHT = 1080

    /** enough for one still of flat colour, text and a glyph; a frame this simple spends far less */
    private const val BIT_RATE = 4_000_000
    private const val FRAME_RATE = 30

    /** the encoder is asked for one frame and every frame is a key frame */
    private const val KEY_FRAME_INTERVAL_S = 0

    private const val DEQUEUE_TIMEOUT_US = 100_000L

    /** an encoder that has produced nothing by now is one this phone cannot use for a still */
    private const val ENCODE_DEADLINE_MS = 4_000L

    private const val BACKGROUND = 0xFF0E1416.toInt()
    private const val ACCENT = 0xFF22D3C5.toInt()
    private const val CAPTION = 0xFFE2E6E5.toInt()

    /**
     * Draw the placeholder at [width] x [height]. The glyph and the caption are sized against the
     * frame rather than in dp: this bitmap is scaled to whatever the video view is, and encoded at
     * a size that has nothing to do with the screen's density.
     */
    fun render(context: Context, width: Int = WIDTH, height: Int = HEIGHT): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)

        val glyphSize = height / 4
        val glyph = ContextCompat.getDrawable(context, R.drawable.ic_stay_tuned)?.mutate()
        if (glyph != null) {
            glyph.setTint(ACCENT)
            val left = (width - glyphSize) / 2
            val top = height / 2 - glyphSize
            glyph.setBounds(left, top, left + glyphSize, top + glyphSize)
            glyph.draw(canvas)
        }

        val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CAPTION
            textSize = height / 12f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        canvas.drawText(
            context.getString(R.string.placeholder_caption),
            width / 2f,
            height / 2f + caption.textSize,
            caption,
        )
        return bitmap
    }

    /**
     * Encode [bitmap] as one access unit of [mimeType], parameter sets first. Null when this
     * phone's encoder refuses the format or produces nothing, which costs the placeholder and
     * leaves the broadcast holding the goggle's last frame as it did before.
     */
    fun encode(bitmap: Bitmap, mimeType: String): ByteArray? {
        var codec: MediaCodec? = null
        return try {
            val format = MediaFormat.createVideoFormat(mimeType, bitmap.width, bitmap.height)
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_INTERVAL_S)

            val created = MediaCodec.createEncoderByType(mimeType)
            codec = created
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            created.start()
            feed(created, bitmap)
            drain(created)
        } catch (failure: Exception) {
            Diagnostics.log("placeholder", "could not encode: ${failure.message}")
            null
        } finally {
            try {
                codec?.stop()
            } catch (ignored: IllegalStateException) {
                // an encoder that never started has nothing to stop
            }
            codec?.release()
        }
    }

    /**
     * Hand the encoder the one frame it is asked for, then close the stream so it flushes.
     */
    private fun feed(codec: MediaCodec, bitmap: Bitmap) {
        val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (index < 0) {
            throw IllegalStateException("the encoder took no input buffer")
        }
        val image = codec.getInputImage(index)
            ?: throw IllegalStateException("the encoder offered no YUV input image")
        writeYuv420(bitmap, image)
        val size = codec.getInputBuffer(index)?.capacity() ?: (bitmap.width * bitmap.height * 3 / 2)
        codec.queueInputBuffer(index, 0, size, 0, 0)

        val end = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (end >= 0) {
            codec.queueInputBuffer(end, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
    }

    /**
     * Collect the parameter sets and the first picture. Encoders report the sets either as a
     * buffer of their own or in front of the key frame, so both are accepted and a duplicate set
     * is left in place: a decoder reading the second copy of a set it already has re-reads the
     * same values.
     */
    private fun drain(codec: MediaCodec): ByteArray? {
        val parameterSets = ByteArrayOutputStream()
        val picture = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        val deadline = System.currentTimeMillis() + ENCODE_DEADLINE_MS

        while (picture.size() == 0 && System.currentTimeMillis() < deadline) {
            val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            if (index < 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    break
                }
                continue
            }

            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                val target = if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    parameterSets
                } else {
                    picture
                }
                target.write(read(buffer, info))
            }
            val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            codec.releaseOutputBuffer(index, false)
            if (ended) {
                break
            }
        }

        if (picture.size() == 0) {
            Diagnostics.log("placeholder", "the encoder produced no picture")
            return null
        }
        return parameterSets.toByteArray() + picture.toByteArray()
    }

    private fun read(buffer: ByteBuffer, info: MediaCodec.BufferInfo): ByteArray {
        val bytes = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        buffer.get(bytes)
        return bytes
    }

    /**
     * Convert the bitmap into the encoder's input image. The planes carry their own strides and
     * a chroma plane may be interleaved with the other one, so every write goes through the
     * plane's own row and pixel stride rather than assuming a layout.
     *
     * Chroma is the average of each 2x2 block, which keeps the caption's edges from fringing.
     */
    private fun writeYuv420(bitmap: Bitmap, image: Image) {
        val width = image.width
        val height = image.height
        if (bitmap.width != width || bitmap.height != height) {
            throw IllegalStateException(
                "the encoder wants ${width}x$height, the placeholder is ${bitmap.width}x${bitmap.height}"
            )
        }
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        for (row in 0 until height) {
            var offset = row * yRowStride
            for (column in 0 until width) {
                val pixel = pixels[row * width + column]
                yBuffer.put(offset, luma(red(pixel), green(pixel), blue(pixel)))
                offset += yPixelStride
            }
        }

        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        for (row in 0 until height / 2) {
            for (column in 0 until width / 2) {
                var sumRed = 0
                var sumGreen = 0
                var sumBlue = 0
                for (i in 0 until 2) {
                    for (j in 0 until 2) {
                        val pixel = pixels[(row * 2 + i) * width + (column * 2 + j)]
                        sumRed += red(pixel)
                        sumGreen += green(pixel)
                        sumBlue += blue(pixel)
                    }
                }
                val averageRed = sumRed / 4
                val averageGreen = sumGreen / 4
                val averageBlue = sumBlue / 4
                uBuffer.put(
                    row * uPlane.rowStride + column * uPlane.pixelStride,
                    chromaBlue(averageRed, averageGreen, averageBlue),
                )
                vBuffer.put(
                    row * vPlane.rowStride + column * vPlane.pixelStride,
                    chromaRed(averageRed, averageGreen, averageBlue),
                )
            }
        }
    }

    private fun red(pixel: Int): Int = (pixel shr 16) and 0xFF

    private fun green(pixel: Int): Int = (pixel shr 8) and 0xFF

    private fun blue(pixel: Int): Int = pixel and 0xFF

    /* BT.601 studio range, which is what a YUV420 encoder input is read as. */
    private fun luma(red: Int, green: Int, blue: Int): Byte {
        return clamp(((66 * red + 129 * green + 25 * blue + 128) shr 8) + 16)
    }

    private fun chromaBlue(red: Int, green: Int, blue: Int): Byte {
        return clamp(((-38 * red - 74 * green + 112 * blue + 128) shr 8) + 128)
    }

    private fun chromaRed(red: Int, green: Int, blue: Int): Byte {
        return clamp(((112 * red - 94 * green - 18 * blue + 128) shr 8) + 128)
    }

    private fun clamp(value: Int): Byte {
        return when {
            value < 0 -> 0
            value > 255 -> 255.toByte()
            else -> value.toByte()
        }
    }
}
