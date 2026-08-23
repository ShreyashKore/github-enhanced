<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# PR Comments Changelog

## [Unreleased]

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

### Security

- The `Authorization` header is no longer replayed across a redirect. Hops are followed only when the
  target is the same origin (scheme, host, port) as the request; anything else is refused, so a
  `Location` pointing off-host cannot collect the token.
- Plain `http` endpoints are rejected before the token is read — loopback excepted — instead of only
  warning in settings, where the URL fields now block on a non-HTTPS value.
- Changing the host in settings no longer re-saves the previous host's token under the new host, or
  deletes the previous host's entry. Only an edited token is written, and the field reloads from the
  new host's own entry.
- `TokenStore` normalizes the host key, so casing or a trailing slash can no longer orphan a stored
  token, and settings no longer reads `PasswordSafe` from the EDT on every `isModified` poll.
