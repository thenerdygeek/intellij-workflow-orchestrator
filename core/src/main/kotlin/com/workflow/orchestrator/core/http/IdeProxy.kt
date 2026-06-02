package com.workflow.orchestrator.core.http

import com.intellij.openapi.diagnostic.Logger
import okhttp3.Authenticator
import okhttp3.Credentials
import java.net.InetSocketAddress
import java.net.ProxySelector

/**
 * Bridges IntelliJ's configured HTTP proxy (Settings -> Appearance & Behavior ->
 * System Settings -> HTTP Proxy) into plugin-built OkHttp clients.
 *
 * Plugin HTTP clients that construct their own `OkHttpClient.Builder()` (rather than
 * going through a factory that already sets this) MUST call [selector] -- otherwise
 * external requests bypass the IDE proxy and time out on corporate/VPN networks that
 * require it. [proxyAuthenticator] answers 407 challenges for authenticated proxies.
 */
object IdeProxy {
    private val log = Logger.getInstance(IdeProxy::class.java)

    /**
     * The IDE's configured proxy selector. Falls back to [ProxySelector.getDefault]
     * when the platform service is unavailable (e.g. unit tests / headless), so callers
     * never crash from a missing application container.
     */
    fun selector(): ProxySelector =
        try {
            com.intellij.util.net.JdkProxyProvider.getInstance().proxySelector
        } catch (t: Throwable) {
            log.debug("JdkProxyProvider unavailable; using default ProxySelector", t)
            ProxySelector.getDefault() ?: NoProxySelector
        }

    /**
     * OkHttp proxy authenticator that answers a 407 using the IDE's configured proxy
     * credentials. Returns null (no retry) when no credentials are available or the
     * challenge was already answered -- so unauthenticated proxies are unaffected.
     */
    fun proxyAuthenticator(): Authenticator = Authenticator { route, response ->
        if (response.request.header("Proxy-Authorization") != null) return@Authenticator null
        val addr = route?.proxy?.address() as? InetSocketAddress ?: return@Authenticator null
        val ideAuth = try {
            com.intellij.util.net.JdkProxyProvider.getInstance().authenticator
        } catch (t: Throwable) {
            return@Authenticator null
        }
        val pa = java.net.Authenticator.requestPasswordAuthentication(
            ideAuth,
            addr.hostString,
            addr.address,
            addr.port,
            "HTTP",
            "Proxy authentication required",
            "basic",
            response.request.url.toUrl(),
            java.net.Authenticator.RequestorType.PROXY,
        ) ?: return@Authenticator null
        response.request.newBuilder()
            .header("Proxy-Authorization", Credentials.basic(pa.userName, String(pa.password)))
            .build()
    }

    private object NoProxySelector : ProxySelector() {
        override fun select(uri: java.net.URI?) = listOf(java.net.Proxy.NO_PROXY)
        override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) {}
    }
}
