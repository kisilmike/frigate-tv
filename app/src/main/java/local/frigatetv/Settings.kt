package local.frigatetv

import android.content.Context
import org.json.JSONArray

/**
 * Настройки приложения в SharedPreferences (приватное хранилище приложения; пароли Frigate лежат там же
 * в открытом виде, как и в любом локальном клиенте без отдельного хранилища ключей).
 */
object Settings {
    private const val PREFS = "frigate_tv"
    private const val KEY_V2 = "servers_v2"        // JSON-массив серверов
    private const val KEY_V1 = "servers"            // прежний формат: адрес в строке
    private const val KEY_OLD = "server_host"       // самый первый формат: один адрес

    /** Список серверов (пустой при первом запуске). Прежние форматы настроек переносятся автоматически. */
    fun loadServers(ctx: Context): List<Server> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(KEY_V2, null)?.let { text ->
            val parsed = runCatching {
                val a = JSONArray(text)
                (0 until a.length()).map { Server.fromJson(a.getJSONObject(it)) }
            }.getOrNull()
            if (!parsed.isNullOrEmpty()) return parsed.distinctBy { it.id }
        }
        val legacy = p.getString(KEY_V1, null)?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: p.getString(KEY_OLD, null)?.trim()?.takeIf { it.isNotEmpty() }?.let { listOf(it) }
        return legacy?.distinct()?.map { Server(host = it) } ?: emptyList()
    }

    fun saveServers(ctx: Context, servers: List<Server>) {
        val a = JSONArray()
        servers.distinctBy { it.id }.forEach { a.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_V2, a.toString()).apply()
    }
}
