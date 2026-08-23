package com.gyanoba.prcomments.github

import com.gyanoba.prcomments.PrCommentsBundle
import com.intellij.util.text.DateFormatUtil
import java.time.Instant

/** Every failure the GitHub layer can produce, mapped to a message the user can act on. */
sealed class GitHubError(val displayMessage: String, cause: Throwable? = null) :
    Exception(displayMessage, cause) {

    class NoToken : GitHubError(PrCommentsBundle.message("error.noToken"))

    class Unauthorized : GitHubError(PrCommentsBundle.message("error.unauthorized"))

    class Forbidden(val rateLimitResetAt: Instant?) : GitHubError(
        if (rateLimitResetAt != null) {
            PrCommentsBundle.message("error.rateLimited", DateFormatUtil.formatTime(rateLimitResetAt.toEpochMilli()))
        } else {
            PrCommentsBundle.message("error.forbidden")
        }
    ) {
        val isRateLimit: Boolean get() = rateLimitResetAt != null
    }

    class NotFound : GitHubError(PrCommentsBundle.message("error.notFound"))

    /** The configured endpoint is not a URL the token may be sent to (plain http, non-loopback). */
    class InsecureEndpoint(val host: String) :
        GitHubError(PrCommentsBundle.message("error.insecureEndpoint", host))

    class InvalidEndpoint(url: String, cause: Throwable? = null) :
        GitHubError(PrCommentsBundle.message("error.invalidEndpoint", url.take(120)), cause)

    /** A 3xx pointed somewhere other than the configured origin; the token was not replayed to it. */
    class CrossOriginRedirect(val origin: String) :
        GitHubError(PrCommentsBundle.message("error.crossOriginRedirect", origin.take(120)))

    class Network(cause: Throwable) :
        GitHubError(PrCommentsBundle.message("error.network", cause.message ?: cause::class.java.simpleName), cause)

    class GraphQl(val errors: List<String>) :
        GitHubError(
            PrCommentsBundle.message(
                "error.graphQl",
                errors.joinToString("; ").replace(Regex("[<>\"']"), "").take(200),
            )
        )

    class Unknown(val status: Int, val body: String) :
        GitHubError(PrCommentsBundle.message("error.unknown", status, body.take(300)))
}
