# PR Comments

A JetBrains IDE plugin that puts every GitHub pull request review comment in a tool window, next to
the code it is about — including what that code looked like when the comment was written and what it
looks like in your working tree right now.

> **Screenshots are still to be captured.** Run `./gradlew runIde` against a real PR, grab the tool
> window in both Light and Dark, and add them to `docs/` before publishing to the Marketplace.

---

## What it does

| | |
|---|---|
| **Finds your PR** | Detects the GitHub repository from the git remote and the pull request from the checked-out branch. Pin a PR number by hand when the branch has none, or several. |
| **Lists every review thread** | File, line, author, relative timestamp, resolved badge, reply count and the first line of the comment. |
| **Filters** | Resolved / unresolved · replied / not replied / replied by me / awaiting my reply · outdated · author (multi-select) · path · free text. All local — filtering never hits the network. |
| **Sorts** | Created, last activity, file path or line, ascending or descending, with a stable secondary order. |
| **Shows the diff hunk** | The ~5 lines as they were when the comment was written, syntax highlighted, with real file line numbers and the IDE's own diff colours. |
| **Shows the line now** | The same line in your working tree, with drift correction and an honest state chip: `Current`, `Moved 42 → 57`, `Changed since comment`, `Line removed`, `File deleted`. |
| **Navigates** | Double-click or Enter jumps the editor to the drift-corrected line. When the line is gone, it opens GitHub instead of guessing. |
| **Replies and resolves** | Inline reply box with `Cmd/Ctrl+Enter`, a **Reply and Resolve** button, and optimistic updates that roll back with a Retry action if the request fails. |
| **Refreshes** | Manually (toolbar or F5) and on a timer while the tool window is visible, without stealing your selection, scroll position or unsent draft. |

## Requirements

- IntelliJ IDEA, Android Studio, WebStorm or any JetBrains IDE built on platform **253** (2025.3) or
  later. The bundled Git plugin (Git4Idea) is required and ships with all of them.
- A GitHub personal access token.

## Installing

From a build:

```bash
./gradlew buildPlugin
# -> build/distributions/github-enhanced-<version>.zip
```

Then **Settings → Plugins → ⚙ → Install Plugin from Disk…** and pick that ZIP.

## Setting up the token

**Settings → Tools → PR Comments**

1. Leave **GitHub host** as `github.com`, or set your GitHub Enterprise Server hostname. The REST and
   GraphQL URLs are derived from it; override them only if you sit behind a proxy.
2. Paste a personal access token.
3. Click **Test connection** — it should report `Connected as <your login>`.

### Required scopes

| Token type | Scopes |
|---|---|
| Classic PAT | `repo` |
| Fine-grained PAT | *Pull requests: Read & Write*, *Contents: Read*, *Metadata: Read* |

*Read & Write* on pull requests is needed to post replies and resolve threads; *Contents: Read* lets
the plugin fetch a file at the PR head commit when that object is not in your local clone.

The token is stored in the IDE's [`PasswordSafe`](https://plugins.jetbrains.com/docs/intellij/persisting-sensitive-data.html)
— the system keychain on macOS, the credential store elsewhere. It is never written to `.idea/` or
any file in the project, and never logged.

## Using it

Open the **PR Comments** tool window on the right rail.

- **Header** — `owner/name #123 — Title`, with refresh, open-on-GitHub, set-PR-number and settings.
- **Filter bar** — combo boxes for resolution, replies, author and sort; a free-text search and a path
  filter, both debounced.
- **Left** — the thread list. Double-click or Enter navigates.
- **Right** — the thread: diff-hunk snapshot, current state of that line, every comment rendered as
  Markdown, and the reply box.

Right-click a row for **Resolve conversation** and **Open on GitHub**.

### Reading the state chip

| Chip | Meaning |
|---|---|
| `Current · line 42` | The line is exactly where the comment left it. |
| `Moved · line 42 → 57` | Unchanged content, pushed down or up by edits above it. |
| `Changed since comment · line 57` | The line itself has been edited since the comment. |
| `Line removed` | The line is gone. Navigation is disabled. |
| `File deleted` | The file is gone from the working tree. |

The chip never guesses. If the plugin cannot establish where a line went, it says so rather than
navigating you somewhere plausible but wrong.

## Not in this version

Creating new review comments on arbitrary lines, submitting or approving reviews, merging, CI status,
gutter icons and editor inlays, and viewing several PRs at once. GitHub Enterprise Server is designed
for (configurable host and URLs) but untested.

## Developing

```bash
./gradlew runIde          # sandbox IDE with the plugin loaded
./gradlew test            # unit tests — no network
./gradlew verifyPlugin    # binary compatibility against the target IDE
./gradlew buildPlugin     # distributable ZIP
```

- `TESTING.md` — the manual end-to-end checklist against a real PR.
- `RISKS.md` — the risk register, and every deviation from the implementation plan with its reasoning.

### Layout

```
src/main/kotlin/com/gyanoba/prcomments/
├── model/      ReviewThread, ReviewComment, filters and sorting  (pure Kotlin)
├── github/     HTTP client, GraphQL documents, DTOs, high-level API
├── vcs/        remote-URL parsing, PR resolution, diff-hunk parsing, line drift correction
├── service/    PrCommentsService (state, refresh, mutations), settings
├── ui/         tool window, list, filters, detail pane, previews, Markdown renderer
├── settings/   configurable page and the PasswordSafe wrapper
└── actions/    refresh, toggle resolve, open in browser, set PR number
```

Threading rules the code holds itself to: GitHub and git calls on `Dispatchers.IO`, VFS and Document
reads inside a read action, Swing on `Dispatchers.EDT`, and nothing blocking on the EDT.
