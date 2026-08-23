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
            // Redirects are followed manually in `send()`: the JDK would carry the Authorization
            // header along to whatever host the Location points at, so every hop is checked to be
            // same-origin before the token is replayed to it.
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
        // Checked before the token is read, so a bad endpoint can never put the PAT on the wire.
        val uri = checkedUri(url)
        val token = tokenProvider()?.takeIf { it.isNotBlank() } ?: throw GitHubError.NoToken()
        return HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", userAgent)
    }

    /**
     * Parses [url] and rejects anything that would send the PAT in the clear. Loopback is the one
     * exception, so a local mock server can still be pointed at during development.
     */
    private fun checkedUri(url: String): URI {
        val uri = try {
            URI.create(url)
        } catch (e: IllegalArgumentException) {
            throw GitHubError.InvalidEndpoint(url, e)
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if (host.isNullOrEmpty() || (scheme != "https" && scheme != "http")) {
            throw GitHubError.InvalidEndpoint(url)
        }
        if (scheme == "http" && !isLoopback(host)) throw GitHubError.InsecureEndpoint(host)
        return uri
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
                // The redirected request replays every header, Authorization included. Anything but
                // the exact same origin — a scheme downgrade, another host, another port — would
                // hand the PAT to a party that is not the configured GitHub, so it is refused
                // rather than followed. Same-origin hops (a renamed repo, a trailing slash) still
                // work.
                if (!isSameOrigin(request.uri(), target)) {
                    throw GitHubError.CrossOriginRedirect(originOf(target))
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
        /** Same scheme, same host, same effective port — the bar a hop must clear to keep the token. */
        internal fun isSameOrigin(from: URI, to: URI): Boolean {
            val fromHost = from.host ?: return false
            val toHost = to.host ?: return false
            return from.scheme.equals(to.scheme, ignoreCase = true) &&
                fromHost.equals(toHost, ignoreCase = true) &&
                effectivePort(from) == effectivePort(to)
        }

        private fun effectivePort(uri: URI): Int = when {
            uri.port != -1 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            uri.scheme.equals("http", ignoreCase = true) -> 80
            else -> -1
        }

        private fun originOf(uri: URI): String =
            "${uri.scheme.orEmpty()}://${uri.host.orEmpty()}" + if (uri.port != -1) ":${uri.port}" else ""

        private val IPV4_LITERAL = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

        /**
         * Loopback and nothing else. Matched on literals only — a *name* is never resolved here,
         * both to keep this off the network and because `127.example.com` is a registrable domain
         * someone else can own, not a local address.
         */
        internal fun isLoopback(host: String): Boolean {
            val bare = host.removeSurrounding("[", "]")
            if (bare.equals("localhost", ignoreCase = true)) return true
            if (bare == "::1" || bare == "0:0:0:0:0:0:0:1") return true
            val octets = IPV4_LITERAL.matchEntire(bare)?.groupValues?.drop(1)?.map { it.toInt() } ?: return false
            return octets.all { it in 0..255 } && octets[0] == 127
        }

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
