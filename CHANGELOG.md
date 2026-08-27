<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# PR Comments Changelog

## Unreleased

## 1.0.0 - 2026-08-27

### Added

- Tool window listing every GitHub pull request review thread for the checked-out branch, with the
  repository and PR detected from the git remote and branch, and a manual PR-number override that is
  remembered per branch.
- Filters for resolved/unresolved, replied/not replied/replied by me/awaiting my reply, outdated,
  author (multi-select), file path and free text — all applied locally, with debounced text input.
- Sorting by created date, last activity, file path or line, ascending or descending, with a stable
  path-then-line secondary order.
- Detail pane showing the full thread with Markdown-rendered comments, the diff-hunk snapshot as the
  code looked when the comment was written, and the current state of that line in the working tree.
- Drift-corrected line mapping with an honest state chip (`Current`, `Moved`, `Changed since comment`,
  `Line removed`, `File deleted`) and navigation that refuses to guess when the line is gone.
- Inline replies (`Cmd/Ctrl+Enter`), **Reply and Resolve**, and resolve/unresolve from the detail pane,
  the list context menu or a keystroke — all optimistic, with rollback and a Retry action on failure.
- Manual refresh (toolbar, F5) and auto-refresh on a timer while the tool window is visible, preserving
  selection, scroll position and unsent reply drafts, and guarded by a cheap `updatedAt` pre-check.
- Settings page under Tools with the GitHub host, optional REST/GraphQL URL overrides, a personal
  access token stored in `PasswordSafe`, a **Test connection** button, and the auto-refresh interval.
