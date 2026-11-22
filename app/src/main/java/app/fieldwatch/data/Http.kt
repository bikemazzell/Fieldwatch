package app.fieldwatch.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

object Http {
    fun open(
        url: String,
        connectMs: Int,
        readMs: Int,
        headers: Map<String, String>,
        followRedirects: Boolean = true,
        useCaches: Boolean = true,
        method: String = "GET",
    ): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = followRedirects
            this.useCaches = useCaches
            connectTimeout = connectMs
            readTimeout = readMs
            requestMethod = method
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }

    fun readCapped(input: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            total += n
            if (total > maxBytes) return null
            out.write(buf, 0, n)
        }
    }

    fun <R> failReason(error: Exception, reach: R, trust: R, other: R): R = when (error) {
        is SSLException -> trust
        is UnknownHostException, is SocketTimeoutException, is SocketException -> reach
        else -> other
    }
}
