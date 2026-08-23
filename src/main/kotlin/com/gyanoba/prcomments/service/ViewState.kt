package com.gyanoba.prcomments.service

import com.gyanoba.prcomments.github.GitHubError
import com.gyanoba.prcomments.model.GitHubRepoCoordinates
import com.gyanoba.prcomments.model.PullRequestCandidate
import com.gyanoba.prcomments.model.PullRequestThreads
import com.gyanoba.prcomments.model.ReviewThread

/** Everything the tool window can be showing (§13.1). */
sealed interface ViewState {

    /** Nothing has been attempted yet. */
    data object Idle : ViewState

    /** No Git repository, or none with a remote that parses as a GitHub repo. */
    data object NoRepo : ViewState

    data object NoToken : ViewState

    data class DetachedHead(val repo: GitHubRepoCoordinates) : ViewState

    data class NoPr(val repo: GitHubRepoCoordinates, val branch: String) : ViewState

    data class ChoosePr(
        val repo: GitHubRepoCoordinates,
        val branch: String,
        val candidates: List<PullRequestCandidate>,
    ) : ViewState

    data class Loading(val previous: Loaded?) : ViewState

    data class Loaded(
        val data: PullRequestThreads,
        val branch: String,
        /** Threads that arrived in the last refresh and have not been looked at yet (§13.3). */
        val newThreadIds: Set<String> = emptySet(),
    ) : ViewState {
        val threads: List<ReviewThread> get() = data.threads
        val viewerLogin: String get() = data.viewerLogin
    }

    data class Error(val error: GitHubError, val previous: Loaded?) : ViewState
}

/** The last successfully loaded snapshot, whatever the current state is. */
val ViewState.loadedOrNull: ViewState.Loaded?
    get() = when (this) {
        is ViewState.Loaded -> this
        is ViewState.Loading -> previous
        is ViewState.Error -> previous
        else -> null
    }
