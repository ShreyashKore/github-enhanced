package com.gyanoba.prcomments.vcs

import com.gyanoba.prcomments.model.GitHubRepoCoordinates
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

data class DetectedRepo(
    val repository: GitRepository,
    val remoteName: String,
    val coordinates: GitHubRepoCoordinates,
) {
    val root: VirtualFile get() = repository.root
}

/**
 * Finds the Git repository to work against and turns its remote into `owner/name` (§6.1).
 * Paths are always resolved against [DetectedRepo.root], never `project.basePath` (RISKS: monorepos).
 */
object RepoDetector {

    /**
     * @param contextFile the file the user is currently looking at, used to pick a repository in a
     *   multi-root project. Must be read inside a read action by the caller.
     */
    fun detect(project: Project, configuredHost: String, contextFile: VirtualFile? = null): DetectedRepo? {
        val repositories = GitRepositoryManager.getInstance(project).repositories
        if (repositories.isEmpty()) return null

        val ordered = buildList {
            contextFile?.let { file ->
                repositories.firstOrNull { VfsUtilCore.isAncestor(it.root, file, false) }?.let(::add)
            }
            addAll(repositories.filterNot { it in this })
        }

        return ordered.firstNotNullOfOrNull { pickRemote(it, configuredHost) }
    }

    /**
     * Prefers `origin`, then any remote whose host matches the configured GitHub host, then the
     * first remote that parses at all.
     */
    fun pickRemote(repository: GitRepository, configuredHost: String): DetectedRepo? {
        val parsed = repository.remotes.flatMap { remote ->
            remote.urls.mapNotNull { url -> RemoteUrlParser.parse(url)?.let { remote to it } }
        }
        if (parsed.isEmpty()) return null

        val hostMatches = parsed.filter { (_, coords) -> RemoteUrlParser.hostMatches(coords.host, configuredHost) }
        val candidates = hostMatches.ifEmpty { parsed }
        val (remote, coordinates) = candidates.firstOrNull { (remote, _) -> remote.name == GitRemote.ORIGIN }
            ?: candidates.first()

        return DetectedRepo(repository, remote.name, coordinates)
    }
}
