package com.workflow.orchestrator.core.bitbucket

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Verifies the remote-priority ordering described in Phase D spec:
 * origin > upstream > bitbucket > first.
 *
 * These tests exercise [RemoteUrlParser] parsing of URLs that would come from
 * each named remote — the priority logic itself lives in RepoContextResolver
 * (which requires the IntelliJ platform and is tested via the integration path).
 * Here we assert that all URL forms that a prioritised remote could emit are
 * parseable correctly.
 */
class RemoteUrlParserMultiRemoteTest {

    @Test
    fun `origin HTTPS URL parses correctly`() {
        val result = RemoteUrlParser.parse("https://bitbucket.example.com/scm/PROJ/origin-repo.git")
        assertNotNull((result as? RemoteUrlParseResult.Success)?.parsed)
        assertEquals("PROJ", (result as RemoteUrlParseResult.Success).parsed.projectKey)
        assertEquals("origin-repo", result.parsed.repoSlug)
    }

    @Test
    fun `upstream SSH scp URL parses correctly`() {
        val result = RemoteUrlParser.parse("git@bitbucket.example.com:TEAM/upstream-service.git")
        assertNotNull((result as? RemoteUrlParseResult.Success)?.parsed)
        val parsed = (result as RemoteUrlParseResult.Success).parsed
        assertEquals("TEAM", parsed.projectKey)
        assertEquals("upstream-service", parsed.repoSlug)
        assertEquals(ParsedBitbucketRemote.Scheme.SCP, parsed.scheme)
    }

    @Test
    fun `named bitbucket remote SSH port URL parses correctly`() {
        val result = RemoteUrlParser.parse("ssh://git@bitbucket.example.com:7999/PROJ/named-repo.git")
        assertNotNull((result as? RemoteUrlParseResult.Success)?.parsed)
        val parsed = (result as RemoteUrlParseResult.Success).parsed
        assertEquals("PROJ", parsed.projectKey)
        assertEquals("named-repo", parsed.repoSlug)
        assertEquals(ParsedBitbucketRemote.Scheme.SSH, parsed.scheme)
    }

    @Test
    fun `Cloud remote for any named remote is rejected`() {
        listOf(
            "https://bitbucket.org/team/repo.git",
            "git@bitbucket.org:team/repo.git",
            "ssh://git@bitbucket.org/team/repo.git"
        ).forEach { url ->
            val result = RemoteUrlParser.parse(url)
            assertEquals(
                RemoteUrlParseResult.CloudNotSupported, result,
                "Expected CloudNotSupported for $url"
            )
        }
    }

    @Test
    fun `PAT in URL does not bleed into project key`() {
        val result = RemoteUrlParser.parse("https://secrettoken123@bitbucket.example.com/scm/MYPROJECT/service.git")
        val parsed = (result as RemoteUrlParseResult.Success).parsed
        assertEquals("MYPROJECT", parsed.projectKey)
        assertEquals("service", parsed.repoSlug)
        // host should be the server, not the PAT
        assertEquals("bitbucket.example.com", parsed.host)
    }
}
