package com.gyanoba.prcomments.github

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/** Where to talk to. Derived from the configured host unless the URLs are overridden. */
data class GitHubEndpoint(
    val host: String,
    val restBaseUrl: String,
    val graphQlUrl: String,
) {
    companion object {
        const val DOT_COM: String = "github.com"

        fun defaultRestBaseUrl(host: String): String =
            if (host.equals(DOT_COM, ignoreCase = true)) "https://api.github.com" else "https://$host/api/v3"

        fun defaultGraphQlUrl(host: String): String =
            if (host.equals(DOT_COM, ignoreCase = true)) "https://api.github.com/graphql" else "https://$host/api/graphql"

        fun of(host: String, restBaseUrl: String?, graphQlUrl: String?): GitHubEndpoint {
            val normalizedHost = host.trim().removeSuffix("/").ifEmpty { DOT_COM }
            return GitHubEndpoint(
                host = normalizedHost,
                restBaseUrl = restBaseUrl?.trim()?.removeSuffix("/")?.ifEmpty { null }
                    ?: defaultRestBaseUrl(normalizedHost),
                graphQlUrl = graphQlUrl?.trim()?.ifEmpty { null } ?: defaultGraphQlUrl(normalizedHost),
            )
        }
    }
}

/**
 * Thin HTTP layer over the JDK client. Every call suspends on [Dispatchers.IO] — nothing here may
 * ever be reached from the EDT (guardrail §14.1).
 */
class GitHubClient(
    private val endpointProvider: () -> GitHubEndpoint,
    private val tokenProvider: () -> String?,
    private val userAgent: String = DEFAULT_USER_AGENT,
) {
    private val log = thisLogger()

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            // Redirects are followed manually in `send()` so we can reject any hop to a
            // non-HTTPS URL before the Authorization header would be sent to it.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    val endpoint: GitHubEndpoint get() = endpointProvider()

    /** Runs a GraphQL document and returns the `data` payload, failing loudly on the `errors` array. */
    suspend inline fun <reified T> graphQl(query: String, variables: JsonObject): T {
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }
        val raw = postJson(endpoint.graphQlUrl, body.toString())
        val parsed = try {
            JSON.decodeFromString<GraphQlResponse<T>>(raw)
        } catch (e: Exception) {
            throw GitHubError.Unknown(200, "Malformed GraphQL response: ${e.message}")
        }
        if (parsed.errors.isNotEmpty()) {
            throw GitHubError.GraphQl(parsed.errors.map { it.message })
        }
        return parsed.data ?: throw GitHubError.GraphQl(listOf("GraphQL response had no data"))
    }

    suspend inline fun <reified T> restGet(path: String): T =
        JSON.decodeFromString(getRaw(restUrl(path)))

    suspend inline fun <reified T> restPost(path: String, body: JsonObject): T =
        JSON.decodeFromString(postJson(restUrl(path), body.toString()))

    fun restUrl(path: String): String = endpoint.restBaseUrl + (if (path.startsWith("/")) path else "/$path")

    // -- transport -----------------------------------------------------------------------------

    suspend fun getRaw(url: String): String = send(requestBuilder(url).GET().build())

    suspend fun postJson(url: String, jsonBody: String): String = send(
        requestBuilder(url)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build()
    )

    private fun requestBuilder(url: String): HttpRequest.Builder {
        val token = tokenProvider()?.takeIf { it.isNotBlank() } ?: throw GitHubError.NoToken()
        return HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", userAgent)
    }

    private suspend fun send(request: HttpRequest, redirectsLeft: Int = MAX_REDIRECTS): String =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val response = try {
                http.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: ProcessCanceledException) {
                throw e // guardrail §14.8 — never swallowed
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw GitHubError.Network(e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw GitHubError.Network(e)
            }
            currentCoroutineContext().ensureActive()
            val status = response.statusCode()
            if (status in 200..299) return@withContext response.body()

            if (status in 300..399) {
                val location = response.headers().firstValue("location").orElse(null)
                    ?: throw GitHubError.Network(IOException("Redirect ($status) with no Location header"))
                if (redirectsLeft <= 0) {
                    throw GitHubError.Network(IOException("Too many redirects"))
                }
                val target = try {
                    request.uri().resolve(URI.create(location))
                } catch (e: IllegalArgumentException) {
                    throw GitHubError.Network(IOException("Redirect to invalid URL rejected", e))
                }
                if (!target.scheme.equals("https", ignoreCase = true)) {
                    throw GitHubError.Network(
                        IOException("Redirect to non-HTTPS URL rejected: ${target.scheme}")
                    )
                }
                return@withContext send(redirectedRequest(request, target), redirectsLeft - 1)
            }

            // Bodies can contain private code; keep them out of INFO logs (guardrail §14.6).
            log.debug("GitHub ${request.method()} ${request.uri().path} -> $status")
            throw mapError(status, response)
        }

    /** Rebuilds [original] against [target], preserving method, headers and body. */
    private fun redirectedRequest(original: HttpRequest, target: URI): HttpRequest {
        val builder = HttpRequest.newBuilder(target)
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
        original.headers().map().forEach { (name, values) ->
            values.forEach { value -> builder.header(name, value) }
        }
        builder.method(original.method(), original.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()))
        return builder.build()
    }

    private fun mapError(status: Int, response: HttpResponse<String>): GitHubError = when (status) {
        401 -> GitHubError.Unauthorized()
        403, 429 -> GitHubError.Forbidden(rateLimitResetAt(response))
        404 -> GitHubError.NotFound()
        else -> GitHubError.Unknown(status, sanitizeErrorBody(response.body().orEmpty()))
    }

    /** Strips markup and collapses whitespace so an error body shown to the user can't smuggle HTML. */
    private fun sanitizeErrorBody(body: String): String = body
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(200)

    private fun rateLimitResetAt(response: HttpResponse<String>): Instant? {
        val remaining = response.headers().firstValue("x-ratelimit-remaining").orElse(null)
        val retryAfter = response.headers().firstValue("retry-after").orElse(null)?.toLongOrNull()
        if (retryAfter != null) return Instant.now().plusSeconds(retryAfter)
        if (remaining != "0") return null
        val reset = response.headers().firstValue("x-ratelimit-reset").orElse(null)?.toLongOrNull() ?: return null
        return Instant.ofEpochSecond(reset)
    }

    companion object {
        const val CONNECT_TIMEOUT_SECONDS: Long = 15
        const val REQUEST_TIMEOUT_SECONDS: Long = 30
        const val DEFAULT_USER_AGENT: String = "pr-comments-plugin"
        const val MAX_REDIRECTS: Int = 5

        @PublishedApi
        internal val JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        fun jsonVars(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
            pairs.forEach { (key, value) ->
                when (value) {
                    null -> put(key, kotlinx.serialization.json.JsonNull)
                    is String -> put(key, value)
                    is Int -> put(key, value)
                    is Long -> put(key, value)
                    is Boolean -> put(key, value)
                    else -> put(key, JsonPrimitive(value.toString()))
                }
            }
        }
    }
}
