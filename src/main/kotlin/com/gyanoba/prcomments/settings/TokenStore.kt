package com.gyanoba.prcomments.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * The PAT lives in [PasswordSafe] and nowhere else — never in `.idea/` or any XML on disk (§5.1).
 * Keyed by host so github.com and an Enterprise instance can each have their own token.
 */
object TokenStore {

    private const val SUBSYSTEM = "PR Comments"

    private fun attributes(host: String) = CredentialAttributes(generateServiceName(SUBSYSTEM, host))

    fun get(host: String): String? =
        PasswordSafe.instance.getPassword(attributes(host))?.takeIf { it.isNotBlank() }

    fun set(host: String, token: String?) {
        val attributes = attributes(host)
        if (token.isNullOrBlank()) {
            PasswordSafe.instance.set(attributes, null)
        } else {
            PasswordSafe.instance.set(attributes, Credentials(host, token))
        }
    }
}
