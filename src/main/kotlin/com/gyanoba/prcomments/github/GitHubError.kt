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
