package com.moath.tvhome.aerial

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream

/**
 * A small OkHttp-backed [DataSource] (media3-datasource-okhttp isn't a dependency of this app),
 * so video requests go through [AerialNetwork]'s Apple-aware TLS client. Supports byte ranges,
 * which ExoPlayer needs because Apple's .mov files keep the moov atom at the end.
 */
@UnstableApi
internal class AerialHttpDataSource(private val client: OkHttpClient) : BaseDataSource(true) {

    class Factory(private val client: OkHttpClient) : DataSource.Factory {
        override fun createDataSource(): DataSource = AerialHttpDataSource(client)
    }

    private var dataSpec: DataSpec? = null
    private var call: Call? = null
    private var response: Response? = null
    private var stream: InputStream? = null
    private var bytesToRead = 0L
    private var bytesRead = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        bytesRead = 0
        bytesToRead = 0
        transferInitializing(dataSpec)

        val resp = execute(dataSpec)
        response = resp
        val code = resp.code
        if (code !in 200..299) {
            val headers = resp.headers.toMultimap()
            val body = runCatching { resp.body?.bytes() }.getOrNull() ?: ByteArray(0)
            closeConnection()
            if (code == 416 && dataSpec.position == totalLength(resp.header("Content-Range"))) {
                opened = true
                transferStarted(dataSpec)
                return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else 0
            }
            throw HttpDataSource.InvalidResponseCodeException(code, resp.message, null, headers, dataSpec, body)
        }

        val body = resp.body ?: run {
            closeConnection()
            throw HttpDataSource.HttpDataSourceException(
                dataSpec, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }
        stream = body.byteStream()
        // A server that ignores Range answers 200 with the whole file: skip to the position.
        val skip = if (code == 200 && dataSpec.position != 0L) dataSpec.position else 0L
        bytesToRead = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            body.contentLength() != -1L -> body.contentLength() - skip
            else -> C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        try {
            skipFully(skip, dataSpec)
        } catch (e: IOException) {
            closeConnection()
            throw e
        }
        return bytesToRead
    }

    private fun execute(dataSpec: DataSpec): Response {
        val builder = Request.Builder().url(dataSpec.uri.toString()).header("Accept-Encoding", "identity")
        if (dataSpec.position != 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) {
            val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) "" else (dataSpec.position + dataSpec.length - 1).toString()
            builder.header("Range", "bytes=${dataSpec.position}-$end")
        }
        val newCall = client.newCall(builder.build())
        call = newCall
        return try {
            newCall.execute()
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(e, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            val remaining = bytesToRead - bytesRead
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            return readInto(buffer, offset, minOf(length.toLong(), remaining).toInt())
        }
        return readInto(buffer, offset, length)
    }

    private fun readInto(buffer: ByteArray, offset: Int, length: Int): Int {
        val source = stream ?: return C.RESULT_END_OF_INPUT
        val n = try {
            source.read(buffer, offset, length)
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e, requireNotNull(dataSpec), HttpDataSource.HttpDataSourceException.TYPE_READ,
            )
        }
        if (n == -1) return C.RESULT_END_OF_INPUT
        bytesRead += n
        bytesTransferred(n)
        return n
    }

    private fun skipFully(count: Long, spec: DataSpec) {
        val scratch = ByteArray(16 * 1024)
        var left = count
        val source = stream ?: return
        while (left > 0) {
            val n = source.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (n < 0) {
                throw HttpDataSource.HttpDataSourceException(
                    spec, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, HttpDataSource.HttpDataSourceException.TYPE_OPEN,
                )
            }
            left -= n
            bytesTransferred(n)
        }
    }

    override fun getUri(): Uri? = response?.request?.url?.toString()?.let(Uri::parse) ?: dataSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = response?.headers?.toMultimap().orEmpty()

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        closeConnection()
    }

    private fun closeConnection() {
        runCatching { stream?.close() }
        runCatching { response?.close() }
        stream = null
        response = null
        call = null
    }

    private fun totalLength(contentRange: String?): Long =
        contentRange?.substringAfterLast('/', "")?.toLongOrNull() ?: -1L
}
