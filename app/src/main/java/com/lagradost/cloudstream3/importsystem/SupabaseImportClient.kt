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

    /** Reads previously stored normalized titles for these providers before starting a scan. */
    suspend fun existingProviderTitles(providerIds: Set<String>): Map<String, Set<String>> {
        val config = configuration()
        if (!config.configured) {
            throw ImportUploadException(config.reason ?: "Supabase is not configured")
        }
        if (callerKey.isBlank()) {
            throw ImportUploadException("Import Edge Function caller key is not configured")
        }
        if (providerIds.isEmpty()) return emptyMap()

        val body = ExistingProviderTitlesRequest(
            action = "existing_provider_titles",
            providerIds = providerIds.sorted(),
        )
            .toJson()
            .toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        var lastError: String? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val response = app.post(
                    functionUrl,
                    headers = requestHeaders(),
                    requestBody = body,
                )
                val text = response.text
                if (response.code !in 200..299) {
                    lastError = "Function responded with HTTP ${response.code}: ${snippet(text)}"
                    if (response.code in 400..499 && response.code != 429) {
                        throw ImportUploadException(lastError!!)
                    }
                } else {
                    val parsed = tryParseJson<ExistingProviderTitles>(text)
                        ?: throw ImportUploadException(
                            "Function returned an existing-title response that could not be parsed: ${snippet(text)}",
                        )
                    return parsed.existingProviderTitles.mapValues { (_, titles) -> titles.toSet() }
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
        throw ImportUploadException(lastError ?: "Existing-title lookup failed without a reason")
    }

    /** Loads a page of already-saved content/provider URLs; it never returns unrelated titles. */
    suspend fun providerLinkRefreshTargets(
        providerId: String,
        offset: Int,
        limit: Int = LINK_REFRESH_PAGE_SIZE,
    ): ProviderLinkRefreshTargetsResponse = postAction(
        request = ProviderLinkRefreshTargetsRequest(
            action = "provider_link_refresh_targets",
            providerId = providerId,
            offset = offset,
            limit = limit,
        ),
        parse = { text -> tryParseJson<ProviderLinkRefreshTargetsResponse>(text) },
        operation = "provider link target lookup",
    )

    /** Replaces this provider's stored sources only where the client found fresh playable links. */
    suspend fun replaceProviderLinks(
        providerId: String,
        contents: List<ProviderLinkRefreshContent>,
    ): ReplaceProviderLinksResult = postAction(
        request = ReplaceProviderLinksRequest(
            action = "replace_provider_links",
            providerId = providerId,
            contents = contents,
        ),
        parse = { text -> tryParseJson<ReplaceProviderLinksResult>(text) },
        operation = "provider link replacement",
    )

    private suspend fun <T> postAction(
        request: Any,
        parse: (String) -> T?,
        operation: String,
    ): T {
        val config = configuration()
        if (!config.configured) {
            throw ImportUploadException(config.reason ?: "Supabase is not configured")
        }
        if (callerKey.isBlank()) {
            throw ImportUploadException("Import Edge Function caller key is not configured")
        }
        val body = request.toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        var lastError: String? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val response = app.post(
                    functionUrl,
                    headers = requestHeaders(),
                    requestBody = body,
                )
                val text = response.text
                if (response.code !in 200..299) {
                    lastError = "Function responded with HTTP ${response.code}: ${snippet(text)}"
                    if (response.code in 400..499 && response.code != 429) {
                        throw ImportUploadException(lastError!!)
                    }
                } else {
                    return parse(text) ?: throw ImportUploadException(
                        "Function returned an invalid $operation response: ${snippet(text)}",
                    )
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
        throw ImportUploadException(lastError ?: "$operation failed without a reason")
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
                    headers = requestHeaders(),
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

    private fun requestHeaders() = mapOf(
        "content-type" to "application/json",
        "apikey" to publishableKey,
        // Public bearer; the function validates it and its own caller key.
        "authorization" to "Bearer $publishableKey",
        "x-import-caller-key" to callerKey,
        "x-client" to "cloudstream-native-import",
    )

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1_500L
        const val LINK_REFRESH_PAGE_SIZE = 200
    }
}

/** Raised when the Edge Function rejects the payload or the caller. Never contains a secret. */
class ImportUploadException(message: String) : Exception(message)
