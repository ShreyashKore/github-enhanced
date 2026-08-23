package com.gyanoba.prcomments.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import java.util.Locale

/**
 * The PAT lives in [PasswordSafe] and nowhere else — never in `.idea/` or any XML on disk (§5.1).
 * Keyed by host so github.com and an Enterprise instance can each keep their own token; the key is
 * normalized so `GitHub.com`, `github.com ` and `github.com/` all resolve to the same entry instead
 * of orphaning a secret under a near-miss key.
 *
 * Reads can block (OS keychain, KeePass master password): call from a background thread, never the
 * EDT.
 */
object TokenStore {

    private const val SUBSYSTEM = "PR Comments"

    private fun key(host: String): String =
        host.trim().removeSuffix("/").lowercase(Locale.ROOT)

    private fun attributes(host: String) = CredentialAttributes(generateServiceName(SUBSYSTEM, key(host)))

    fun get(host: String): String? {
        if (key(host).isEmpty()) return null
        return PasswordSafe.instance.getPassword(attributes(host))?.takeIf { it.isNotBlank() }
    }

    fun set(host: String, token: String?) {
        val normalized = key(host)
        if (normalized.isEmpty()) return
        val attributes = attributes(host)
        if (token.isNullOrBlank()) {
            PasswordSafe.instance.set(attributes, null)
        } else {
            PasswordSafe.instance.set(attributes, Credentials(normalized, token))
        }
    }
}
