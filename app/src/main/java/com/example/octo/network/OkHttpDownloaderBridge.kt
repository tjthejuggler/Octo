package com.example.octo.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/**
 * Bridges NewPipeExtractor's blocking [Downloader] onto our shared OkHttp client.
 *
 * One instance per app; hand it to [org.schabi.newpipe.extractor.NewPipe.init] exactly once.
 */
class OkHttpDownloaderBridge(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), request.dataToSend()?.toRequestBody())

        request.headers().forEach { (name, values) ->
            values.forEach { value -> builder.header(name, value) }
        }

        val response = client.newCall(builder.build()).execute()
        val body = response.body
        val responseBody = body?.string().orEmpty()

        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha challenge (HTTP 429) url=" + request.url(), request.url())
        }

        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            responseBody,
            response.request.url.toString(),
            // Latest-URL tracking is irrelevant for us; keep requested URL.
        )
    }

    private fun ByteArray.toRequestBody(): okhttp3.RequestBody =
        okhttp3.RequestBody.create(null, this)

    companion object {
        /** NewPipeExtractor's own calls are short page fetches — dedicated short-timeout client. */
        fun newExtractorClient(shared: OkHttpClient): OkHttpClient = shared.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
