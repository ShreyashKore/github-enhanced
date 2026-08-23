package com.gyanoba.prcomments.settings

import com.gyanoba.prcomments.PrCommentsBundle
import com.gyanoba.prcomments.github.GitHubApi
import com.gyanoba.prcomments.github.GitHubClient
import com.gyanoba.prcomments.github.GitHubEndpoint
import com.gyanoba.prcomments.github.GitHubError
import com.gyanoba.prcomments.service.PrCommentsService
import com.gyanoba.prcomments.service.PrCommentsSettings
import com.intellij.openapi.application.EDT
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.ValidationInfoBuilder
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URISyntaxException
import javax.swing.JLabel

/**
 * Settings | Tools | PR Comments (§5.3). The token is bound to [TokenStore], never to the state
 * component, so it never reaches disk in plain text.
 */
class PrCommentsConfigurable(private val project: Project) :
    BoundConfigurable(PrCommentsBundle.message("settings.title")) {

    private val settings get() = PrCommentsSettings.getInstance(project)

    private var host: String = ""
    private var apiBaseUrl: String = ""
    private var graphQlUrl: String = ""
    private var token: String = ""
    private var autoRefresh: Boolean = true
    private var refreshInterval: Int = PrCommentsSettings.DEFAULT_REFRESH_SECONDS

    /**
     * The token exactly as it was read for the host in the form. [isModified] compares against this
     * instead of re-reading [TokenStore] — that read can block on the OS keychain and `isModified`
     * is polled on the EDT — and [apply] uses it to tell a token the user typed apart from one that
     * was merely loaded for a host they have since changed.
     */
    private var loadedToken: String = ""

    private val statusLabel = JLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    override fun createPanel(): DialogPanel {
        host = settings.githubHost
        apiBaseUrl = settings.apiBaseUrl
        graphQlUrl = settings.graphQlUrl
        loadedToken = TokenStore.get(host).orEmpty()
        token = loadedToken
        autoRefresh = settings.autoRefreshEnabled
        refreshInterval = settings.refreshIntervalSeconds

        return panel {
            group(PrCommentsBundle.message("settings.group.connection")) {
                row(PrCommentsBundle.message("settings.host")) {
                    textField()
                        .bindText({ host }, { host = it.trim() })
                        .comment(PrCommentsBundle.message("settings.host.comment"))
                }
                row(PrCommentsBundle.message("settings.apiBaseUrl")) {
                    textField()
                        .bindText({ apiBaseUrl }, { apiBaseUrl = it.trim() })
                        .validationOnInput { field -> requireHttps(this, field.text) }
                }
                row(PrCommentsBundle.message("settings.graphQlUrl")) {
                    textField()
                        .bindText({ graphQlUrl }, { graphQlUrl = it.trim() })
                        .comment(PrCommentsBundle.message("settings.url.comment"))
                        .validationOnInput { field -> requireHttps(this, field.text) }
                }
                row(PrCommentsBundle.message("settings.token")) {
                    passwordField()
                        .bindText({ token }, { token = it })
                        .comment(PrCommentsBundle.message("settings.token.comment"))
                }
                row {
                    button(PrCommentsBundle.message("settings.testConnection")) { testConnection() }
                    cell(statusLabel).align(Align.FILL)
                }.layout(RowLayout.PARENT_GRID)
            }

            group(PrCommentsBundle.message("settings.group.refresh")) {
                row {
                    checkBox(PrCommentsBundle.message("settings.autoRefresh"))
                        .bindSelected({ autoRefresh }, { autoRefresh = it })
                }
                row(PrCommentsBundle.message("settings.refreshInterval")) {
                    intTextField(PrCommentsSettings.MIN_REFRESH_SECONDS..PrCommentsSettings.MAX_REFRESH_SECONDS)
                        .bindIntText({ refreshInterval }, { refreshInterval = it })
                }
            }
        }
    }

    override fun isModified(): Boolean = super.isModified() ||
        host != settings.githubHost ||
        apiBaseUrl != settings.apiBaseUrl ||
        graphQlUrl != settings.graphQlUrl ||
        autoRefresh != settings.autoRefreshEnabled ||
        refreshInterval != settings.refreshIntervalSeconds ||
        token != loadedToken

    override fun apply() {
        super.apply()
        settings.githubHost = host
        settings.apiBaseUrl = apiBaseUrl
        settings.graphQlUrl = graphQlUrl
        settings.autoRefreshEnabled = autoRefresh
        settings.refreshIntervalSeconds = refreshInterval

        val newHost = settings.githubHost
        // Only a token the user actually typed is written, and only under the host now in the form.
        // An untouched field still holds the secret loaded for the *previous* host; re-saving that
        // under a host the user has just changed would silently re-target one instance's PAT at
        // another. Tokens belonging to other hosts are left in place — they are keyed per host on
        // purpose, so switching back finds the right one.
        if (token != loadedToken) TokenStore.set(newHost, token.ifBlank { null })

        loadedToken = TokenStore.get(newHost).orEmpty()
        if (token != loadedToken) {
            token = loadedToken
            reset() // push the token that belongs to the new host into the field
        }

        PrCommentsService.getInstance(project).refresh(force = true)
    }

    override fun reset() {
        super.reset()
        statusLabel.text = ""
    }

    override fun disposeUIResources() {
        // Nothing can scrub a String from the heap, but dropping the last reference keeps the PAT
        // from outliving the dialog in a retained configurable.
        token = ""
        loadedToken = ""
        super.disposeUIResources()
    }

    /**
     * Blocks rather than warns: the PAT is sent to this URL on every request, so plain http would
     * put it on the wire in the clear. Loopback stays allowed for local test servers.
     */
    private fun requireHttps(builder: ValidationInfoBuilder, value: String): ValidationInfo? {
        if (value.isBlank()) return null
        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            return builder.error(PrCommentsBundle.message("settings.url.https.error"))
        }
        val scheme = uri.scheme?.lowercase()
        val secure = scheme == "https" ||
            (scheme == "http" && uri.host?.let { GitHubClient.isLoopback(it) } == true)
        return if (secure) null else builder.error(PrCommentsBundle.message("settings.url.https.error"))
    }

    /** `GET /user` against the values currently in the form, not the persisted ones. */
    private fun testConnection() {
        val service = PrCommentsService.getInstance(project)
        val endpoint = GitHubEndpoint.of(host, apiBaseUrl, graphQlUrl)
        val enteredToken = token
        statusLabel.text = PrCommentsBundle.message("settings.testConnection.inProgress")

        service.scope.launch {
            val message = try {
                val api = GitHubApi(GitHubClient({ endpoint }, { enteredToken.ifBlank { null } }))
                PrCommentsBundle.message("settings.testConnection.success", api.fetchViewerLogin())
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                PrCommentsBundle.message("settings.testConnection.failure", e.displayMessage)
            } catch (e: Throwable) {
                PrCommentsBundle.message(
                    "settings.testConnection.failure",
                    e.message ?: e::class.java.simpleName,
                )
            }
            withContext(Dispatchers.EDT) { statusLabel.text = message }
        }
    }
}
