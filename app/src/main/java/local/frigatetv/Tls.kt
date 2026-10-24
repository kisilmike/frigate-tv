package local.frigatetv

import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * HTTPS с самоподписанным сертификатом (у Frigate на порту 8971 он такой по умолчанию).
 * Проверка сертификата отключается только для серверов, у которых включена настройка «принимать любой»;
 * для остальных узлов всё работает как обычно. Действует и для плеера (он тоже ходит через HttpsURLConnection).
 */
object Tls {
    private val insecureHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val systemTm: X509TrustManager by lazy {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(null as KeyStore?)
        f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Список узлов, которым разрешён любой сертификат. */
    fun update(servers: List<Server>) {
        insecureHosts.clear()
        servers.filter { it.https && it.insecure }.forEach { insecureHosts += it.host }
    }

    /** Разрешить любой сертификат для проверки соединения в настройках (до следующего update). */
    fun allowForTest(server: Server) {
        if (server.https && server.insecure) insecureHosts += server.host
    }

    private fun allowed(socket: Socket?): Boolean {
        if (socket == null || insecureHosts.isEmpty()) return false
        val ip = socket.inetAddress?.hostAddress
        val peer = (socket as? SSLSocket)?.handshakeSession?.peerHost
        return (ip != null && ip in insecureHosts) || (peer != null && peer in insecureHosts)
    }

    private val trustManager = object : X509ExtendedTrustManager() {
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) {
            if (allowed(socket)) return
            val tm = systemTm
            if (tm is X509ExtendedTrustManager) tm.checkServerTrusted(chain, authType, socket) else tm.checkServerTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) {
            val tm = systemTm
            if (tm is X509ExtendedTrustManager) tm.checkServerTrusted(chain, authType, engine) else tm.checkServerTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) =
            systemTm.checkServerTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) =
            systemTm.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) =
            systemTm.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            systemTm.checkClientTrusted(chain, authType)

        override fun getAcceptedIssuers(): Array<X509Certificate> = systemTm.acceptedIssuers
    }

    /** Один раз при запуске приложения. */
    fun install() {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(trustManager), null)
        HttpsURLConnection.setDefaultSSLSocketFactory(ctx.socketFactory)
        val original = HttpsURLConnection.getDefaultHostnameVerifier()
        HttpsURLConnection.setDefaultHostnameVerifier { host, session -> host in insecureHosts || original.verify(host, session) }
    }
}
