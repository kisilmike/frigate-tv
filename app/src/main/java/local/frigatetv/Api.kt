package local.frigatetv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

/**
 * Камера конкретного сервера Frigate.
 * [name] — имя во Frigate, [stream] — основной поток в go2rtc, [liveStream] — поток для просмотра:
 * отдельная сессия `<поток>_live`, если она заведена (тогда нет ожидания ключевого кадра), иначе основной.
 */
data class Camera(val server: Server, val name: String, val stream: String, val liveStream: String) {
    val http: String get() = server.base
    val rtsp: String get() = server.rtspBase
    val key: String get() = "${server.id}/$name"
}

/** Ответ сервера с кодом не 2xx. */
class HttpStatus(val code: Int) : IOException(
    when (code) {
        401 -> "нужен логин и пароль"
        403 -> "доступ запрещён"
        404 -> "не найдено (HTTP 404)"
        else -> "HTTP $code"
    }
)

object Api {
    private const val TAG = "FrigateTV"

    /** Разница часов сервера и телевизора (мс) по каждому серверу, берётся из заголовка Date. */
    private val clockSkewMs = ConcurrentHashMap<String, Long>()

    /** «Сейчас» по часам сервера, unix-секунды. */
    fun nowSec(server: Server): Double = (System.currentTimeMillis() + (clockSkewMs[server.id] ?: 0L)) / 1000.0

    /**
     * GET к серверу с авторизацией. Если сервер ответил 401 (сессия истекла), входим заново и повторяем один раз.
     * [read] выполняется на потоке ввода-вывода, пока соединение открыто.
     */
    private suspend fun <T> call(
        server: Server,
        path: String,
        readTimeoutMs: Int = 8000,
        read: (HttpURLConnection) -> T,
    ): T = withContext(Dispatchers.IO) {
        try {
            var last: Throwable? = null
            for (attempt in 0..1) {
                val headers = Auth.headers(server)
                val conn = (URL(server.base + path).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = readTimeoutMs
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                try {
                    val code = conn.responseCode
                    if (code == 401 && server.hasAuth && attempt == 0) {
                        Auth.invalidate(server)
                        last = HttpStatus(401)
                        continue
                    }
                    if (code !in 200..299) throw HttpStatus(code)
                    return@withContext read(conn)
                } finally {
                    conn.disconnect()
                }
            }
            throw last ?: HttpStatus(401)
        } catch (e: SSLException) {
            throw IOException("сертификат сервера не принят: в настройках сервера включите «Сертификат: принимать любой»", e)
        } catch (e: SocketTimeoutException) {
            throw IOException("сервер не отвечает: проверьте адрес и порт", e)
        } catch (e: ConnectException) {
            throw IOException("не удалось подключиться: проверьте адрес и порт", e)
        } catch (e: UnknownHostException) {
            throw IOException("неизвестный адрес сервера", e)
        }
    }

    /** Имена потоков go2rtc: через API Frigate (работает и с авторизацией), запасной путь — порт go2rtc. */
    private suspend fun go2rtcStreamNames(server: Server): Set<String> {
        runCatching {
            call(server, "/api/go2rtc/streams") { c ->
                JSONObject(c.inputStream.bufferedReader().use { it.readText() }).keys().asSequence().toSet()
            }
        }.getOrNull()?.let { return it }
        if (server.https) return emptySet()
        return runCatching {
            withContext(Dispatchers.IO) {
                val conn = URL("http://${server.host}:${Config.GO2RTC_PORT}/api/streams").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 5000
                try {
                    JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).keys().asSequence().toSet()
                } finally {
                    conn.disconnect()
                }
            }
        }.getOrDefault(emptySet())
    }

    /** Список включённых камер сервера из /api/config в порядке конфига. */
    suspend fun cameras(server: Server): List<Camera> {
        val (text, date) = call(server, "/api/config", 20000) { c ->
            c.inputStream.bufferedReader().use { it.readText() } to c.getHeaderFieldDate("Date", 0L)
        }
        if (date > 0) {
            clockSkewMs[server.id] = date - System.currentTimeMillis()
            Log.i(TAG, "${server.id}: clock skew vs server ${clockSkewMs[server.id]} ms")
        }
        val live = go2rtcStreamNames(server)
        val cams = JSONObject(text).getJSONObject("cameras")
        val result = mutableListOf<Camera>()
        for (name in cams.keys()) {
            val cfg = cams.getJSONObject(name)
            if (!cfg.optBoolean("enabled", true)) continue
            val streams = cfg.optJSONObject("live")?.optJSONObject("streams")
            val stream = streams?.keys()?.asSequence()?.firstOrNull()
                ?.let { streams.getString(it) }
                ?: name.lowercase()
            // отдельная сессия для просмотра: `<поток>_live`, а если поток составной (например, видео + чужой звук),
            // то `<камера>_live`; иначе основной поток
            val liveStream = listOf("${stream}_live", "${name.lowercase()}_live").firstOrNull { it in live } ?: stream
            result += Camera(server, name, stream, liveStream)
        }
        Log.i(TAG, "${server.id} cameras: ${result.map { "${it.name}->${it.liveStream}" }}")
        return result
    }

    private fun decode(c: HttpURLConnection): Bitmap? = c.inputStream.use { BitmapFactory.decodeStream(it) }

    /** Последний снимок камеры; null при любой ошибке. */
    suspend fun snapshot(cam: Camera, height: Int = Config.SNAPSHOT_HEIGHT): Bitmap? =
        runCatching { call(cam.server, "/api/${cam.name}/latest.jpg?h=$height", read = ::decode) }.getOrNull()

    /** Кадр из записи камеры на момент [ts] (unix, сек); null при ошибке. */
    suspend fun recordingFrame(cam: Camera, ts: Long): Bitmap? =
        runCatching {
            call(cam.server, "/api/${cam.name}/recordings/$ts/snapshot.jpg?height=${Config.FRAME_HEIGHT}", read = ::decode)
        }.getOrNull()

    /** Записанные сегменты камеры в интервале [after, before] (unix, сек), по возрастанию времени. */
    suspend fun recordings(cam: Camera, after: Long, before: Long): List<Rec> =
        call(cam.server, "/api/${cam.name}/recordings?after=$after&before=$before", 25000) { c ->
            val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
            val out = ArrayList<Rec>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val start = o.getDouble("start_time")
                val dur = o.optDouble("duration", o.getDouble("end_time") - start)
                if (dur > 0) out += Rec(start, dur)
            }
            out.sortBy { it.start }
            out
        }

    /** Проверка настроек сервера: версия Frigate (и результат входа); при ошибке бросает исключение с причиной. */
    suspend fun ping(server: Server): String {
        Tls.allowForTest(server)
        val version = call(server, "/api/version") { c -> c.inputStream.bufferedReader().use { it.readText().trim() } }
        return "Frigate $version" + if (server.hasAuth) " · вход выполнен" else ""
    }
}
