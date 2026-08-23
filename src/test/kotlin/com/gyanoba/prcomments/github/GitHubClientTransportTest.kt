package com.gyanoba.prcomments.github

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The transport's job around the PAT: send it only over a channel that protects it, and never
 * replay it to an origin other than the configured one. Servers are bound to loopback, which is the
 * one place plain http is allowed.
 */
class GitHubClientTransportTest {

    private val token = "ghp_secret_value"

    @Test
    fun `plain http endpoint is refused before the token is read`() {
        var tokenRead = false
        val client = clientFor("http://github.example.com/api/v3") { tokenRead = true; token }

        assertThrows<GitHubError.InsecureEndpoint> {
            runBlocking { client.getRaw("http://github.example.com/api/v3/user") }
        }
        assertFalse(tokenRead, "the token must not even be read for an endpoint it cannot be sent to")
    }

    @Test
    fun `a URL with no host is refused`() {
        val client = clientFor("https://github.example.com") { token }

        assertThrows<GitHubError.InvalidEndpoint> {
            runBlocking { client.getRaw("file:///etc/passwd") }
        }
    }

    @Test
    fun `a same-origin redirect is followed and carries the token`() = withServer { server, base ->
        val seen = CopyOnWriteArrayList<String>()
        server.createContext("/moved") { exchange ->
            seen += exchange.authorization()
            exchange.responseHeaders.add("Location", "$base/final")
            exchange.respond(301, "")
        }
        server.createContext("/final") { exchange ->
            seen += exchange.authorization()
            exchange.respond(200, """{"ok":true}""")
        }

        val client = clientFor(base) { token }
        val body = runBlocking { client.getRaw("$base/moved") }

        assertEquals("""{"ok":true}""", body)
        assertEquals(listOf("Bearer $token", "Bearer $token"), seen)
    }

    @Test
    fun `a redirect to another origin is refused and the token is not replayed`() =
        withServer { attacker, attackerBase ->
            val attackerSaw = CopyOnWriteArrayList<String>()
            attacker.createContext("/") { exchange ->
                attackerSaw += exchange.authorization()
                exchange.respond(200, "{}")
            }

            withServer { origin, originBase ->
                origin.createContext("/user") { exchange ->
                    exchange.responseHeaders.add("Location", "$attackerBase/steal")
                    exchange.respond(302, "")
                }

                val client = clientFor(originBase) { token }
                val error = assertThrows<GitHubError.CrossOriginRedirect> {
                    runBlocking { client.getRaw("$originBase/user") }
                }

                assertTrue(error.origin.contains(attacker.address.port.toString()), error.origin)
                assertTrue(attackerSaw.isEmpty(), "the redirect target must never see the request: $attackerSaw")
            }
        }

    @Test
    fun `a same-host scheme downgrade is refused`() = withServer { server, base ->
        val plainTarget = base.replace("http://127.0.0.1", "https://127.0.0.1")
        server.createContext("/user") { exchange ->
            // Same host and port, https instead of http: still a different origin.
            exchange.responseHeaders.add("Location", "$plainTarget/user")
            exchange.respond(302, "")
        }

        val client = clientFor(base) { token }
        assertThrows<GitHubError.CrossOriginRedirect> {
            runBlocking { client.getRaw("$base/user") }
        }
    }

    @Test
    fun `origins compare on scheme host and effective port`() {
        val https = java.net.URI.create("https://api.github.com/graphql")
        assertTrue(GitHubClient.isSameOrigin(https, java.net.URI.create("https://api.github.com:443/other")))
        assertTrue(GitHubClient.isSameOrigin(https, java.net.URI.create("https://API.GitHub.com/other")))
        assertFalse(GitHubClient.isSameOrigin(https, java.net.URI.create("https://api.github.com.evil.tld/x")))
        assertFalse(GitHubClient.isSameOrigin(https, java.net.URI.create("https://api.github.com:8443/x")))
        assertFalse(GitHubClient.isSameOrigin(https, java.net.URI.create("http://api.github.com/x")))
        assertFalse(GitHubClient.isSameOrigin(https, java.net.URI.create("mailto:x@example.com")))
    }

    @Test
    fun `loopback is recognised in every spelling that reaches the client`() {
        listOf("localhost", "LOCALHOST", "127.0.0.1", "127.13.2.9", "[::1]", "::1").forEach {
            assertTrue(GitHubClient.isLoopback(it), it)
        }
        // `127.example.com` is a domain someone else can register, not a local address.
        listOf("github.com", "127.example.com", "127.0.0.1.evil.tld", "0.0.0.0", "10.0.0.1", "1270.0.1").forEach {
            assertFalse(GitHubClient.isLoopback(it), it)
        }
    }

    // -- helpers -----------------------------------------------------------------------------------

    private fun clientFor(base: String, tokenProvider: () -> String?) = GitHubClient(
        endpointProvider = { GitHubEndpoint("127.0.0.1", base, "$base/graphql") },
        tokenProvider = tokenProvider,
    )

    private fun HttpExchange.authorization(): String =
        requestHeaders.getFirst("Authorization").orEmpty()

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) }
        close()
    }

    private fun withServer(block: (HttpServer, String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.start()
        try {
            block(server, "http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }
}
