package app.fieldwatch.data

import java.net.HttpURLConnection
import kotlinx.coroutines.CancellationException

/**
 * HTTPS GET of the stock signature pack on GitHub. No account.
 * Phones on 1.1.44 still fetch dist/fieldwatch-signatures-v2.json.
 * This build fetches the RSI file. That file is not on GitHub until it is pushed.
 */
object CatalogRemote {
    const val STOCK_PACK_URL =
        "https://raw.githubusercontent.com/OffGridPete/Fieldwatch/main/dist/fieldwatch-signatures.rsi"

    enum class Reason {
        REACH, TRUST, SERVER, LARGE, EMPTY, OTHER,
    }

    sealed class Fetch {
        data class Ok(val text: String) : Fetch()
        data class Failed(val reason: Reason, val code: Int = 0) : Fetch()
    }

    fun failReason(error: Exception): Reason = Http.failReason(error, Reason.REACH, Reason.TRUST, Reason.OTHER)

    fun fetch(
        url: String = STOCK_PACK_URL,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000,
        userAgent: String = "Fieldwatch",
        maxBytes: Int = ImportFiles.SIGNATURE_MAX,
    ): Fetch {
        if (url.isBlank()) return Fetch.Failed(Reason.OTHER)
        var connection: HttpURLConnection? = null
        try {
            val opened = Http.open(
                url,
                connectTimeoutMs,
                readTimeoutMs,
                mapOf("Accept" to "application/json", "User-Agent" to userAgent),
            )
            connection = opened
            val code = opened.responseCode
            if (code !in 200..299) return Fetch.Failed(Reason.SERVER, code)
            val bytes = opened.inputStream.use { Http.readCapped(it, maxBytes) }
                ?: return Fetch.Failed(Reason.LARGE)
            if (bytes.isEmpty()) return Fetch.Failed(Reason.EMPTY)
            return Fetch.Ok(String(bytes, Charsets.UTF_8))
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            return Fetch.Failed(failReason(error))
        } finally {
            connection?.disconnect()
        }
    }
}
