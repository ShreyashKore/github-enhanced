package com.gyanoba.prcomments.service

import com.gyanoba.prcomments.github.GitHubEndpoint
import com.gyanoba.prcomments.model.ReplyState
import com.gyanoba.prcomments.model.SortKey
import com.gyanoba.prcomments.model.SortOrder
import com.gyanoba.prcomments.model.ThreadFilter
import com.gyanoba.prcomments.model.ThreadSort
import com.gyanoba.prcomments.model.TriState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.XCollection
import com.intellij.util.xmlb.annotations.XMap

/**
 * Project-level, non-secret settings. The token itself lives in [com.gyanoba.prcomments.settings.TokenStore].
 */
@Service(Service.Level.PROJECT)
@State(name = "PrCommentsSettings", storages = [Storage("prComments.xml")])
class PrCommentsSettings : PersistentStateComponent<PrCommentsSettings.State> {

    class State {
        @JvmField var githubHost: String = GitHubEndpoint.DOT_COM

        /** Blank means "derive from the host". */
        @JvmField var apiBaseUrl: String = ""

        @JvmField var graphQlUrl: String = ""

        @JvmField var refreshIntervalSeconds: Int = DEFAULT_REFRESH_SECONDS
        @JvmField var autoRefreshEnabled: Boolean = true

        // Last-used filter/sort, restored on the next IDE start (§9.3).
        @JvmField var resolution: String = TriState.ALL.name
        @JvmField var replyState: String = ReplyState.ALL.name
        @JvmField var includeOutdated: Boolean = true
        @JvmField var pathQuery: String = ""
        @JvmField var textQuery: String = ""

        @XCollection(style = XCollection.Style.v2)
        @JvmField var authors: MutableList<String> = mutableListOf()

        @JvmField var sortKey: String = SortKey.LAST_ACTIVITY.name
        @JvmField var sortOrder: String = SortOrder.DESC.name

        /** Branch name -> manually pinned PR number (§6.3). */
        @XMap(entryTagName = "branch", keyAttributeName = "name", valueAttributeName = "pr")
        @JvmField var branchPrOverrides: MutableMap<String, String> = LinkedHashMap()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        XmlSerializerUtil.copyBean(loaded, state)
    }

    // -- typed accessors -----------------------------------------------------------------------

    var githubHost: String
        get() = state.githubHost.trim().ifEmpty { GitHubEndpoint.DOT_COM }
        set(value) {
            state.githubHost = value.trim()
        }

    var apiBaseUrl: String
        get() = state.apiBaseUrl.trim()
        set(value) {
            state.apiBaseUrl = value.trim()
        }

    var graphQlUrl: String
        get() = state.graphQlUrl.trim()
        set(value) {
            state.graphQlUrl = value.trim()
        }

    var refreshIntervalSeconds: Int
        get() = state.refreshIntervalSeconds.coerceIn(MIN_REFRESH_SECONDS, MAX_REFRESH_SECONDS)
        set(value) {
            state.refreshIntervalSeconds = value.coerceIn(MIN_REFRESH_SECONDS, MAX_REFRESH_SECONDS)
        }

    var autoRefreshEnabled: Boolean
        get() = state.autoRefreshEnabled
        set(value) {
            state.autoRefreshEnabled = value
        }

    fun endpoint(): GitHubEndpoint = GitHubEndpoint.of(githubHost, apiBaseUrl, graphQlUrl)

    var filter: ThreadFilter
        get() = ThreadFilter(
            resolution = enumOrDefault(state.resolution, TriState.ALL),
            replyState = enumOrDefault(state.replyState, ReplyState.ALL),
            includeOutdated = state.includeOutdated,
            authors = state.authors.toSet(),
            pathQuery = state.pathQuery,
            textQuery = state.textQuery,
        )
        set(value) {
            state.resolution = value.resolution.name
            state.replyState = value.replyState.name
            state.includeOutdated = value.includeOutdated
            state.authors = value.authors.toMutableList()
            state.pathQuery = value.pathQuery
            state.textQuery = value.textQuery
        }

    var sort: ThreadSort
        get() = ThreadSort(
            key = enumOrDefault(state.sortKey, SortKey.LAST_ACTIVITY),
            order = enumOrDefault(state.sortOrder, SortOrder.DESC),
        )
        set(value) {
            state.sortKey = value.key.name
            state.sortOrder = value.order.name
        }

    fun prOverrideFor(branch: String?): Int? =
        branch?.let { state.branchPrOverrides[it]?.toIntOrNull()?.takeIf { number -> number > 0 } }

    fun setPrOverride(branch: String?, number: Int?) {
        if (branch.isNullOrBlank()) return
        if (number == null || number <= 0) {
            state.branchPrOverrides.remove(branch)
        } else {
            state.branchPrOverrides[branch] = number.toString()
        }
    }

    companion object {
        const val DEFAULT_REFRESH_SECONDS: Int = 120
        const val MIN_REFRESH_SECONDS: Int = 30
        const val MAX_REFRESH_SECONDS: Int = 3600

        fun getInstance(project: Project): PrCommentsSettings = project.service()

        private inline fun <reified E : Enum<E>> enumOrDefault(raw: String, fallback: E): E =
            enumValues<E>().firstOrNull { it.name == raw } ?: fallback
    }
}
