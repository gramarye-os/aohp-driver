package org.aohp.driver.harness

import android.util.Log
import org.aohp.driver.binder.ContainerService
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * The gateway's loopback endpoint as the Driver should reach it: plain HTTP until the gateway
 * serves TLS (gateway.tls.enabled, auto-generated self-signed cert), then HTTPS with that one
 * certificate pinned by SHA-256 — nothing else is trusted, and 127.0.0.1 is accepted as the host
 * even though the cert has no SAN. [refresh] asks the env (aohp-gateway fingerprint) which it is.
 */
object GatewayEndpoint {
    private const val TAG = "AohpGateway"
    private const val HOST = "127.0.0.1"
    private const val PORT = 18789

    @Volatile var tls: Boolean = false; private set
    @Volatile var fingerprint: String? = null; private set     // "AA:BB:…" uppercase, colon-separated
    @Volatile private var refreshedAt = 0L

    fun url(path: String = ""): String = (if (tls) "https" else "http") + "://" + HOST + ":" + PORT + "/" + path.trimStart('/')

    /** Re-learn TLS state from the env (cheap; rate-limited to once per 20 s unless [force]). */
    suspend fun refresh(svc: ContainerService, env: String?, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - refreshedAt < 20_000) return
        refreshedAt = now
        val e = env ?: svc.listContainers().getOrNull()?.firstOrNull() ?: return
        try {
            val r = svc.execSync(e, "aohp-gateway fingerprint 2>/dev/null", 15_000).getOrNull()
            val fp = (r?.stdout ?: "").trim().uppercase()
            if (r != null && r.exitCode == 0 && fp.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}"))) { tls = true; fingerprint = fp }
            else { tls = false; fingerprint = null }
        } catch (t: Throwable) { Log.w(TAG, "endpoint refresh: " + t.message) }
    }

    fun sha256(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

    /** True when [cert] is exactly the gateway's pinned certificate. */
    fun matches(cert: X509Certificate?): Boolean = cert != null && fingerprint != null && sha256(cert) == fingerprint

    private val pinnedContext: SSLContext by lazy {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw java.security.cert.CertificateException("client certs not used")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty() || !matches(chain[0])) throw java.security.cert.CertificateException("gateway certificate is not the pinned one")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
    }

    /** An HttpURLConnection to the gateway: HTTPS pinned to the gateway cert when TLS is on. */
    fun open(path: String = ""): HttpURLConnection {
        val c = URL(url(path)).openConnection() as HttpURLConnection
        if (c is HttpsURLConnection) {
            c.sslSocketFactory = pinnedContext.socketFactory
            c.hostnameVerifier = javax.net.ssl.HostnameVerifier { host, _ -> host == HOST || host == "localhost" }
        }
        return c
    }
}
