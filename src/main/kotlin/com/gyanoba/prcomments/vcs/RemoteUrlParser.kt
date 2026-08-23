package com.gyanoba.prcomments.vcs

import com.gyanoba.prcomments.model.GitHubRepoCoordinates

/**
 * Parses the git remote URL forms GitHub hands out, plus the SSH-alias form people write in
 * `~/.ssh/config` (`git@github-work:owner/name.git`). Pure — unit tested without an IDE fixture.
 */
object RemoteUrlParser {

    /** `scheme://[user@]host[:port]/path` */
    private val SCHEME_URL = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*://(?:([^@/]+)@)?([^/:]+)(?::\d+)?(/.*)$""")

    /** `[user@]host:path` — the scp-like syntax, with no scheme and no leading slash on the path. */
    private val SCP_URL = Regex("""^(?:([^@/]+)@)?([^/:]+):(.+)$""")

    fun parse(rawUrl: String): GitHubRepoCoordinates? {
        val url = rawUrl.trim().trimEnd('/')
        if (url.isEmpty()) return null

        val (host, path) = when {
            SCHEME_URL.matches(url) -> {
                val m = SCHEME_URL.matchEntire(url)!!
                m.groupValues[2] to m.groupValues[3]
            }

            SCP_URL.matches(url) -> {
                val m = SCP_URL.matchEntire(url)!!
                m.groupValues[2] to m.groupValues[3]
            }

            else -> return null
        }
        if (host.isEmpty()) return null

        val segments = path.removePrefix("/").removeSuffix("/").split('/').filter { it.isNotEmpty() }
        if (segments.size < 2) return null
        val owner = segments[segments.size - 2]
        val name = segments.last().removeSuffix(".git")
        if (owner.isEmpty() || name.isEmpty()) return null

        return GitHubRepoCoordinates(host = host, owner = owner, name = name)
    }

    /**
     * True when a parsed remote host plausibly refers to [configuredHost]. Exact matches win;
     * beyond that we accept SSH aliases that keep the first label (`github`, `github-work`),
     * since the alias never survives into the URL the user actually configured.
     */
    fun hostMatches(parsedHost: String, configuredHost: String): Boolean {
        if (parsedHost.equals(configuredHost, ignoreCase = true)) return true
        val label = configuredHost.substringBefore('.')
        if (label.isEmpty()) return false
        return parsedHost.equals(label, ignoreCase = true) ||
            parsedHost.startsWith("$label-", ignoreCase = true) ||
            parsedHost.startsWith("$label.", ignoreCase = true)
    }
}
