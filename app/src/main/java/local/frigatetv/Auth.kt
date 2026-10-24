package local.frigatetv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Ошибка входа: неверный логин/пароль или сервер отказал. */
class AuthException(message: String) : IOException(message)

/**
 * Вход во Frigate (POST /api/login) и хранение токена сессии (cookie frigate_token).
 * Если сервер вход не требует (порт 5000), токена нет, и запросы идут без авторизации.
 */
object Auth {
    private class Session(val token: String?, val at: Long)

    private val sessions = ConcurrentHashMap<String, Session>()
    private val mutex = Mutex()

    /** Сессия живёт в пределах суток; обновляем заранее. */
    private const val MAX_AGE_MS = 6 * 3600_000L

    private fun key(s: Server) = "${s.id}|${s.https}|${s.user}|${s.password.hashCode()}"

    /** Заголовки для запроса к серверу; пусто, если логин не задан или сервер вход не требует. */
    suspend fun headers(server: Server): Map<String, String> {
        if (!server.hasAuth) return emptyMap()
        val token = token(server) ?: return emptyMap()
        return mapOf("Cookie" to "frigate_token=$token")
    }

    private suspend fun token(server: Server): String? = mutex.withLock {
        val k = key(server)
        val cur = sessions[k]
        if (cur != null && System.currentTimeMillis() - cur.at < MAX_AGE_MS) return cur.token
        val t = withContext(Dispatchers.IO) { login(server) }
        sessions[k] = Session(t, System.currentTimeMillis())
        t
    }

    /** Токен перестал подходить (401): при следующем запросе войдём заново. */
    fun invalidate(server: Server) {
        sessions.remove(key(server))
    }

    private fun login(server: Server): String? {
        val conn = (URL("${server.base}/api/login").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 4000
            readTimeout = 8000
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            val body = JSONObject().put("user", server.user).put("password", server.password).toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            when (val code = conn.responseCode) {
                200, 204 -> Unit
                401, 400, 422 -> throw AuthException("неверный логин или пароль")
                429 -> throw AuthException("слишком много попыток входа, подождите минуту")
                404, 405 -> return null // авторизации на этом порту нет
                else -> throw AuthException("вход не удался: HTTP $code")
            }
            val cookies = conn.headerFields.entries
                .filter { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
                .flatMap { it.value }
            return cookies.firstNotNullOfOrNull { Regex("frigate_token=([^;]+)").find(it)?.groupValues?.get(1) }
        } finally {
            conn.disconnect()
        }
    }
}
