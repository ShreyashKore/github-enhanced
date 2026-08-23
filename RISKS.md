# Risk register

Maintained by the implementation. Each entry states the risk, what the code actually does about it,
and what would have to change to remove the risk entirely.

## Deviations from the implementation plan

| Plan says | What was built | Why |
|---|---|---|
| Dev target IntelliJ IDEA Community 2024.3, `sinceBuild = "243"` | IntelliJ IDEA 2025.3.5 (build 253), `sinceBuild = "253"` | The scaffolded template already pinned 2025.3.5 and the plan explicitly says to verify versions before writing build files. Declaring 243 while compiling against 253 is the classic way to ship a plugin that throws `NoSuchMethodError` on older IDEs, so the lower bound is set to what the code is actually compiled and verified against. To genuinely support 243, switch `intellijIdea("2025.3.5")` to a 2024.3 target, re-run `verifyPlugin`, and only then lower `sinceBuild`. |
| §11.1 "Stage A — the easy path": short-circuit to `EXACT` when `localHeadSha == pullRequest.headRefOid && !isOutdated` | `LineMapper` always runs the Stage B comparison | Stage A's condition is not sufficient: the working tree can hold uncommitted edits, and unsaved editor changes, while HEAD still equals the PR head. Stage B produces `EXACT` for itself when nothing moved (an empty fragment list), so the fast path bought a class of silently-wrong line numbers for no behavioural gain. Costs one `git show` per file, cached by `(path, revision)`. |
| §9.2 "custom `ColoredListCellRenderer`" | A `JPanel` of three `SimpleColoredComponent`s implementing `ListCellRenderer` | `ColoredListCellRenderer` extends `SimpleColoredComponent`, which renders a single line. The three-line row in the plan needs three of them stacked. Still platform components, still theme-aware. |
| §15 "Plain JUnit5" for every layer | JUnit 5 for the pure tests; the platform test runs as JUnit 4 through the vintage engine | `BasePlatformTestCase` is a JUnit 3/4 base class. The `test` task uses `useJUnitPlatform()` with `junit-vintage-engine` on the runtime classpath so both styles run in one pass. |

## Standing risks

| Risk | Mitigation in the code |
|---|---|
| `diffHunk` truncated or absent (common for outdated threads) | `DiffHunkParser.parse` returns null rather than throwing; the detail pane shows "Diff snapshot unavailable" and the thread stays usable via **Open on GitHub**. No blob is fetched just to fill this in. |
| GraphQL schema field renames | `X-GitHub-Api-Version: 2022-11-28` is pinned on every request. `GitHubClient.graphQl` inspects the `errors` array even on HTTP 200 and raises `GitHubError.GraphQl` carrying GitHub's own message, which names the offending field. Queries live as plain strings in `GraphQlQueries` so they can be diffed against the schema by eye. |
| Rate limits from aggressive auto-refresh | Default interval 120 s, clamped to 30–3600 s. The timer only runs while the tool window is visible. Each timed refresh first issues the tiny `pullRequest { updatedAt }` query and skips the full fetch when nothing changed. `403`/`429` responses are mapped to `GitHubError.Forbidden` carrying `x-ratelimit-reset`, shown as "resets at 14:32". |
| Line mapping wrong on rebase-heavy branches | The state chip never lies: `EXACT` / `Moved a → b` / `Changed since comment` / `Line removed` / `File deleted` / unknown. **Go to Line** is disabled for `DELETED`, `FILE_DELETED` and `UNKNOWN`; double-click on such a thread opens GitHub instead of jumping somewhere plausible-looking. |
| Platform API drift across IDE versions | No upper `until-build`. `verifyPlugin` is wired into CI and currently passes against three recent majors — see below. |
| Multi-root / monorepo path mismatch | Every path is resolved against `DetectedRepo.root` (a `GitRepository.root`), never `project.basePath` or `guessProjectDir()`. In a multi-root project the repository containing the currently open file wins. |
| Bundled dependency conflicts | Only `kotlinx-serialization-json` and `-core` ship in `lib/`. `kotlin-stdlib`, `org.jetbrains:annotations` and `kotlinx-coroutines` are excluded from `runtimeClasspath` and `testRuntimeClasspath`; verify with `unzip -l build/distributions/*.zip`. |
| Our `kotlinx-serialization` copy shadowing the platform's, or vice versa | The version is pinned to **1.8.1**, exactly what IntelliJ Platform 2025.3 ships in `lib/module-intellij.libraries.kotlinx.serialization.*.jar`, so the plugin behaves identically whichever copy the classloader picks. Bump it only after checking `license/third-party-libraries.json` in the target platform. |
| A comment body injecting markup into the detail pane | `MarkdownRenderer` HTML-escapes before inserting a single tag, stashes code spans so nothing rewrites them, and allows only `http`/`https`/`mailto`/fragment link targets — `javascript:` and `data:` URLs degrade to plain text. Covered by tests. |
| A thread with more than 100 comments | The GraphQL query takes the first 100 and the extra page is not fetched (`comments.pageInfo` is parsed but unused). Rare in practice; the thread still renders, just without its tail. Fetching the remainder lazily when the thread is opened is the follow-up. |
| A PR with more than 1000 review threads | `GitHubApi.MAX_THREAD_PAGES = 20` (20 × 50) is a runaway guard; hitting it logs a warning and returns what was fetched. |

## Plugin Verifier status

`./gradlew verifyPlugin` reports **Compatible** against `IU-253.33813.55`, `IU-261.27258.48` and
`IU-262.10315.19` — three recent majors — with **no internal API usages**.

What it still reports, and why it is left alone:

- **4 deprecated + 6 experimental usages, all on `ToolWindowFactory`** (`isApplicable`,
  `isDoNotActivateOnStart`, `getIcon`, `getAnchor`, `manage`). `PrCommentsToolWindowFactory` does not
  reference any of them. `ToolWindowFactory` is a *Kotlin* interface, so the compiler emits bridge
  overrides for its default methods in every implementing class; the verifier sees the bridges. There
  is no way to suppress this short of writing the factory in Java, and it affects every Kotlin plugin
  that implements the interface.
- **`AnAction.shortcutSet` was an internal-API usage and has been removed.** F5 on the refresh action
  is now bound with `registerCustomShortcutSet(shortcutSet, component, disposable)`, which is public
  API and correctly scopes the shortcut to the tool window rather than the global keymap.

## API status

Everything the plugin touches directly is open API:
`ToolWindowFactory`, `PersistentStateComponent`, `PasswordSafe`, `EditorTextField`, `ComparisonManager`,
`git4idea`'s `GitRepositoryManager` / `GitFileUtils`, `UiDataProvider`, Kotlin UI DSL v2.

Two things worth watching:

- **`com.intellij.openapi.diff.DiffColors`** (`DIFF_INSERTED` / `DIFF_DELETED`) lives in a package whose
  name suggests the retired old-diff API, but the constants are the current text-attribute keys the
  IDE's own diff viewer uses. If they ever move, the previews lose their tint and nothing else breaks.
- **The bundled GitHub plugin's account manager is deliberately not used** (§2). The plugin keeps its
  own PAT in `PasswordSafe`, which is why the token is per-host and independent of the IDE's GitHub
  settings.
