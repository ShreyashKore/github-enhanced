package com.gyanoba.prcomments.vcs

import com.gyanoba.prcomments.github.GitHubApi
import com.gyanoba.prcomments.model.PullRequestRef
import com.gyanoba.prcomments.model.ReviewThread
import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.comparison.DiffTooBigException
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vfs.VirtualFile
import git4idea.util.GitFileUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a review thread's line lives in the working tree right now.
 *
 * @param line 0-based line in [file]'s current document, or null when there is nothing to point at.
 * @param snippet the surrounding lines to preview, and the 0-based number of the first of them.
 */
data class CurrentLineState(
    val state: MappedLineState,
    val file: VirtualFile?,
    val line: Int?,
    /** 0-based line the comment referred to before drift correction, for the "42 -> 57" chip. */
    val originalLine: Int?,
    val snippet: String?,
    val snippetFirstLine: Int,
) {
    val isNavigable: Boolean
        get() = file != null && line != null &&
            state != MappedLineState.DELETED && state != MappedLineState.FILE_DELETED

    companion object {
        fun fileDeleted(): CurrentLineState =
            CurrentLineState(MappedLineState.FILE_DELETED, null, null, null, null, 0)

        fun unknown(file: VirtualFile?): CurrentLineState =
            CurrentLineState(MappedLineState.UNKNOWN, file, null, null, null, 0)
    }
}

/**
 * Drift correction for commented lines (§11). Never touches the EDT: VFS and Document reads run in
 * a read action, git and network reads on IO.
 */
class LineMapper(private val project: Project) {

    private val log = thisLogger()

    private data class CacheKey(val path: String, val revision: String, val modificationStamp: Long)

    private val fragmentCache = ConcurrentHashMap<CacheKey, List<DiffLineRange>>()
    private val contentCache = ConcurrentHashMap<Pair<String, String>, String>()

    fun invalidate() {
        fragmentCache.clear()
        contentCache.clear()
    }

    /**
     * @param prHeadOid the PR head commit, which `thread.currentLine` is expressed against.
     * @param api used only as a fallback when the git object is not present locally; may be null.
     */
    suspend fun map(
        thread: ReviewThread,
        repoRoot: VirtualFile,
        prHeadOid: String?,
        ref: PullRequestRef?,
        api: GitHubApi?,
        contextLines: Int = DEFAULT_CONTEXT_LINES,
    ): CurrentLineState {
        val file = readAction { repoRoot.findFileByRelativePath(thread.path) }
            ?: return CurrentLineState.fileDeleted()

        // `currentLine` is against the PR head; once a thread is outdated GitHub drops it and only
        // `originalLine` (against the commit that was reviewed) remains.
        val (targetLine1Based, revision) = when {
            thread.currentLine != null && prHeadOid != null -> thread.currentLine to prHeadOid
            thread.originalLine != null && thread.root.originalCommitOid != null ->
                thread.originalLine to thread.root.originalCommitOid!!

            else -> return CurrentLineState.unknown(file)
        }
        val targetLine = targetLine1Based - 1
        if (targetLine < 0) return CurrentLineState.unknown(file)

        val current = readAction {
            val document = FileDocumentManager.getInstance().getDocument(file)
            document?.let { it.immutableCharSequence.toString() to it.modificationStamp }
        } ?: return CurrentLineState.unknown(file)
        val (currentText, modificationStamp) = current

        val oldText = loadRevisionText(repoRoot, thread.path, revision, ref, api)
            ?: return CurrentLineState.unknown(file)

        val fragments = try {
            fragmentsFor(thread.path, revision, modificationStamp, oldText, currentText)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: DiffTooBigException) {
            log.debug("Diff too big for ${thread.path}", e)
            return CurrentLineState.unknown(file)
        }

        val mapped = LineTranslator.translate(targetLine, fragments)
        val lines = currentText.lines()
        val mappedLine = mapped.line?.takeIf { it < lines.size }
        val effectiveState = if (mapped.line != null && mappedLine == null) {
            // The mapping ran off the end of the file — the content is simply not there any more.
            MappedLineState.DELETED
        } else {
            mapped.state
        }

        val snippetFirst = ((mappedLine ?: 0) - contextLines).coerceAtLeast(0)
        val snippetLast = ((mappedLine ?: 0) + contextLines).coerceAtMost(lines.size - 1)
        val snippet = if (lines.isEmpty() || snippetLast < snippetFirst) {
            null
        } else {
            lines.subList(snippetFirst, snippetLast + 1).joinToString("\n")
        }

        return CurrentLineState(
            state = effectiveState,
            file = file,
            line = mappedLine,
            originalLine = targetLine,
            snippet = snippet,
            snippetFirstLine = snippetFirst,
        )
    }

    private fun fragmentsFor(
        path: String,
        revision: String,
        modificationStamp: Long,
        oldText: String,
        newText: String,
    ): List<DiffLineRange> = fragmentCache.computeIfAbsent(CacheKey(path, revision, modificationStamp)) {
        ComparisonManager.getInstance()
            .compareLines(oldText, newText, ComparisonPolicy.IGNORE_WHITESPACES, DumbProgressIndicator.INSTANCE)
            .map { DiffLineRange(it.startLine1, it.endLine1, it.startLine2, it.endLine2) }
    }

    /** Git first — no network round-trip when the object is already local — then the contents API. */
    private suspend fun loadRevisionText(
        repoRoot: VirtualFile,
        path: String,
        revision: String,
        ref: PullRequestRef?,
        api: GitHubApi?,
    ): String? {
        contentCache[path to revision]?.let { return it }

        val fromGit = withContext(Dispatchers.IO) {
            try {
                String(GitFileUtils.getFileContent(project, repoRoot, revision, path), Charsets.UTF_8)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: VcsException) {
                log.debug("git show $revision:$path failed, falling back to the REST contents API", e)
                null
            }
        }
        val text = fromGit ?: if (ref != null && api != null) api.fetchFileContent(ref, path, revision) else null
        return text?.also { contentCache[path to revision] = it }
    }

    companion object {
        /** Lines of context shown either side of the commented line (§11.1 step 4). */
        const val DEFAULT_CONTEXT_LINES: Int = 2
    }
}
