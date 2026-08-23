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
| **Multi-selects** | Ctrl/Cmd-click or Shift-click rows to select several threads at once, then resolve, unresolve, open or copy all of them in one action. |
| **Copies for AI** | Turns the selected thread(s) into a compact prompt — file, line and full comment thread, no markup or metadata to burn tokens — ready to paste into an AI coding assistant. |
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
— the system keychain on macOS, the credential store elsewhere, keyed by host so github.com and an
Enterprise instance each keep their own. It is never written to `.idea/` or any file in the project,
and never logged.

On the wire it goes to one place and one place only: the configured `https` endpoint. Plain `http`
is rejected outright (loopback aside, for local test servers), and a redirect to any other origin is
refused rather than followed, so the `Authorization` header is never replayed to a host that is not
the one you configured.

## Using it

Open the **PR Comments** tool window on the right rail.

- **Header** — `owner/name #123 — Title`, with refresh, open-on-GitHub, set-PR-number and settings.
- **Filter bar** — combo boxes for resolution, replies, author and sort; a free-text search and a path
  filter, both debounced.
- **Left** — the thread list. Double-click or Enter navigates.
- **Right** — the thread: diff-hunk snapshot, current state of that line, every comment rendered as
  Markdown, and the reply box.

Right-click a row for **Resolve conversation** and **Open on GitHub**.

### Working with several threads at once

Ctrl/Cmd-click or Shift-click rows in the list to select more than one. Right-click the selection
(or press `Cmd/Ctrl+C`) for:

| Action | What it does |
|---|---|
| **Resolve N Conversations** / **Unresolve N Conversations** | Bulk-toggles every thread in the selection that isn't already in that state. |
| **Open N on GitHub** | Opens each selected thread's comment in its own browser tab. |
| **Copy Links** | Copies the GitHub URL of each selected thread, one per line. |
| **Copy for AI** (`Cmd/Ctrl+C`) | Copies the selection as a compact prompt — file, line and the full comment thread for each — ready to hand to an AI coding assistant to fix. |

Double-click, Enter and the single-thread context menu items all still work the normal way when
exactly one row is selected.

#### The "Copy for AI" format

```
Fix these 2 GitHub PR review comments:

src/main/kotlin/Foo.kt:42
- This will NPE if `user` is null — guard it.
  ↳ Good catch, added a null check in 9f2a1c3.

src/main/kotlin/Bar.kt:10
- Rename this to something that doesn't shadow the outer `result`.
```

One block per thread: `path:line`, then the root comment (`-`) and any replies (`↳`), each verbatim
(Markdown, code spans and all) — just whitespace-collapsed and indented so a multi-line comment stays
visually attached to its bullet.

Deliberately **left out**: the diff hunk, comment authors, and GitHub links. An AI assistant working
in the same repo can already open `path:line` and run `git blame` itself — pasting that context again
would just spend tokens telling it something it can look up in one tool call. What it *can't* get any
other way is where to look and what was actually asked for, including anything a reply narrowed or
changed — that's exactly what's kept. The formatter lives in
[`AiPromptFormatter.kt`](src/main/kotlin/com/gyanoba/prcomments/actions/AiPromptFormatter.kt); keep
this section in sync if you change it.

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
└── actions/    refresh, resolve, open in browser, set PR number, multi-select bulk actions,
                AI-prompt formatting
```

Threading rules the code holds itself to: GitHub and git calls on `Dispatchers.IO`, VFS and Document
reads inside a read action, Swing on `Dispatchers.EDT`, and nothing blocking on the EDT.
