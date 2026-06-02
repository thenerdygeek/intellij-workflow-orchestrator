package com.workflow.orchestrator.web.service

import com.workflow.orchestrator.core.web.WebError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class WebFetchErrorMappingTest {
    private fun code(e: Exception) = WebFetchEngine.mapFetchException(e, "https://x.test").code

    @Test fun `unknown host maps to DNS`() =
        assertEquals("HTTP_DNS_FAILED", code(UnknownHostException("x")))

    @Test fun `connect exception maps to connect failed`() =
        assertEquals("HTTP_CONNECT_FAILED", code(ConnectException("refused")))

    @Test fun `connect-stage timeout maps to connect failed`() =
        assertEquals("HTTP_CONNECT_FAILED", code(SocketTimeoutException("connect timed out")))

    @Test fun `read-stage timeout maps to read timeout`() =
        assertEquals("HTTP_READ_TIMEOUT", code(SocketTimeoutException("timeout")))

    @Test fun `ssl maps to TLS`() =
        assertEquals("HTTP_TLS_FAILED", code(object : SSLException("bad cert") {}))

    @Test fun `other maps to generic HTTP_ERROR`() =
        assertEquals("HTTP_ERROR", code(java.io.IOException("weird")))
}
