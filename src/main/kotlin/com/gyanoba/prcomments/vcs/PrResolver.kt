package com.gyanoba.prcomments.vcs

import com.gyanoba.prcomments.github.GitHubApi
import com.gyanoba.prcomments.model.PullRequestCandidate
import com.gyanoba.prcomments.model.PullRequestRef

/** Outcome of resolving "which PR does the checked-out branch belong to?" (§6.2). */
sealed interface PrResolution {
    /** Not on a branch (detached HEAD), so there is nothing to look up. */
    data object DetachedHead : PrResolution

    data class Resolved(val ref: PullRequestRef, val branch: String) : PrResolution

    data class None(val branch: String) : PrResolution

    data class Ambiguous(val branch: String, val candidates: List<PullRequestCandidate>) : PrResolution
}

class PrResolver(private val api: GitHubApi) {

    /**
     * @param overrideNumber a PR number the user pinned for this branch; short-circuits the lookup.
     */
    suspend fun resolve(
        repo: com.gyanoba.prcomments.model.GitHubRepoCoordinates,
        branch: String?,
        overrideNumber: Int?,
    ): PrResolution {
        if (overrideNumber != null && overrideNumber > 0) {
            return PrResolution.Resolved(PullRequestRef(repo, overrideNumber), branch.orEmpty())
        }
        if (branch.isNullOrBlank()) return PrResolution.DetachedHead

        val candidates = api.findPullRequestsForBranch(repo, branch)
        return when {
            candidates.isEmpty() -> PrResolution.None(branch)
            candidates.size == 1 -> PrResolution.Resolved(PullRequestRef(repo, candidates.first().number), branch)
            else -> PrResolution.Ambiguous(branch, candidates)
        }
    }
}
