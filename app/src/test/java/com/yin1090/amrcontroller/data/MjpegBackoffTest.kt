package com.yin1090.amrcontroller.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MjpegBackoffTest {

    @Test
    fun backoffProgressionAnd5000MsCap() {
        assertEquals(1_000L, MjpegBackoff.computeDelayMs(0))
        assertEquals(1_700L, MjpegBackoff.computeDelayMs(1))
        assertEquals(2_400L, MjpegBackoff.computeDelayMs(2))
        assertEquals(3_100L, MjpegBackoff.computeDelayMs(3))
        assertEquals(3_800L, MjpegBackoff.computeDelayMs(4))
        assertEquals(4_500L, MjpegBackoff.computeDelayMs(5))
        assertEquals(5_000L, MjpegBackoff.computeDelayMs(6))
        assertEquals(5_000L, MjpegBackoff.computeDelayMs(25))
    }

    @Test
    fun negativeAttemptClampsToBaseDelay() {
        assertEquals(1_000L, MjpegBackoff.computeDelayMs(-4))
    }

    @Test
    fun framerExtractsSingleJpegFrameWithMultipartNoise() {
        val framer = JpegStreamFramer()
        val payload = byteArrayOf(
            0x2D, 0x2D, 0x66, 0x72, // "--fr"
            0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0x03, 0xFF.toByte(), 0xD9.toByte(),
            0x0D, 0x0A
        )
        val frames = framer.feed(payload)
        assertEquals(1, frames.size)
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0x03, 0xFF.toByte(), 0xD9.toByte()),
            frames[0]
        )
    }

    @Test
    fun framerHandlesSplitSoiAndSplitEoiAcrossChunks() {
        val framer = JpegStreamFramer()
        val c1 = byteArrayOf(0x00, 0xFF.toByte())
        val c2 = byteArrayOf(0xD8.toByte(), 0x42, 0xFF.toByte())
        val c3 = byteArrayOf(0xD9.toByte())

        assertEquals(0, framer.feed(c1).size)
        assertEquals(0, framer.feed(c2).size)
        val out = framer.feed(c3)
        assertEquals(1, out.size)
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x42, 0xFF.toByte(), 0xD9.toByte()),
            out[0]
        )
    }

    @Test
    fun framerExtractsMultipleConsecutiveFrames() {
        val framer = JpegStreamFramer()
        val stream = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0x11, 0xFF.toByte(), 0xD9.toByte(),
            0xFF.toByte(), 0xD8.toByte(), 0x22, 0x33, 0xFF.toByte(), 0xD9.toByte()
        )
        val frames = framer.feed(stream)
        assertEquals(2, frames.size)
        assertEquals(0x11, frames[0][2].toInt() and 0xFF)
        assertEquals(0x22, frames[1][2].toInt() and 0xFF)
    }

    @Test
    fun framerResetsOversizedCorruptFrameWithoutOom() {
        val framer = JpegStreamFramer(maxFrameBytes = 8)
        val corrupt = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08
        )
        assertEquals(0, framer.feed(corrupt).size)

        val valid = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x7F, 0xFF.toByte(), 0xD9.toByte())
        val frames = framer.feed(valid)
        assertEquals(1, frames.size)
        assertEquals(5, frames[0].size)
    }
}
