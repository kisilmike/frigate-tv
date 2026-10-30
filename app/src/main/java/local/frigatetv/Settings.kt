package local.frigatetv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Настройки приложения в SharedPreferences (приватное хранилище приложения; пароли Frigate лежат там же
 * в открытом виде, как и в любом локальном клиенте без отдельного хранилища ключей).
 */
object Settings {
    private const val PREFS = "frigate_tv"
    private const val KEY_V2 = "servers_v2"        // JSON-массив серверов
    private const val KEY_ORDER = "camera_order"    // JSON-объект: сервер → список камер
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

    /** Порядок камер по серверам: id сервера → имена камер в нужной последовательности. */
    fun loadOrder(ctx: Context): Map<String, List<String>> {
        val text = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ORDER, null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(text)
            o.keys().asSequence().associateWith { id ->
                val a = o.getJSONArray(id)
                (0 until a.length()).map { a.getString(it) }
            }
        }.getOrDefault(emptyMap())
    }

    fun saveOrder(ctx: Context, order: Map<String, List<String>>) {
        val o = JSONObject()
        order.forEach { (id, names) -> o.put(id, JSONArray(names)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ORDER, o.toString()).apply()
    }

    fun saveServers(ctx: Context, servers: List<Server>) {
        val a = JSONArray()
        servers.distinctBy { it.id }.forEach { a.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_V2, a.toString()).apply()
    }
}
