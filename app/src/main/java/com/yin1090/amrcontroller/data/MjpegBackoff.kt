package com.yin1090.amrcontroller.data

import java.io.ByteArrayOutputStream

object MjpegBackoff {
    const val BASE_DELAY_MS = 1_000L
    const val STEP_DELAY_MS = 700L
    const val MAX_DELAY_MS = 5_000L

    fun computeDelayMs(attempt: Int): Long {
        val safeAttempt = attempt.coerceAtLeast(0).toLong()
        return minOf(MAX_DELAY_MS, BASE_DELAY_MS + STEP_DELAY_MS * safeAttempt)
    }
}

class JpegStreamFramer(
    private val maxFrameBytes: Int = MAX_FRAME_BYTES
) {
    private val buffer = ByteArrayOutputStream(65_536)
    private var inFrame = false
    private var prevByte = -1

    fun reset() {
        buffer.reset()
        inFrame = false
        prevByte = -1
    }

    fun feed(chunk: ByteArray, length: Int = chunk.size): List<ByteArray> {
        val frames = ArrayList<ByteArray>(2)
        val limit = length.coerceIn(0, chunk.size)
        for (i in 0 until limit) {
            val b = chunk[i].toInt() and 0xFF
            if (!inFrame) {
                if (prevByte == 0xFF && b == 0xD8) {
                    buffer.reset()
                    buffer.write(0xFF)
                    buffer.write(0xD8)
                    inFrame = true
                    prevByte = -1
                    continue
                }
            } else {
                if (buffer.size() >= maxFrameBytes) {
                    reset()
                    prevByte = b
                    continue
                }
                buffer.write(b)
                if (prevByte == 0xFF && b == 0xD9) {
                    frames.add(buffer.toByteArray())
                    buffer.reset()
                    inFrame = false
                    prevByte = -1
                    continue
                }
            }
            prevByte = b
        }
        return frames
    }

    companion object {
        const val MAX_FRAME_BYTES = 2 * 1024 * 1024
    }
}
