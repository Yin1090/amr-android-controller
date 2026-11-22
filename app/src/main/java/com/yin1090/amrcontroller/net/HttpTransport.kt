package com.yin1090.amrcontroller.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class HttpResponse(
    val code: Int,
    val body: String
)

interface HttpTransport {
    suspend fun get(
        url: String,
        timeoutMs: Int,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse
}

class OkHttpTransport(
    private val baseClient: OkHttpClient = OkHttpClient()
) : HttpTransport {
    override suspend fun get(
        url: String,
        timeoutMs: Int,
        headers: Map<String, String>
    ): HttpResponse = withContext(Dispatchers.IO) {
        val timeout = timeoutMs.coerceAtLeast(100).toLong()
        val client = baseClient.newBuilder()
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .callTimeout(timeout, TimeUnit.MILLISECONDS)
            .build()

        val requestBuilder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        val call = client.newCall(requestBuilder.build())

        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                call.cancel()
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { res ->
                        val bodyString = res.body?.string().orEmpty()
                        if (continuation.isActive) {
                            continuation.resume(HttpResponse(code = res.code, body = bodyString))
                        }
                    }
                }
            })
        }
    }
}
