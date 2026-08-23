package com.gyanoba.prcomments.vcs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class RemoteUrlParserTest {

    @ParameterizedTest(name = "{0} -> {1}/{2}/{3}")
    @CsvSource(
        // ssh, scp-like
        "git@github.com:owner/name.git,          github.com,      owner, name",
        "git@github.com:owner/name,              github.com,      owner, name",
        // ssh alias from ~/.ssh/config
        "git@github-work:owner/name.git,         github-work,     owner, name",
        // explicit ssh scheme, with and without a port
        "ssh://git@github.com/owner/name.git,    github.com,      owner, name",
        "ssh://git@github.com:22/owner/name.git, github.com,      owner, name",
        // https, with and without credentials and the .git suffix
        "https://github.com/owner/name.git,      github.com,      owner, name",
        "https://github.com/owner/name,          github.com,      owner, name",
        "https://user@github.com/owner/name.git, github.com,      owner, name",
        "https://github.com/owner/name/,         github.com,      owner, name",
        // git protocol
        "git://github.com/owner/name.git,        github.com,      owner, name",
        // enterprise host
        "https://git.example.com/owner/name.git, git.example.com, owner, name",
        "git@git.example.com:owner/name.git,     git.example.com, owner, name",
        // dots and dashes in the repo name survive; only a trailing .git is stripped
        "git@github.com:my-org/my.repo.git,      github.com,      my-org, my.repo",
    )
    fun `parses every remote URL form`(url: String, host: String, owner: String, name: String) {
        val parsed = RemoteUrlParser.parse(url)
        assertEquals(host, parsed?.host, url)
        assertEquals(owner, parsed?.owner, url)
        assertEquals(name, parsed?.name, url)
    }

    @Test
    fun `whitespace is tolerated`() {
        val parsed = RemoteUrlParser.parse("  https://github.com/owner/name.git  ")
        assertEquals("owner/name", parsed?.slug)
    }

    @Test
    fun `nested paths fall back to the last two segments`() {
        val parsed = RemoteUrlParser.parse("https://git.example.com/scm/team/owner/name.git")
        assertEquals("owner", parsed?.owner)
        assertEquals("name", parsed?.name)
    }

    @Test
    fun `rejects things that are not repository URLs`() {
        assertNull(RemoteUrlParser.parse(""))
        assertNull(RemoteUrlParser.parse("   "))
        assertNull(RemoteUrlParser.parse("https://github.com/owner"))
        assertNull(RemoteUrlParser.parse("not a url"))
        assertNull(RemoteUrlParser.parse("/local/path/repo.git"))
    }

    @Test
    fun `host matching accepts exact hosts and ssh aliases`() {
        assertTrue(RemoteUrlParser.hostMatches("github.com", "github.com"))
        assertTrue(RemoteUrlParser.hostMatches("GitHub.com", "github.com"))
        assertTrue(RemoteUrlParser.hostMatches("github-work", "github.com"))
        assertTrue(RemoteUrlParser.hostMatches("github", "github.com"))
        assertTrue(RemoteUrlParser.hostMatches("git.example.com", "git.example.com"))
    }

    @Test
    fun `host matching rejects unrelated hosts`() {
        assertFalse(RemoteUrlParser.hostMatches("gitlab.com", "github.com"))
        assertFalse(RemoteUrlParser.hostMatches("bitbucket.org", "github.com"))
        assertFalse(RemoteUrlParser.hostMatches("github.com", "git.example.com"))
    }
}
