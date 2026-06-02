package com.workflow.orchestrator.core.http

import com.intellij.openapi.diagnostic.Logger
import okhttp3.OkHttpClient
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Bridges IntelliJ's certificate trust ([com.intellij.util.net.ssl.CertificateManager])
 * into plugin-built OkHttp clients — the TLS analogue of [IdeProxy].
 *
 * A bare `OkHttpClient.Builder()` uses the JVM platform default trust manager, which
 * validates server certificates only against the JBR's bundled `jbr/lib/security/cacerts`.
 * On a corporate network with SSL inspection (a TLS man-in-the-middle proxy), the cert
 * presented is re-signed by a private corporate root CA that is NOT in that store, so the
 * handshake fails with an `SSLException`. The user is then told (unhelpfully) to run
 * `keytool` against the JBR cacerts.
 *
 * IntelliJ's [com.intellij.util.net.ssl.CertificateManager] already solves this the way
 * browsers do: its trust manager honours the OS system trust store (where corporate IT
 * pushes the inspection CA via policy) AND the IDE's own user truststore, and pops the
 * native "accept certificate?" dialog for genuinely-new untrusted certs. Routing plugin
 * HTTP through it gives `web_fetch` (and every other client) the same zero-keytool
 * behaviour as the rest of the IDE's networking.
 *
 * Clients that build their own [OkHttpClient.Builder] should call [applyTo] — otherwise
 * external HTTPS requests fail TLS on SSL-inspecting networks even when the IDE itself can
 * reach the same hosts.
 */
object IdeTrust {
    private val log = Logger.getInstance(IdeTrust::class.java)

    /** Resolved IDE TLS material: the socket factory and its matching trust manager. */
    data class SslConfig(
        val socketFactory: SSLSocketFactory,
        val trustManager: X509TrustManager,
    )

    /**
     * The IDE's certificate trust material, or `null` when [com.intellij.util.net.ssl.CertificateManager]
     * is unavailable (headless / unit tests / missing application container). Mirrors
     * [IdeProxy.selector]'s defensive fallback so callers never crash from a missing
     * platform service — they simply fall back to the JVM default trust.
     */
    fun sslConfig(): SslConfig? =
        try {
            val cm = com.intellij.util.net.ssl.CertificateManager.getInstance()
            SslConfig(
                socketFactory = cm.sslContext.socketFactory,
                trustManager = cm.trustManager,
            )
        } catch (t: Throwable) {
            log.debug("CertificateManager unavailable; using JVM default trust", t)
            null
        }

    /**
     * Installs IDE certificate trust on [builder] when available and returns [builder]
     * for chaining. When the platform is unavailable this is a no-op and the SAME builder
     * is returned unchanged (JVM default trust is left in place).
     */
    fun applyTo(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val config = sslConfig() ?: return builder
        return builder.sslSocketFactory(config.socketFactory, config.trustManager)
    }
}
