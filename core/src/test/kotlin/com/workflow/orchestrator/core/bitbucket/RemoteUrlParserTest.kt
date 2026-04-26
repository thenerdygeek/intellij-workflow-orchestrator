package com.workflow.orchestrator.core.bitbucket

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RemoteUrlParserTest {

    private fun success(url: String): ParsedBitbucketRemote {
        val result = RemoteUrlParser.parse(url)
        assertTrue(result is RemoteUrlParseResult.Success, "Expected Success for '$url', got $result")
        return (result as RemoteUrlParseResult.Success).parsed
    }

    @Test
    fun `https project repo parses correctly`() {
        val parsed = success("https://bitbucket.example.com/scm/PROJ/repo.git")
        assertEquals("PROJ", parsed.projectKey)
        assertEquals("repo", parsed.repoSlug)
        assertEquals("bitbucket.example.com", parsed.host)
        assertEquals(ParsedBitbucketRemote.Scheme.HTTPS, parsed.scheme)
    }

    @Test
    fun `https with PAT strips userinfo`() {
        val parsed = success("https://mytoken@bitbucket.example.com/scm/PROJ/repo.git")
        assertEquals("PROJ", parsed.projectKey)
        assertEquals("repo", parsed.repoSlug)
        assertEquals("bitbucket.example.com", parsed.host)
        assertEquals(ParsedBitbucketRemote.Scheme.HTTPS, parsed.scheme)
    }

    @Test
    fun `ssh default port form`() {
        val parsed = success("ssh://git@bitbucket.example.com:7999/PROJ/repo.git")
        assertEquals("PROJ", parsed.projectKey)
        assertEquals("repo", parsed.repoSlug)
        assertEquals("bitbucket.example.com", parsed.host)
        assertEquals(ParsedBitbucketRemote.Scheme.SSH, parsed.scheme)
    }

    @Test
    fun `ssh scp-style form`() {
        val parsed = success("git@bitbucket.example.com:PROJ/repo.git")
        assertEquals("PROJ", parsed.projectKey)
        assertEquals("repo", parsed.repoSlug)
        assertEquals("bitbucket.example.com", parsed.host)
        assertEquals(ParsedBitbucketRemote.Scheme.SCP, parsed.scheme)
    }

    @Test
    fun `personal repo preserves tilde as project key`() {
        val parsed = success("https://bitbucket.example.com/scm/~johndoe/repo.git")
        assertEquals("~johndoe", parsed.projectKey)
        assertEquals("repo", parsed.repoSlug)
    }

    @Test
    fun `bitbucket dot org rejected as Cloud`() {
        val result = RemoteUrlParser.parse("https://bitbucket.org/myteam/repo.git")
        assertEquals(RemoteUrlParseResult.CloudNotSupported, result)
    }

    @Test
    fun `subdomain of bitbucket dot org rejected as Cloud`() {
        val result = RemoteUrlParser.parse("https://api.bitbucket.org/2.0/repositories/team/repo")
        assertEquals(RemoteUrlParseResult.CloudNotSupported, result)
    }

    @Test
    fun `scp-style bitbucket dot org rejected as Cloud`() {
        val result = RemoteUrlParser.parse("git@bitbucket.org:team/repo.git")
        assertEquals(RemoteUrlParseResult.CloudNotSupported, result)
    }

    @Test
    fun `unparseable garbage returns Unparseable`() {
        val result = RemoteUrlParser.parse("not-a-url-at-all!!")
        assertTrue(result is RemoteUrlParseResult.Unparseable, "Expected Unparseable, got $result")
    }

    @Test
    fun `empty string returns Unparseable`() {
        val result = RemoteUrlParser.parse("")
        assertTrue(result is RemoteUrlParseResult.Unparseable)
    }

    @Test
    fun `trailing dot-git stripped`() {
        val parsed = success("https://bitbucket.example.com/scm/PROJ/my-repo.git")
        assertEquals("my-repo", parsed.repoSlug)
    }

    @Test
    fun `trailing slash tolerated in scp form`() {
        val parsed = success("git@bitbucket.example.com:PROJ/repo/")
        assertEquals("PROJ", parsed.projectKey)
        assertEquals("repo", parsed.repoSlug)
    }

    @Test
    fun `https without scm prefix still parses last two path segments`() {
        // Some Bitbucket mirrors omit /scm/ prefix
        val parsed = success("https://bitbucket.example.com/PROJ/repo.git")
        assertEquals("PROJ", parsed.projectKey)
        assertEquals("repo", parsed.repoSlug)
    }

    @Test
    fun `ssh without port parses correctly`() {
        val parsed = success("ssh://git@bitbucket.example.com/PROJ/repo.git")
        assertEquals("PROJ", parsed.projectKey)
        assertEquals("repo", parsed.repoSlug)
        assertEquals(ParsedBitbucketRemote.Scheme.SSH, parsed.scheme)
    }

    @Test
    fun `isCloudHost returns true for exact bitbucket dot org`() {
        assertTrue(RemoteUrlParser.isCloudHost("bitbucket.org"))
        assertTrue(RemoteUrlParser.isCloudHost("BITBUCKET.ORG"))
    }

    @Test
    fun `isCloudHost returns true for subdomains`() {
        assertTrue(RemoteUrlParser.isCloudHost("api.bitbucket.org"))
    }

    @Test
    fun `parse rejects upper-case bitbucket dot org host end-to-end`() {
        val result = RemoteUrlParser.parse("https://BITBUCKET.ORG/workspace/repo.git")
        assertEquals(RemoteUrlParseResult.CloudNotSupported, result)
    }

    @Test
    fun `parse rejects bitbucket dot org subdomain end-to-end`() {
        val result = RemoteUrlParser.parse("https://api.bitbucket.org/workspace/repo.git")
        assertEquals(RemoteUrlParseResult.CloudNotSupported, result)
    }

    @Test
    fun `splitProjectSlug returns last two segments`() {
        val result = RemoteUrlParser.splitProjectSlug("extra/PROJ/repo")
        assertEquals("PROJ" to "repo", result)
    }

    @Test
    fun `splitProjectSlug returns null for single segment`() {
        val result = RemoteUrlParser.splitProjectSlug("onlyone")
        assertEquals(null, result)
    }
}
