# Implementation Plan — "PR Comments" IntelliJ Platform Plugin

> **Audience:** an autonomous coding agent.
> **Goal:** a native JetBrains IDE plugin that surfaces all GitHub PR review comments in a dedicated tool window, with filtering, sorting, diff-hunk snapshots, current-line state, inline replies, and thread resolution.

---

## 0. Operating instructions for the agent

1. **Work milestone by milestone.** Each milestone below has an explicit *Acceptance criteria* block. Do not start milestone N+1 until N's criteria pass in a running sandbox IDE (`./gradlew runIde`).
2. **Verify SDK versions before writing build files.** The IntelliJ Platform Gradle Plugin and platform APIs change frequently. Check <https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html> and adjust versions/DSL in this plan if they have drifted. The *architecture* in this document is stable; the *version numbers* are not.
3. **Never call GitHub APIs from the EDT.** See §14 (Platform guardrails). Violating this produces `Slow operations are prohibited on EDT` assertions and freezes.
4. **Prefer platform components over hand-rolled Swing.** If a JetBrains component exists (`JBList`, `JBTable`, `SearchTextField`, `EditorTextField`, `DiffManager`, Kotlin UI DSL v2), use it.
5. **Commit after every milestone** with a message naming the milestone.
6. When an API you need is marked `@ApiStatus.Internal` or `@ApiStatus.Experimental`, stop and find a stable alternative or note the risk in `RISKS.md`.

---

## 1. Product scope

### 1.1 MVP (must ship)

| # | Capability |
|---|---|
| F1 | Auto-detect the GitHub repo and the PR for the current branch; allow manual PR number override |
| F2 | Dedicated tool window listing **every review thread** on the PR |
| F3 | Each list row shows: file path, line, author, relative timestamp, resolved badge, reply count, first line of body |
| F4 | Filter by: **Resolved / Unresolved**, **Replied / Not replied**, **Outdated / Current**, author, file path, free-text search |
| F5 | Sort by: created date, last-activity date, file path (asc/desc) |
| F6 | Detail pane shows the full thread (all comments, markdown-rendered) |
| F7 | Detail pane shows the **diff-hunk snapshot** (~4–5 lines) exactly as the code looked when commented, syntax highlighted |
| F8 | Detail pane shows the **current state** of that line in the working tree, with drift-corrected line number |
| F9 | Double-click / Enter navigates the editor to the current location of that line |
| F10 | Reply to a thread from within the tool window |
| F11 | Resolve / unresolve a thread from within the tool window |
| F12 | Manual refresh + auto-refresh on a timer; optimistic UI on mutations |
| F13 | PAT stored in `PasswordSafe`; settings page for token + refresh interval |

### 1.2 Explicitly out of MVP

- Creating *new* review comments on arbitrary lines (only replies to existing threads).
- Submitting/approving reviews, PR merge, CI status.
- GitHub Enterprise Server (design for it — configurable base URL — but do not test it).
- Multi-PR / multi-repo simultaneous view.

### 1.3 Stretch (Milestone 10+)

- Gutter icons + editor inlays showing comment threads inline (VS Code parity).
- New comment creation on selected lines.
- Notification on new incoming comments.

---

## 2. Locked technical decisions

Do **not** re-litigate these.

| Decision | Choice | Reason |
|---|---|---|
| Plugin type | Native IntelliJ Platform plugin (Plugin DevKit) | Only path that supports a stateful tool window with interactive components |
| Language | Kotlin 2.x | Platform-idiomatic; coroutines support |
| Build | Gradle Kotlin DSL + `org.jetbrains.intellij.platform` 2.x | Current official plugin |
| Target IDE | IntelliJ IDEA Community 2024.3+ as dev target; `until-build` left open | Widest compatibility; Git4Idea is bundled everywhere |
| UI framework | Kotlin UI DSL v2 + JBList/JBSplitter + `EditorTextField` | Least code for a native look |
| GitHub thread data | **GraphQL v4** | REST cannot express `isResolved` on review threads |
| Resolve/unresolve | **GraphQL mutations** | Only available via GraphQL |
| Reply | **REST** `POST /pulls/{n}/comments/{id}/replies` | Simpler and more reliable than `addPullRequestReviewThreadReply`, which can create a *pending* review |
| HTTP client | Java 11+ `HttpClient` (JDK bundled) | Zero extra deps; avoids classloader conflicts |
| JSON | `kotlinx.serialization` | Small, Kotlin-native. **Shade/relocate is not needed but pin the version to what the platform ships if a conflict arises.** |
| Auth | Own PAT in `PasswordSafe` | The bundled GitHub plugin's account manager is internal API and unstable across releases |
| Async | Kotlin coroutines via platform service scope | Modern platform standard |

---

## 3. Repository scaffold

### 3.1 Bootstrap

Start from the official template rather than an empty directory:

```
https://github.com/JetBrains/intellij-platform-plugin-template
```

Then strip: the Qodana config, the template's sample `MyProjectService`, and the changelog automation if it adds noise.

### 3.2 Target layout

```
pr-comments/
├── build.gradle.kts
├── gradle.properties
├── settings.gradle.kts
├── RISKS.md                      # agent maintains this
└── src/main/
    ├── kotlin/com/gyanoba/prcomments/
    │   ├── PrCommentsBundle.kt
    │   ├── model/
    │   │   ├── ReviewThread.kt
    │   │   ├── ReviewComment.kt
    │   │   ├── PullRequestRef.kt
    │   │   └── Filters.kt              # ThreadFilter, SortKey, SortOrder
    │   ├── github/
    │   │   ├── GitHubClient.kt         # HTTP + auth + rate limit
    │   │   ├── GraphQlQueries.kt       # query/mutation strings
    │   │   ├── GitHubDtos.kt           # @Serializable response DTOs
    │   │   └── GitHubApi.kt            # high-level suspend funcs
    │   ├── vcs/
    │   │   ├── RepoDetector.kt         # remote URL -> owner/name
    │   │   ├── PrResolver.kt           # branch -> PR number
    │   │   └── LineMapper.kt           # drift correction
    │   ├── service/
    │   │   ├── PrCommentsService.kt    # @Service(PROJECT), state holder
    │   │   └── PrCommentsSettings.kt   # PersistentStateComponent
    │   ├── ui/
    │   │   ├── PrCommentsToolWindowFactory.kt
    │   │   ├── PrCommentsPanel.kt      # root splitter
    │   │   ├── ThreadListPanel.kt
    │   │   ├── ThreadListCellRenderer.kt
    │   │   ├── FilterToolbar.kt
    │   │   ├── ThreadDetailPanel.kt
    │   │   ├── DiffHunkPreview.kt
    │   │   ├── CurrentStatePreview.kt
    │   │   └── ReplyEditor.kt
    │   ├── settings/
    │   │   ├── PrCommentsConfigurable.kt
    │   │   └── TokenStore.kt           # PasswordSafe wrapper
    │   └── actions/
    │       ├── RefreshAction.kt
    │       ├── ToggleResolveAction.kt
    │       └── OpenInBrowserAction.kt
    └── resources/
        ├── META-INF/plugin.xml
        ├── messages/PrCommentsBundle.properties
        └── icons/
```

### 3.3 `build.gradle.kts` (starting point — verify versions)

```kotlin
plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.gyanoba"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3")
        bundledPlugin("Git4Idea")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3") {
        // platform ships kotlinx-coroutines; never bundle your own
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    }
}

kotlin { jvmToolchain(21) }

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }   // no upper bound
        }
    }
}
```

> **Critical:** never bundle `kotlinx-coroutines` or `kotlin-stdlib` — the platform provides them. Bundling causes `NoSuchMethodError` at runtime.

### 3.4 `plugin.xml`

```xml
<idea-plugin>
  <id>com.gyanoba.prcomments</id>
  <name>PR Comments</name>
  <vendor email="..." url="https://gyanoba.com">Gyanoba</vendor>

  <depends>com.intellij.modules.platform</depends>
  <depends>Git4Idea</depends>

  <extensions defaultExtensionNs="com.intellij">
    <toolWindow id="PR Comments"
                anchor="right"
                icon="/icons/toolwindow.svg"
                factoryClass="com.gyanoba.prcomments.ui.PrCommentsToolWindowFactory"/>
    <projectConfigurable
        instance="com.gyanoba.prcomments.settings.PrCommentsConfigurable"
        displayName="PR Comments"
        groupId="tools"/>
    <notificationGroup id="PR Comments" displayType="BALLOON"/>
  </extensions>
</idea-plugin>
```

---

## 4. Milestone 0 — Skeleton tool window

**Tasks**
1. Scaffold per §3.
2. Implement `PrCommentsToolWindowFactory : ToolWindowFactory` returning a placeholder panel with a label.
3. Wire `PrCommentsBundle` (message bundle) so no strings are hardcoded in UI classes.

**Acceptance**
- `./gradlew runIde` launches; a "PR Comments" tool window is visible on the right rail with the placeholder.
- `./gradlew verifyPlugin` passes with no errors.

---

## 5. Milestone 1 — Authentication & settings

**Tasks**
1. `TokenStore` — wrap `PasswordSafe`:
   ```kotlin
   private fun attrs(host: String) = CredentialAttributes(
       generateServiceName("PR Comments", host)
   )
   fun get(host: String): String? = PasswordSafe.instance.getPassword(attrs(host))
   fun set(host: String, token: String?) { PasswordSafe.instance.setPassword(attrs(host), token) }
   ```
2. `PrCommentsSettings : PersistentStateComponent<State>` (project-level) holding: `githubHost` (default `github.com`), `apiBaseUrl`, `graphQlUrl`, `refreshIntervalSeconds` (default 120), `autoRefreshEnabled`, and last-used filter/sort state.
3. `PrCommentsConfigurable` using Kotlin UI DSL v2:
   ```kotlin
   override fun createPanel() = panel {
       row("GitHub host:") { textField().bindText(state::githubHost) }
       row("Personal access token:") {
           passwordField().bindText(::tokenGetter, ::tokenSetter)
       }
       row { button("Test connection") { validateToken() } }
       row("Auto-refresh (seconds):") { intTextField(30..3600).bindIntText(state::refreshIntervalSeconds) }
   }
   ```
4. "Test connection" calls `GET /user` and shows the resolved login or the error.

**Required PAT scopes** (document in the settings panel comment row):
- Classic PAT: `repo`
- Fine-grained: *Pull requests: Read & Write*, *Contents: Read*, *Metadata: Read*

**Acceptance**
- Token survives IDE restart, is not visible in `.idea/` or any XML on disk.
- Test connection reports success with the authenticated username.

---

## 6. Milestone 2 — Repo & PR detection

**Tasks**
1. `RepoDetector`:
    - Get `GitRepositoryManager.getInstance(project).repositories`.
    - Pick the repository containing the currently open file, else the first.
    - Read remotes; prefer `origin`, else the first remote whose URL host matches the configured GitHub host.
    - Parse both URL forms into `owner/name`:
        - `git@github.com:owner/name.git`
        - `https://github.com/owner/name.git`
    - Handle `.git` suffix, trailing slashes, and SSH aliases (`git@github-work:owner/name.git` → strip host alias, still match by path).
2. `PrResolver`:
    - Current branch: `repository.currentBranchName`.
    - GraphQL: `repository { ref(qualifiedName:$branch) { associatedPullRequests(first:5, states:OPEN) { nodes { number title headRefOid } } } }`.
    - If 0 results → show an "enter PR number" prompt in the tool window.
    - If >1 → let the user pick from a combo box.
3. Persist the manually chosen PR number per branch in settings so it survives restarts.

**Acceptance**
- On a repo with an open PR for the checked-out branch, the tool window header shows `owner/name #123 — <title>`.
- On a detached HEAD or a branch with no PR, a clear empty-state with a "Set PR number…" action appears (no exception, no red balloon).

---

## 7. Milestone 3 — GitHub data layer

### 7.1 `GitHubClient`

- Java `HttpClient` with `connectTimeout = 15s`, per-request `timeout = 30s`.
- Headers: `Authorization: Bearer <token>`, `Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28`, `User-Agent: pr-comments-plugin/<version>`.
- All methods are `suspend` and run on `Dispatchers.IO`.
- Map non-2xx into a sealed `GitHubError`: `Unauthorized`, `Forbidden(rateLimitResetAt)`, `NotFound`, `Network(cause)`, `Unknown(status, body)`.
- On `403`/`429`, read `x-ratelimit-remaining` and `x-ratelimit-reset` and surface a human message ("GitHub rate limit exceeded, resets at 14:32").
- GraphQL responses: even on HTTP 200, check the `errors` array and fail loudly.

### 7.2 The thread query (`GraphQlQueries.FETCH_THREADS`)

```graphql
query Threads($owner: String!, $name: String!, $number: Int!, $cursor: String) {
  viewer { login }
  repository(owner: $owner, name: $name) {
    pullRequest(number: $number) {
      number
      title
      url
      headRefOid
      baseRefOid
      reviewThreads(first: 50, after: $cursor) {
        pageInfo { hasNextPage endCursor }
        nodes {
          id
          isResolved
          isOutdated
          path
          line
          startLine
          originalLine
          originalStartLine
          diffSide
          resolvedBy { login }
          comments(first: 100) {
            nodes {
              id
              databaseId
              body
              createdAt
              updatedAt
              url
              diffHunk
              author { login avatarUrl url }
              originalCommit { oid }
              replyTo { id }
            }
          }
        }
      }
    }
  }
}
```

**Pagination:** loop on `pageInfo.hasNextPage`, passing `endCursor`, until exhausted. Cap at 20 pages as a runaway guard. If a thread has >100 comments, fetch the remainder lazily when the thread is opened (rare; log a warning for MVP).

### 7.3 Mutations

```graphql
mutation Resolve($threadId: ID!) {
  resolveReviewThread(input: { threadId: $threadId }) { thread { id isResolved } }
}
mutation Unresolve($threadId: ID!) {
  unresolveReviewThread(input: { threadId: $threadId }) { thread { id isResolved } }
}
```

### 7.4 Reply (REST)

```
POST https://api.github.com/repos/{owner}/{name}/pulls/{number}/comments/{comment_id}/replies
Body: {"body": "<markdown>"}
```
Where `comment_id` is the **`databaseId` of the thread's root (first) comment**, not the GraphQL node ID. Response is the created comment — map it into the model and append optimistically.

**Acceptance**
- A unit-testable `GitHubApi.fetchThreads(prRef): PullRequestThreads` returns a fully populated model against a real PR (verify by count against the GitHub web UI).
- Deliberately using an invalid token produces a `GitHubError.Unauthorized` with a readable message, not a stack trace balloon.

---

## 8. Milestone 4 — Domain model, filtering, sorting

### 8.1 Model

```kotlin
data class ReviewComment(
    val nodeId: String,
    val databaseId: Long,
    val authorLogin: String,
    val avatarUrl: String?,
    val bodyMarkdown: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val htmlUrl: String,
    val diffHunk: String?,
    val originalCommitOid: String?,
    val isReply: Boolean,
)

data class ReviewThread(
    val nodeId: String,
    val path: String,
    val isResolved: Boolean,
    val isOutdated: Boolean,
    val resolvedByLogin: String?,
    val currentLine: Int?,        // GraphQL `line` — position in PR head
    val originalLine: Int?,       // position in the commit commented on
    val diffSide: DiffSide,       // LEFT | RIGHT
    val comments: List<ReviewComment>,  // root first, chronological
) {
    val root get() = comments.first()
    val replyCount get() = comments.size - 1
    val lastActivityAt get() = comments.maxOf { it.updatedAt }
}
```

### 8.2 Filter semantics — define these precisely

| Filter | Predicate |
|---|---|
| `Resolved` | `isResolved == true` |
| `Unresolved` | `isResolved == false` |
| `Replied` | `comments.size > 1` |
| `Not replied` | `comments.size == 1` |
| `Replied by me` | `comments.drop(1).any { it.authorLogin == viewerLogin }` |
| `Awaiting my reply` | `!isResolved && comments.last().authorLogin != viewerLogin` |
| `Outdated` | `isOutdated == true` |
| `Author` | `root.authorLogin == selected` |
| `File` | `path.contains(query, ignoreCase = true)` |
| `Text` | any comment body contains the query, case-insensitive |

Model as:
```kotlin
data class ThreadFilter(
    val resolution: TriState = TriState.ALL,      // ALL | RESOLVED | UNRESOLVED
    val replyState: ReplyState = ReplyState.ALL,  // ALL | REPLIED | NOT_REPLIED | AWAITING_ME
    val includeOutdated: Boolean = true,
    val authors: Set<String> = emptySet(),
    val pathQuery: String = "",
    val textQuery: String = "",
)
```
Filters compose with **AND**. Multiple selected authors compose with **OR**.

### 8.3 Sorting

```kotlin
enum class SortKey { CREATED, LAST_ACTIVITY, FILE_PATH, LINE }
enum class SortOrder { ASC, DESC }
```
Default: `LAST_ACTIVITY, DESC`. Secondary sort always `path` then `line` for stable ordering.

**Acceptance**
- Pure-Kotlin unit tests (no IDE fixture) covering every predicate above with a hand-built list of 10 threads.
- Filter + sort of 500 threads completes in <10 ms (it's in-memory; no reason to be slow).

---

## 9. Milestone 5 — Tool window UI

### 9.1 Layout

```
┌───────────────────────────────────────────────────────────────┐
│ Header: owner/name #123 — Title   [↻] [⚙] [open in browser]   │
├───────────────────────────────────────────────────────────────┤
│ FilterToolbar: [Search] [Resolution ▾] [Replies ▾] [Author ▾]  │
│                [☐ outdated]  [Sort: Last activity ▾] [↑↓]      │
├──────────────────────────┬────────────────────────────────────┤
│ ThreadListPanel          │ ThreadDetailPanel                  │
│  ▸ Foo.kt:42  @alice  2d │  ┌ Thread header + Resolve button ┐│
│    "This allocates on…"  │  ├ DiffHunkPreview (when commented)││
│  ▸ Bar.kt:17  @bob   5h  │  ├ CurrentStatePreview (now)       ││
│    ✔ resolved · 3 replies│  ├ Comments (markdown)             ││
│                          │  └ ReplyEditor + [Reply] [Resolve] ┘│
└──────────────────────────┴────────────────────────────────────┘
```

Use `OnePixelSplitter(false, 0.4f)` — vertical split, proportion persisted via its `splitterProportionKey`.

### 9.2 Thread list

- `JBList<ReviewThread>` backed by a `CollectionListModel`, wrapped in `ScrollPaneFactory.createScrollPane`.
- Custom `ColoredListCellRenderer<ReviewThread>`:
    - Line 1: file basename (bold) + `:line` (grey) + resolved checkmark icon + outdated tag.
    - Line 2: `@author · <relative time> · N replies`, in `SimpleTextAttributes.GRAYED_ATTRIBUTES`.
    - Line 3: first 90 chars of root body, single line, ellipsized.
    - Use `DateFormatUtil.formatPrettyDateTime` for timestamps.
- `AllIcons.Actions.Checked` for resolved, `AllIcons.General.Balloon` for unresolved (or ship custom SVGs in `/icons`).
- Empty state: `StatusText` on the list — "No comments match the current filters." with a `Clear filters` link.

### 9.3 Filter toolbar

Build with `ActionToolbar` + custom `ComboBoxAction`s so it renders like native IDE toolbars, plus a `SearchTextField` for free text. Debounce the search field by 200 ms with an `Alarm`.

Persist the last filter/sort into `PrCommentsSettings` on every change.

**Acceptance**
- Selecting a filter updates the list synchronously with no flicker and no network call.
- Filter selections survive IDE restart.
- Tool window renders correctly in both Light and Dark themes (verify manually; use only `JBColor` / `UIUtil` colors, never raw `Color`).

---

## 10. Milestone 6 — Diff-hunk snapshot ("as it was when commented")

**Data source:** `comment.diffHunk` from GraphQL. This is a unified-diff fragment, typically the commented line plus ~4 lines of preceding context — exactly the 4–5 line snapshot required by F7.

**Tasks**
1. Parse the hunk header `@@ -a,b +c,d @@` to recover starting line numbers.
2. Strip the leading `+`/`-`/` ` marker from each line into plain source text, keeping a parallel list of `LineType` (ADDED / REMOVED / CONTEXT).
3. Render in a read-only editor so syntax highlighting works:
   ```kotlin
   val fileType = FileTypeManager.getInstance().getFileTypeByFileName(thread.path)
   val field = EditorTextField(
       EditorFactory.getInstance().createDocument(sourceText),
       project, fileType, /* isViewer = */ true, /* oneLineMode = */ false
   )
   field.addSettingsProvider { editor ->
       editor.settings.apply {
           isLineNumbersShown = true
           isLineMarkerAreaShown = false
           isFoldingOutlineShown = false
           additionalLinesCount = 0
           isCaretRowShown = false
       }
       // shift the gutter to show real file line numbers
       editor.gutter.let { /* use LineNumberConverter via EditorSettings if available */ }
       // tint added/removed rows
       markLines(editor, lineTypes)
   }
   ```
4. Tint rows using `editor.markupModel.addLineHighlighter(...)` with `DiffColors.DIFF_INSERTED` / `DIFF_DELETED` text attributes keys so it matches the IDE's diff theme.
5. Highlight the **commented line** itself with a distinct border/background and a `▸` gutter mark.
6. Set the preview's preferred height to `lineCount * lineHeight` capped at ~10 lines, then scroll.

**Fallback:** if `diffHunk` is null (rare, e.g. certain outdated threads), render a "snapshot unavailable" label with an "Open on GitHub" link. Do not fetch the blob just for this in MVP.

**Acceptance**
- The preview shows the same lines as the GitHub web UI for the same thread, with correct absolute line numbers in the gutter.
- Syntax highlighting is active (Kotlin/Java/TS files all colored).

---

## 11. Milestone 7 — Current state of the commented line

This is the highest-risk piece. Implement it in two stages.

### 11.1 Stage A — the easy path

1. Resolve the file: `project.guessProjectDir()` is wrong for multi-root; instead use the `GitRepository.root` found in Milestone 2 and do `repoRoot.findFileByRelativePath(thread.path)`.
2. If the file is missing → state `FILE_DELETED`, render "File no longer exists in the working tree."
3. Get the local HEAD sha: `repository.currentRevision`.
4. **If `localHeadSha == pullRequest.headRefOid` and `!thread.isOutdated`:** `thread.currentLine` is already correct for this content. Render lines `[line-2 .. line+2]` from the current `Document`. State = `EXACT`.

### 11.2 Stage B — drift correction

When the local checkout has moved past the PR head (extra commits, rebase, local edits):

1. Fetch the file content at the PR head revision using Git4Idea, avoiding a network round-trip when the object is local:
   ```kotlin
   val bytes = GitFileUtils.getFileContent(project, repoRoot, prHeadOid, thread.path)
   ```
   If it throws (object not fetched), fall back to REST:
   `GET /repos/{o}/{n}/contents/{path}?ref={prHeadOid}` and base64-decode.
2. Compare old content to current document text:
   ```kotlin
   val fragments = ComparisonManager.getInstance().compareLines(
       oldText, newText, ComparisonPolicy.IGNORE_WHITESPACES, DumbProgressIndicator.INSTANCE
   )
   ```
3. Translate the line: walk `fragments`; for a target line `L` (0-based) in the old text —
    - if `L` falls **before** all fragments or **between** fragments, offset it by the cumulative `(endLine2 - startLine2) - (endLine1 - startLine1)` delta of preceding fragments → state = `SHIFTED`.
    - if `L` falls **inside** a fragment's `startLine1..endLine1` range, the line was modified → map to `fragment.startLine2` and set state = `MODIFIED`.
    - if the fragment deleted the range entirely (`endLine2 == startLine2`) → state = `DELETED`.
4. Cache the mapping per `(path, prHeadOid, documentModificationStamp)`; invalidate on document change via a `DocumentListener` or simply on refresh.

### 11.3 Rendering

Render a second `EditorTextField` preview identical in style to §10 showing `[mappedLine-2 .. mappedLine+2]` from the **current document**, with a status chip above it:

| State | Chip |
|---|---|
| `EXACT` | `Current · line 42` (grey) |
| `SHIFTED` | `Moved · line 42 → 57` (blue) |
| `MODIFIED` | `Changed since comment · line 57` (orange) |
| `DELETED` | `Line removed` (red) |
| `FILE_DELETED` | `File deleted` (red) |

### 11.4 Navigation (F9)

```kotlin
OpenFileDescriptor(project, virtualFile, mappedLine, 0).navigate(true)
```
Bind to double-click on the list row and to `Enter`. If state is `DELETED`/`FILE_DELETED`, disable navigation and offer "Open on GitHub" instead.

**Acceptance**
- With the branch checked out at PR head, all threads report `EXACT` and navigation lands on the right line.
- After inserting 10 blank lines above a commented line and saving, the thread reports `SHIFTED` and navigation still lands on the right line.
- After editing the commented line itself, the thread reports `MODIFIED`.
- After deleting the commented line, the thread reports `DELETED` and does not throw.
- Line mapping for a 5000-line file completes in <100 ms and never runs on the EDT.

---

## 12. Milestone 8 — Reply & resolve

### 12.1 Reply editor

- Use a multi-line `EditorTextField` with `Markdown` file type if the Markdown plugin is present, else plain text. Detect via `FileTypeManager.getInstance().findFileTypeByName("Markdown")` — do **not** add a hard `<depends>` on the Markdown plugin.
- Submit shortcut: `Ctrl/Cmd+Enter`. Register with a `DumbAwareAction` on the component, not a global keymap.
- Disable the Reply button when the text is blank or a request is in flight; show an inline spinner.

### 12.2 Optimistic update flow

1. Append a provisional `ReviewComment` (author = viewer login, body = typed text, `createdAt = now`) to the in-memory thread and repaint immediately.
2. Fire the REST call on `Dispatchers.IO`.
3. On success, replace the provisional comment with the server response (gets the real IDs and timestamps).
4. On failure, remove the provisional comment, restore the text into the editor so nothing is lost, and show an error `Notification` in the "PR Comments" group with a Retry action.

Apply the identical pattern to resolve/unresolve: flip `isResolved` locally, call the mutation, revert on failure.

### 12.3 Resolve UX

- Primary button in the detail pane header: `Resolve conversation` / `Unresolve conversation`.
- Also expose as `ToggleResolveAction` in the list's context menu and bound to a keystroke, so a reviewer can burn through threads without touching the mouse.
- Offer a `Reply and resolve` combined button — this is the single highest-value interaction in the whole plugin.
- After resolving, if the active filter is `Unresolved`, the row disappears; **auto-select the next row** so the user keeps flowing.

**Acceptance**
- A reply posted from the IDE appears on github.com under the correct thread.
- Resolve from the IDE flips the thread state on github.com.
- Killing the network mid-reply restores the draft text and shows a retryable error.

---

## 13. Milestone 9 — Service, caching, refresh

### 13.1 `PrCommentsService`

```kotlin
@Service(Service.Level.PROJECT)
class PrCommentsService(private val project: Project, private val cs: CoroutineScope) {
    private val _state = MutableStateFlow<ViewState>(ViewState.Idle)
    val state: StateFlow<ViewState> = _state.asStateFlow()

    fun refresh(force: Boolean = false) { cs.launch { … } }
}
```
- Inject the coroutine scope via the constructor (platform-provided) — **never** create a `GlobalScope` or a raw `CoroutineScope`.
- `ViewState` sealed interface: `Idle | NoRepo | NoPr | Loading | Loaded(prRef, threads, viewerLogin, fetchedAt) | Error(GitHubError)`.
- UI collects the flow in the tool window's scope and re-renders. Every `collect` that touches Swing must be on `Dispatchers.EDT` (or `Dispatchers.Main` with `ModalityState`).

### 13.2 Refresh triggers

- Manual: toolbar `RefreshAction` (also bind `F5` locally).
- Timer: `refreshIntervalSeconds` from settings, only while the tool window is visible (`ToolWindowManagerListener.stateChanged` → start/stop).
- On tool window activation if data is older than the interval.
- On branch switch: subscribe to `GitRepository.GIT_REPO_CHANGE` on the message bus, re-run PR detection.

### 13.3 Merge strategy on refresh

Do **not** blow away the model. Diff old vs new by thread `nodeId`:
- Preserve the current selection by `nodeId`.
- Preserve scroll position.
- Preserve any unsent reply draft, keyed by `nodeId` (drafts survive refresh; this matters a lot in practice).
- Badge newly-arrived threads with a subtle "new" marker until the user views them.

### 13.4 Conditional requests

GraphQL doesn't support ETags. Instead, keep a cheap guard: fetch `pullRequest { updatedAt }` first; skip the full thread fetch if it hasn't changed since `fetchedAt`. Saves rate limit on the auto-refresh timer.

**Acceptance**
- Auto-refresh does not steal selection, scroll position, or an in-progress reply draft.
- Switching git branches re-detects the PR within a few seconds.
- Closing the project cancels all in-flight coroutines (verify: no `Already disposed` exceptions in `idea.log`).

---

## 14. Platform guardrails (apply throughout)

1. **Threading**
    - Swing/UI mutations: EDT only (`Dispatchers.EDT` / `ApplicationManager.getApplication().invokeLater`).
    - Network/Git: `Dispatchers.IO` only.
    - Reading VFS/Document/PSI: inside `ReadAction` (`readAction { }` from coroutine helpers).
    - Writing to a `Document`: `WriteCommandAction` on EDT. (MVP shouldn't need this.)
2. **Disposal:** every listener, `Alarm`, and `EditorTextField`-created editor must be tied to a `Disposable` (the tool window content). Release editors with `EditorFactory.getInstance().releaseEditor(editor)` or leaks will show as `EditorImpl` retained in the IDE's leak checker.
3. **Dumb mode:** all actions should be `DumbAware` — nothing here needs indexes.
4. **No blocking on EDT:** never `runBlocking` inside a Swing listener.
5. **i18n:** all user-visible strings in `PrCommentsBundle.properties`.
6. **Logging:** `thisLogger()`; never log the PAT, never log full response bodies at INFO.
7. **Icons:** ship 16×16 SVG with light/dark variants; register via `IconLoader.getIcon`.
8. **Exceptions:** catch `ProcessCanceledException` and rethrow it — never swallow it.

---

## 15. Testing

| Layer | Approach |
|---|---|
| Filters/sorting | Plain JUnit5, no fixture — pure functions over a fixture list |
| Diff-hunk parser | JUnit5 with recorded `diffHunk` strings covering: pure context, added lines, removed lines, multi-hunk |
| `LineMapper` | JUnit5 with before/after text pairs covering the five states in §11.3 |
| URL parsing | JUnit5 table test: ssh, https, ssh-alias, no `.git`, enterprise host |
| GraphQL DTO deserialization | JUnit5 against recorded JSON fixtures in `src/test/resources/fixtures/` |
| Tool window wiring | `BasePlatformTestCase` — assert the factory creates content without throwing |
| End-to-end | Manual checklist in `TESTING.md` against a real scratch PR with: 1 unresolved thread, 1 resolved thread, 1 outdated thread, 1 multi-reply thread |

Record real API responses once (with the token redacted) into fixtures; do not hit the network in CI.

---

## 16. Build, run, distribute

```bash
./gradlew runIde          # sandbox IDE with the plugin
./gradlew buildPlugin     # -> build/distributions/pr-comments-0.1.0.zip
./gradlew verifyPlugin    # plugin verifier against target IDE range
```

- Install locally via *Settings → Plugins → ⚙ → Install Plugin from Disk…*
- For JetBrains Marketplace later: fill `<description>` and `<change-notes>` in `plugin.xml`, add a 40×40 and 80×80 `pluginIcon.svg`, then `./gradlew publishPlugin` with a marketplace token.
- Add a GitHub Actions workflow running `verifyPlugin` + tests on PRs. **Pin all actions to a full commit SHA and set `permissions: contents: read` at the workflow level.**

---

## 17. Risk register (agent maintains `RISKS.md`)

| Risk | Mitigation |
|---|---|
| `diffHunk` truncated or absent for outdated threads | Fall back to "snapshot unavailable" + GitHub link; do not crash |
| GraphQL schema field renames | Pin `X-GitHub-Api-Version`; fail with a clear message naming the missing field |
| Rate limits on aggressive auto-refresh | Default 120 s, `updatedAt` pre-check, respect `x-ratelimit-reset` |
| Line mapping wrong on rebase-heavy branches | Show the state chip honestly; never silently navigate to a wrong line |
| Platform API drift across IDE versions | No upper `until-build`, run `verifyPlugin` against 3 recent majors in CI |
| Multi-root / monorepo path mismatch | Always resolve paths relative to the detected `GitRepository.root`, never `project.basePath` |
| Bundled dependency conflicts | Ship only `kotlinx-serialization`; exclude coroutines and stdlib |

---

## 18. Definition of done (MVP)

- [ ] F1–F13 implemented and manually verified against a real PR with ≥15 threads across ≥4 files
- [ ] Zero EDT-blocking assertions in `idea.log` during a full session
- [ ] Zero leaked editors/disposables on project close
- [ ] `verifyPlugin` clean
- [ ] Unit tests green; filter/mapper coverage ≥80%
- [ ] Works in IntelliJ IDEA, Android Studio, and one other JetBrains IDE (WebStorm) — all bundle Git4Idea
- [ ] Light and Dark themes both render correctly
- [ ] README with screenshots and PAT setup instructions