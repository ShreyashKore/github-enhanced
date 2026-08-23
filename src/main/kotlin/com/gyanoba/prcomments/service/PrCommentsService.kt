package com.gyanoba.prcomments.service

import com.gyanoba.prcomments.PrCommentsBundle
import com.gyanoba.prcomments.github.GitHubApi
import com.gyanoba.prcomments.github.GitHubClient
import com.gyanoba.prcomments.github.GitHubError
import com.gyanoba.prcomments.model.PullRequestRef
import com.gyanoba.prcomments.model.PullRequestThreads
import com.gyanoba.prcomments.model.ReviewComment
import com.gyanoba.prcomments.model.ReviewThread
import com.gyanoba.prcomments.model.ThreadFilter
import com.gyanoba.prcomments.model.ThreadSort
import com.gyanoba.prcomments.settings.TokenStore
import com.gyanoba.prcomments.vcs.DetectedRepo
import com.gyanoba.prcomments.vcs.LineMapper
import com.gyanoba.prcomments.vcs.PrResolution
import com.gyanoba.prcomments.vcs.PrResolver
import com.gyanoba.prcomments.vcs.RepoDetector
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns all PR comment state for one project: fetching, refreshing, mutating and the filter the UI
 * is currently applying. The coroutine scope is injected by the platform — never create one here
 * (guardrail §13.1).
 */
@Service(Service.Level.PROJECT)
class PrCommentsService(private val project: Project, private val cs: CoroutineScope) : Disposable {

    private val log = thisLogger()

    private val _state = MutableStateFlow<ViewState>(ViewState.Idle)
    val state: StateFlow<ViewState> = _state.asStateFlow()

    private val _filter = MutableStateFlow(ThreadFilter())
    val filter: StateFlow<ThreadFilter> = _filter.asStateFlow()

    private val _sort = MutableStateFlow(ThreadSort())
    val sort: StateFlow<ThreadSort> = _sort.asStateFlow()

    /** Thread node ids with a mutation in flight, so the UI can disable their buttons. */
    private val _busyThreads = MutableStateFlow<Set<String>>(emptySet())
    val busyThreads: StateFlow<Set<String>> = _busyThreads.asStateFlow()

    /** Unsent reply text, keyed by thread node id. Survives refreshes (§13.3). */
    private val drafts = ConcurrentHashMap<String, String>()

    private val settings get() = PrCommentsSettings.getInstance(project)

    private val client = GitHubClient(
        endpointProvider = { settings.endpoint() },
        tokenProvider = { TokenStore.get(settings.githubHost) },
    )

    val api: GitHubApi = GitHubApi(client)

    val lineMapper: LineMapper = LineMapper(project)

    val scope: CoroutineScope get() = cs

    private val refreshMutex = Mutex()
    private var refreshJob: Job? = null
    private var autoRefreshJob: Job? = null
    private var lastKnownBranch: String? = null

    init {
        settings.filter.let { _filter.value = it }
        settings.sort.let { _sort.value = it }
        project.messageBus.connect(this).subscribe(
            GitRepository.GIT_REPO_CHANGE,
            GitRepositoryChangeListener { repository -> onRepositoryChanged(repository) },
        )
    }

    override fun dispose() {
        autoRefreshJob?.cancel()
        refreshJob?.cancel()
    }

    // -- filter & sort -------------------------------------------------------------------------

    fun updateFilter(transform: (ThreadFilter) -> ThreadFilter) {
        _filter.update(transform)
        settings.filter = _filter.value
    }

    fun updateSort(transform: (ThreadSort) -> ThreadSort) {
        _sort.update(transform)
        settings.sort = _sort.value
    }

    fun clearFilters() = updateFilter { ThreadFilter() }

    // -- drafts --------------------------------------------------------------------------------

    fun draftFor(threadId: String): String = drafts[threadId].orEmpty()

    fun setDraft(threadId: String, text: String) {
        if (text.isBlank()) drafts.remove(threadId) else drafts[threadId] = text
    }

    // -- refresh -------------------------------------------------------------------------------

    /** @param force skips the cheap `updatedAt` guard and always re-fetches. */
    fun refresh(force: Boolean = false) {
        refreshJob?.cancel()
        refreshJob = cs.launch {
            try {
                refreshMutex.withLock { doRefresh(force) }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                _state.update { ViewState.Error(e, it.loadedOrNull) }
                notifyError("notification.refreshFailed.title", e.displayMessage) { refresh(force = true) }
            } catch (e: Throwable) {
                log.warn("PR comments refresh failed", e)
                val wrapped = GitHubError.Network(e)
                _state.update { ViewState.Error(wrapped, it.loadedOrNull) }
                notifyError("notification.refreshFailed.title", wrapped.displayMessage) { refresh(force = true) }
            }
        }
    }

    /** Refreshes only if the data is older than the configured interval (§13.2). */
    fun refreshIfStale() {
        val loaded = _state.value.loadedOrNull ?: run { refresh(); return }
        val age = Duration.between(loaded.data.fetchedAt, Instant.now())
        if (age.seconds >= settings.refreshIntervalSeconds) refresh()
    }

    private suspend fun doRefresh(force: Boolean) {
        if (TokenStore.get(settings.githubHost) == null) {
            _state.value = ViewState.NoToken
            return
        }

        val detected = detectRepo() ?: run {
            _state.value = ViewState.NoRepo
            return
        }
        val branch = readAction { detected.repository.currentBranchName }
        lastKnownBranch = branch

        val resolution = PrResolver(api).resolve(
            repo = detected.coordinates,
            branch = branch,
            overrideNumber = settings.prOverrideFor(branch),
        )
        val ref = when (resolution) {
            is PrResolution.DetachedHead -> {
                _state.value = ViewState.DetachedHead(detected.coordinates); return
            }

            is PrResolution.None -> {
                _state.value = ViewState.NoPr(detected.coordinates, resolution.branch); return
            }

            is PrResolution.Ambiguous -> {
                _state.value = ViewState.ChoosePr(detected.coordinates, resolution.branch, resolution.candidates)
                return
            }

            is PrResolution.Resolved -> resolution.ref
        }

        val previous = _state.value.loadedOrNull?.takeIf { it.data.pullRequest.ref == ref }
        if (!force && previous != null && !hasChangedSince(ref, previous.data)) {
            // Nothing new upstream: keep the model (and the selection) exactly as it is, but clear a
            // stale Error/Loading wrapper now that we know GitHub is reachable again.
            _state.value = previous
            return
        }

        _state.value = ViewState.Loading(previous)
        val fetched = api.fetchThreads(ref)
        lineMapper.invalidate()
        _state.value = merge(previous, fetched, branch.orEmpty())
    }

    /** The §13.4 rate-limit guard: one tiny query instead of the whole thread graph. */
    private suspend fun hasChangedSince(ref: PullRequestRef, previous: PullRequestThreads): Boolean {
        val known = previous.pullRequest.updatedAt ?: return true
        return try {
            val current = api.fetchPullRequestUpdatedAt(ref) ?: return true
            current.isAfter(known)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubError) {
            log.debug("updatedAt pre-check failed; falling through to a full fetch", e)
            true
        }
    }

    /** Keeps drafts and marks threads that were not present before as new (§13.3). */
    private fun merge(previous: ViewState.Loaded?, fetched: PullRequestThreads, branch: String): ViewState.Loaded {
        val knownIds = previous?.threads?.mapTo(mutableSetOf()) { it.nodeId }.orEmpty()
        val stillNew = previous?.newThreadIds.orEmpty()
        val newIds = if (previous == null) {
            emptySet()
        } else {
            fetched.threads.mapNotNull { it.nodeId.takeIf { id -> id !in knownIds || id in stillNew } }.toSet()
        }
        drafts.keys.retainAll(fetched.threads.mapTo(mutableSetOf()) { it.nodeId })
        return ViewState.Loaded(fetched, branch, newIds)
    }

    fun markThreadSeen(threadId: String) {
        _state.update { current ->
            val loaded = current as? ViewState.Loaded ?: return@update current
            if (threadId !in loaded.newThreadIds) loaded else loaded.copy(newThreadIds = loaded.newThreadIds - threadId)
        }
    }

    private suspend fun detectRepo(): DetectedRepo? {
        val contextFile = currentContextFile()
        return readAction { RepoDetector.detect(project, settings.githubHost, contextFile) }
    }

    private suspend fun currentContextFile(): VirtualFile? = withContext(Dispatchers.EDT) {
        if (project.isDisposed) null else FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
    }

    suspend fun detectedRepo(): DetectedRepo? = detectRepo()

    // -- auto refresh --------------------------------------------------------------------------

    /** Driven by tool window visibility so a hidden tool window costs no rate limit (§13.2). */
    fun setToolWindowVisible(visible: Boolean) {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
        if (!visible) return

        refreshIfStale()
        if (!settings.autoRefreshEnabled) return
        autoRefreshJob = cs.launch {
            while (isActive) {
                delay(settings.refreshIntervalSeconds * 1000L)
                if (!isActive) break
                refresh(force = false)
            }
        }
    }

    private fun onRepositoryChanged(repository: GitRepository) {
        val branch = repository.currentBranchName
        if (branch == lastKnownBranch) return
        lastKnownBranch = branch
        lineMapper.invalidate()
        refresh(force = true)
    }

    // -- PR selection --------------------------------------------------------------------------

    fun pinPullRequest(branch: String?, number: Int?) {
        settings.setPrOverride(branch, number)
        refresh(force = true)
    }

    // -- mutations -----------------------------------------------------------------------------

    /**
     * Optimistically appends the reply, then reconciles with GitHub's response. On failure the
     * provisional comment is removed and the text is handed back to the caller so nothing is lost
     * (§12.2).
     */
    fun reply(
        thread: ReviewThread,
        body: String,
        alsoResolve: Boolean,
        onFailure: (String) -> Unit,
    ) {
        val loaded = _state.value.loadedOrNull ?: return
        val ref = loaded.data.pullRequest.ref
        val rootId = thread.root.databaseId
        if (rootId <= 0) {
            onFailure(body)
            notifyError("notification.replyFailed.title", "GitHub did not report a numeric id for this thread.")
            return
        }

        val provisionalId = "pending-${System.nanoTime()}"
        val now = Instant.now()
        val provisional = ReviewComment(
            nodeId = provisionalId,
            databaseId = 0,
            authorLogin = loaded.viewerLogin,
            avatarUrl = null,
            bodyMarkdown = body,
            createdAt = now,
            updatedAt = now,
            htmlUrl = thread.root.htmlUrl,
            diffHunk = null,
            originalCommitOid = null,
            isReply = true,
            isPending = true,
        )
        updateThread(thread.nodeId) { it.withComments(it.comments + provisional) }
        setBusy(thread.nodeId, true)
        drafts.remove(thread.nodeId)

        cs.launch {
            try {
                val created = api.replyToThread(ref, rootId, body)
                updateThread(thread.nodeId) { current ->
                    current.withComments(current.comments.map { if (it.nodeId == provisionalId) created else it })
                }
                if (alsoResolve) setResolvedInternal(thread.nodeId, true)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                updateThread(thread.nodeId) { current ->
                    current.withComments(current.comments.filterNot { it.nodeId == provisionalId })
                }
                drafts[thread.nodeId] = body
                withContext(Dispatchers.EDT) { onFailure(body) }
                notifyError("notification.replyFailed.title", messageOf(e)) {
                    findThread(thread.nodeId)?.let { reply(it, body, alsoResolve, onFailure) }
                }
            } finally {
                setBusy(thread.nodeId, false)
            }
        }
    }

    fun setResolved(threadId: String, resolved: Boolean) {
        cs.launch { setResolvedInternal(threadId, resolved) }
    }

    private suspend fun setResolvedInternal(threadId: String, resolved: Boolean) {
        val before = findThread(threadId) ?: return
        if (before.isResolved == resolved) return
        updateThread(threadId) { it.copy(isResolved = resolved) }
        setBusy(threadId, true)
        try {
            val actual = api.setThreadResolved(threadId, resolved)
            if (actual != resolved) updateThread(threadId) { it.copy(isResolved = actual) }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            updateThread(threadId) { it.copy(isResolved = before.isResolved) }
            notifyError("notification.resolveFailed.title", messageOf(e)) { setResolved(threadId, resolved) }
        } finally {
            setBusy(threadId, false)
        }
    }

    fun findThread(threadId: String): ReviewThread? =
        _state.value.loadedOrNull?.threads?.firstOrNull { it.nodeId == threadId }

    private fun updateThread(threadId: String, transform: (ReviewThread) -> ReviewThread) {
        _state.update { current ->
            val loaded = current.loadedOrNull ?: return@update current
            val updated = loaded.data.copy(
                threads = loaded.threads.map { if (it.nodeId == threadId) transform(it) else it }
            )
            val newLoaded = loaded.copy(data = updated)
            when (current) {
                is ViewState.Loaded -> newLoaded
                is ViewState.Loading -> current.copy(previous = newLoaded)
                is ViewState.Error -> current.copy(previous = newLoaded)
                else -> current
            }
        }
    }

    private fun setBusy(threadId: String, busy: Boolean) {
        _busyThreads.update { if (busy) it + threadId else it - threadId }
    }

    private fun messageOf(e: Throwable): String =
        (e as? GitHubError)?.displayMessage ?: e.message ?: e::class.java.simpleName

    private fun notifyError(titleKey: String, content: String, retry: (() -> Unit)? = null) {
        if (project.isDisposed) return
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(PrCommentsBundle.message(titleKey), content, NotificationType.ERROR)
        if (retry != null) {
            notification.addAction(
                NotificationAction.createSimpleExpiring(PrCommentsBundle.message("notification.retry"), retry)
            )
        }
        notification.notify(project)
    }

    companion object {
        const val NOTIFICATION_GROUP: String = "PR Comments"

        fun getInstance(project: Project): PrCommentsService = project.service()
    }
}
