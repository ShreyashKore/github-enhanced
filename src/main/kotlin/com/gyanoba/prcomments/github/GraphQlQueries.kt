package com.gyanoba.prcomments.github

/**
 * GraphQL documents used by the plugin. Kept as plain strings so they can be diffed against the
 * GitHub schema by eye when a field is renamed (see RISKS.md).
 */
object GraphQlQueries {

    /** Threads on a PR, one page at a time. `cursor` is null for the first page. */
    const val FETCH_THREADS: String = """
query Threads(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!, ${'$'}cursor: String) {
  viewer { login }
  repository(owner: ${'$'}owner, name: ${'$'}name) {
    pullRequest(number: ${'$'}number) {
      number
      title
      url
      updatedAt
      headRefOid
      baseRefOid
      reviewThreads(first: 50, after: ${'$'}cursor) {
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
            pageInfo { hasNextPage endCursor }
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
"""

    /** Cheap guard for the auto-refresh timer (§13.4): GraphQL has no ETags. */
    const val FETCH_PR_UPDATED_AT: String = """
query PrUpdatedAt(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!) {
  repository(owner: ${'$'}owner, name: ${'$'}name) {
    pullRequest(number: ${'$'}number) { number updatedAt }
  }
}
"""

    /** Open pull requests associated with a branch. */
    const val FIND_PRS_FOR_BRANCH: String = """
query PrsForBranch(${'$'}owner: String!, ${'$'}name: String!, ${'$'}branch: String!) {
  repository(owner: ${'$'}owner, name: ${'$'}name) {
    ref(qualifiedName: ${'$'}branch) {
      associatedPullRequests(first: 5, states: OPEN, orderBy: { field: UPDATED_AT, direction: DESC }) {
        nodes { number title headRefOid }
      }
    }
  }
}
"""

    const val RESOLVE_THREAD: String = """
mutation Resolve(${'$'}threadId: ID!) {
  resolveReviewThread(input: { threadId: ${'$'}threadId }) { thread { id isResolved } }
}
"""

    const val UNRESOLVE_THREAD: String = """
mutation Unresolve(${'$'}threadId: ID!) {
  unresolveReviewThread(input: { threadId: ${'$'}threadId }) { thread { id isResolved } }
}
"""
}
