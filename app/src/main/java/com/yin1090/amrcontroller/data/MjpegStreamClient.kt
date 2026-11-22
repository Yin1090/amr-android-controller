package com.yin1090.amrcontroller.data

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

sealed interface MjpegStreamEvent {
    data object Connecting : MjpegStreamEvent
    data class Frame(val bitmap: ImageBitmap) : MjpegStreamEvent
    data class Disconnected(val attempt: Int, val retryInMs: Long, val reason: String) : MjpegStreamEvent
}

class MjpegStreamClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4_000L, TimeUnit.MILLISECONDS)
        .readTimeout(6_000L, TimeUnit.MILLISECONDS)
        .build()
) {
    fun streamFrames(baseUrlProvider: () -> String): Flow<MjpegStreamEvent> = flow {
        var attempt = 0
        val framer = JpegStreamFramer()

        while (currentCoroutineContext().isActive) {
            emit(MjpegStreamEvent.Connecting)
            var failureReason = "Stream closed"

            try {
                val request = Request.Builder()
                    .url(baseUrlProvider())
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        failureReason = "HTTP ${response.code}"
                    } else {
                        val stream = response.body?.byteStream()
                        if (stream == null) {
                            failureReason = "Empty response body"
                        } else {
                            framer.reset()
                            val chunk = ByteArray(16_384)
                            var gotFirstFrame = false
                            while (currentCoroutineContext().isActive) {
                                val read = stream.read(chunk)
                                if (read < 0) break
                                if (read == 0) continue
                                val jpegs = framer.feed(chunk, read)
                                for (jpeg in jpegs) {
                                    val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                                    if (bmp != null) {
                                        if (!gotFirstFrame) {
                                            gotFirstFrame = true
                                            attempt = 0
                                        }
                                        emit(MjpegStreamEvent.Frame(bmp.asImageBitmap()))
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                failureReason = t.message ?: t::class.java.simpleName
            }

            if (!currentCoroutineContext().isActive) break
            val delayMs = MjpegBackoff.computeDelayMs(attempt)
            emit(MjpegStreamEvent.Disconnected(attempt = attempt + 1, retryInMs = delayMs, reason = failureReason))
            attempt += 1
            delay(delayMs)
        }
    }.flowOn(Dispatchers.IO)
}
