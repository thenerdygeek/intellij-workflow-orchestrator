package com.workflow.orchestrator.web.service

import com.workflow.orchestrator.core.security.UrlSafetyGuard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression tests for [PinnedDns] — the per-call DNS cache that defeats the
 * DNS-rebinding TOCTOU between the SSRF safety check and OkHttp's subsequent resolution.
 *
 * The contract: lookups for a given hostname are resolved at most once per [PinnedDns]
 * instance. Subsequent lookups for the same host return the cached address — even if the
 * underlying resolver would now return a different (potentially malicious) address.
 */
class PinnedDnsTest {

    @Test
    fun `repeated lookups for the same host hit the resolver exactly once`() {
        val callCount = AtomicInteger(0)
        val resolver = UrlSafetyGuard.Resolver { _ ->
            callCount.incrementAndGet()
            arrayOf(InetAddress.getByName("203.0.113.1"))
        }
        val sut = PinnedDns(resolver)

        repeat(5) { sut.lookup("example.com") }

        assertEquals(1, callCount.get(), "PinnedDns must call the resolver exactly once per host")
    }

    @Test
    fun `lookup returns the address from the first resolver call even when resolver would change`() {
        // The resolver returns a different address on each call. The pinned DNS must
        // return only the first set, defeating rebinding.
        val calls = AtomicInteger(0)
        val resolver = UrlSafetyGuard.Resolver { _ ->
            val n = calls.incrementAndGet()
            if (n == 1) arrayOf(InetAddress.getByName("203.0.113.1"))
            else arrayOf(InetAddress.getByName("127.0.0.1"))
        }
        val sut = PinnedDns(resolver)

        val first = sut.lookup("evil.example.com")
        val second = sut.lookup("evil.example.com")

        assertEquals(first, second, "Subsequent lookups must reuse the cached address")
        assertEquals("203.0.113.1", first[0].hostAddress, "First (and only) lookup must be the screened address")
    }

    @Test
    fun `different hostnames cache independently`() {
        val calls = AtomicInteger(0)
        val resolver = UrlSafetyGuard.Resolver { host ->
            calls.incrementAndGet()
            when (host) {
                "a.example.com" -> arrayOf(InetAddress.getByName("203.0.113.1"))
                "b.example.com" -> arrayOf(InetAddress.getByName("203.0.113.2"))
                else            -> arrayOf(InetAddress.getByName("203.0.113.99"))
            }
        }
        val sut = PinnedDns(resolver)

        sut.lookup("a.example.com")
        sut.lookup("b.example.com")
        sut.lookup("a.example.com")  // cached
        sut.lookup("b.example.com")  // cached

        assertEquals(2, calls.get(), "PinnedDns must cache per-host, not globally")
    }
}
