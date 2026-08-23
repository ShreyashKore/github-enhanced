# Manual test checklist

Automated coverage lives in `src/test` and never touches the network. This checklist is the
end-to-end pass that needs a real GitHub PR (§15, §18).

```bash
./gradlew test          # unit tests
./gradlew verifyPlugin  # binary compatibility
./gradlew runIde        # sandbox IDE with the plugin loaded
```

## Scratch PR setup

Create one PR with at least:

- **≥ 15 review threads across ≥ 4 files** (needed for the §18 definition of done).
- 1 unresolved thread with no replies.
- 1 resolved thread.
- 1 outdated thread — leave a comment, then force-push a commit that rewrites that region.
- 1 thread with 3+ replies, at least one of them yours.
- 1 thread on a deleted file.
- 1 thread on a `LEFT`-side (removed) line.

## 1 — Authentication (M1)

- [ ] With no token configured, the tool window shows "A GitHub personal access token is required."
      and a **Configure Token…** link, not an error balloon.
- [ ] Settings | Tools | PR Comments accepts a PAT; **Test connection** reports `Connected as <login>`.
- [ ] A deliberately invalid token reports a readable 401 message, not a stack trace.
- [ ] `grep -ri <token-prefix> .idea/ ~/.../options/` finds nothing. The token is only in `PasswordSafe`.
- [ ] Restart the IDE: the token is still there.

## 2 — Repo & PR detection (M2)

- [ ] On a branch with one open PR, the header reads `owner/name #123 — <title>`.
- [ ] On a branch with no PR, the empty state offers **Set PR Number…** — no exception, no balloon.
- [ ] On a detached HEAD, the empty state says so.
- [ ] With two open PRs for the branch, a picker appears; choosing one loads it.
- [ ] A manually set PR number survives an IDE restart on the same branch.
- [ ] In a multi-root project, opening a file in the second repository switches the detected repo.

## 3 — Listing, filtering, sorting (M4, M5)

- [ ] Thread count matches the GitHub web UI for the same PR.
- [ ] Each row shows file name, line, author, relative time, resolved badge and reply count.
- [ ] Each filter narrows the list **without a network request** (watch the IDE's network activity).
- [ ] Filter changes are instant — no flicker, no spinner.
- [ ] The search field debounces: typing quickly does not re-filter per keystroke.
- [ ] The path field filters by substring, case-insensitively.
- [ ] Selecting two authors shows threads from either.
- [ ] Sort by created / last activity / file path / line, ascending and descending.
- [ ] Filter and sort selections survive an IDE restart.
- [ ] Clearing all filters via the empty-state link restores the full list.
- [ ] Switch the IDE between Light and Dark: every row, chip and preview stays legible.

## 4 — Diff-hunk snapshot (M6)

- [ ] The **When commented** preview shows the same lines as the GitHub web UI for that thread.
- [ ] Gutter line numbers are absolute file line numbers, not 1..n.
- [ ] Added lines are tinted green, removed lines red, matching the IDE's diff colours.
- [ ] The commented line is boxed and has a gutter arrow.
- [ ] Syntax highlighting is active — check a Kotlin, a Java and a TypeScript file.
- [ ] For the outdated thread with no `diffHunk`, "Diff snapshot unavailable" appears instead of a crash.

## 5 — Current state of the line (M7)

With the branch checked out at the PR head:

- [ ] Every thread reports `Current · line N`, and **Go to Line** lands on the right line.

Then, in a file with a commented line:

- [ ] Insert 10 blank lines above it and save → `Moved · line 42 → 52`; navigation still lands correctly.
- [ ] Edit the commented line itself → `Changed since comment · line N`.
- [ ] Delete the commented line → `Line removed`; **Go to Line** is disabled; double-click opens GitHub.
- [ ] Delete the whole file → `File deleted`; nothing throws.
- [ ] Do all of the above in a 5000-line file: the pane updates in well under a second and
      `idea.log` contains no `Slow operations are prohibited on EDT` assertion.

## 6 — Reply & resolve (M8)

- [ ] Reply from the IDE; the comment appears immediately (optimistic) and then settles.
- [ ] Reload github.com: the reply is on the correct thread.
- [ ] `Cmd/Ctrl+Enter` submits; the button is disabled while the text is blank or a request is in flight.
- [ ] **Reply and Resolve** posts and resolves in one go.
- [ ] Resolve from the IDE, then reload github.com: the thread is resolved there.
- [ ] Unresolve works the same way.
- [ ] With the **Unresolved** filter on, resolving a thread removes its row and selects the next one.
- [ ] Turn off Wi-Fi mid-reply: the draft comes back in the editor and an error notification with
      **Retry** appears. Turn Wi-Fi on, hit Retry: the reply posts.
- [ ] Turn off Wi-Fi and resolve: the checkmark flips back and an error notification appears.

## 7 — Refresh & lifecycle (M9)

- [ ] Manual refresh (toolbar button, and F5 with the tool window focused) re-fetches.
- [ ] Auto-refresh fires on the configured interval only while the tool window is visible.
- [ ] Auto-refresh does not steal the selection, the scroll position, or an unsent reply draft.
- [ ] Type a draft, let auto-refresh run, and the draft is still there.
- [ ] Switch git branches: the PR is re-detected within a few seconds.
- [ ] A thread that arrived since the last refresh is badged `new` until it is selected.
- [ ] Close the project with a refresh in flight: `idea.log` has no `Already disposed` exception.
- [ ] Close and reopen the project several times, then check the IDE's leak checker for retained
      `EditorImpl` instances — there should be none from this plugin.

## 8 — Cross-IDE (§18)

Repeat sections 2–6 in:

- [ ] IntelliJ IDEA
- [ ] Android Studio
- [ ] WebStorm
