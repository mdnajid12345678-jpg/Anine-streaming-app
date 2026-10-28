package com.lagradost.cloudstream3.importsystem

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Client for the `import-sync` Supabase Edge Function.
 *
 * Security model (why no service role key exists here):
 *  - the APK only holds the public project url and the publishable key, both injected at build
 *    time by Gradle from the CI environment; RLS blocks anonymous writes on every table
 *  - privileged writes happen inside the Edge Function, which owns the service role key as a
 *    *function secret* stored in Supabase, never in this repository or in the APK
 *  - the caller key in the header is entered by the user on device; it authorises this device
 *    and is never bundled at build time
 *
 * Retries are limited and back off; a cancellation always propagates.
 */
class SupabaseImportClient(
    private val functionUrl: String,
    private val publishableKey: String,
    private val callerKey: String,
) {
    data class Configuration(val configured: Boolean, val reason: String?)

    fun configuration(): Configuration = when {
        functionUrl.isBlank() ->
            Configuration(false, "SUPABASE_URL was not injected at build time")

        publishableKey.isBlank() ->
            Configuration(false, "SUPABASE_PUBLISHABLE_KEY was not injected at build time")

        !functionUrl.startsWith("https://") ->
            Configuration(false, "Function url must be https")

        else -> Configuration(true, null)
    }

    /**
     * Uploads one finished preview. Throws with an accurate message when the function is not
     * configured, rejects the caller, or the network fails; it never fabricates success.
     */
    suspend fun upload(payload: ImportUploadPayload): ImportUploadResult {
        val config = configuration()
        if (!config.configured) {
            throw ImportUploadException(config.reason ?: "Supabase is not configured")
        }

        val body = payload.toJson()
            .toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

        var lastError: String? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val response = app.post(
                    functionUrl,
                    headers = mapOf(
                        "content-type" to "application/json",
                        "apikey" to publishableKey,
                        // Public bearer; the function validates it and its own caller key.
                        "authorization" to "Bearer $publishableKey",
                        "x-import-caller-key" to callerKey,
                        "x-client" to "cloudstream-native-import",
                    ),
                    requestBody = body,
                )

                val text = response.text
                if (response.code !in 200..299) {
                    lastError = "Function responded with HTTP ${response.code}: ${snippet(text)}"
                    // 4xx are not retried: the caller key or payload will not change by waiting.
                    if (response.code in 400..499 && response.code != 429) {
                        throw ImportUploadException(lastError!!)
                    }
                } else {
                    val parsed = tryParseJson<ImportUploadResult>(text)
                        ?: throw ImportUploadException(
                            "Function returned a response that could not be parsed: ${snippet(text)}",
                        )
                    return parsed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImportUploadException) {
                throw e
            } catch (t: Throwable) {
                lastError = t.message?.take(300) ?: t::class.simpleName
            }
            if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MS * (attempt + 1))
        }

        throw ImportUploadException(lastError ?: "Upload failed without a reason")
    }

    private fun snippet(text: String?): String {
        if (text.isNullOrBlank()) return "no body"
        return text.replace(Regex("""\s+"""), " ").take(300)
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1_500L
    }
}

/** Raised when the Edge Function rejects the payload or the caller. Never contains a secret. */
class ImportUploadException(message: String) : Exception(message)
