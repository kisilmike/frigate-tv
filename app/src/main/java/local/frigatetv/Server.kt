package local.frigatetv

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Один сервер Frigate и параметры подключения к нему.
 * Если во Frigate включена авторизация, обычно используют порт 8971 (HTTPS, по умолчанию самоподписанный
 * сертификат) и задают [user]/[password]; порт 5000 внутренний и без пароля.
 */
data class Server(
    val host: String,
    val port: Int = Config.FRIGATE_PORT,
    val https: Boolean = false,
    /** Принимать самоподписанный сертификат (только для этого сервера). */
    val insecure: Boolean = false,
    val user: String = "",
    val password: String = "",
    val rtspPort: Int = Config.RTSP_PORT,
) {
    val base: String get() = "${if (https) "https" else "http"}://$host:$port"
    val id: String get() = "$host:$port"
    val hasAuth: Boolean get() = user.isNotEmpty()

    /** Адрес RTSP-рестрима go2rtc; логин и пароль подставляются, если заданы (сервер без пароля их игнорирует). */
    val rtspBase: String
        get() {
            val cred = if (hasAuth) "${enc(user)}:${enc(password)}@" else ""
            return "rtsp://$cred$host:$rtspPort"
        }

    /** Подпись для списков в настройках. */
    val label: String
        get() = buildString {
            append(id)
            if (https) append(" · HTTPS")
            if (hasAuth) append(" · $user")
        }

    fun toJson(): JSONObject = JSONObject()
        .put("host", host).put("port", port).put("https", https).put("insecure", insecure)
        .put("user", user).put("password", password).put("rtspPort", rtspPort)

    companion object {
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        fun fromJson(o: JSONObject) = Server(
            host = o.getString("host"),
            port = o.optInt("port", Config.FRIGATE_PORT),
            https = o.optBoolean("https", false),
            insecure = o.optBoolean("insecure", false),
            user = o.optString("user", ""),
            password = o.optString("password", ""),
            rtspPort = o.optInt("rtspPort", Config.RTSP_PORT),
        )
    }
}
