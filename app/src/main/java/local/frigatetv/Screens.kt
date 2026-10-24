package local.frigatetv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Состояние одного сервера на главном экране: камеры или ошибка подключения. */
class Group(val server: Server, val cams: List<Camera>?, val error: String?)

@Composable
fun App() {
    val ctx = LocalContext.current
    var servers by remember { mutableStateOf(Settings.loadServers(ctx)) }
    val groups = remember { mutableStateMapOf<String, Group>() }
    var opened by remember { mutableStateOf<Camera?>(null) }
    var lastKey by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    // каждый сервер грузится независимо: недоступный не мешает остальным и опрашивается до успеха
    LaunchedEffect(servers) {
        Tls.update(servers)
        groups.clear()
        servers.forEach { server ->
            launch {
                while (isActive) {
                    try {
                        groups[server.id] = Group(server, Api.cameras(server), null)
                        break
                    } catch (e: Exception) {
                        groups[server.id] = Group(server, null, e.message ?: e.javaClass.simpleName)
                        delay(4000)
                    }
                }
            }
        }
    }

    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = !showSettings && opened != null) { opened = null }

    val ordered = servers.mapNotNull { groups[it.id] }
    val anyLoaded = ordered.any { it.cams != null }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val cam = opened
        when {
            showSettings -> SettingsScreen(servers) { result ->
                showSettings = false
                if (result != null) {
                    Settings.saveServers(ctx, result)
                    opened = null
                    servers = result
                }
            }
            !anyLoaded -> LoadingScreen(servers, ordered) { showSettings = true }
            cam != null -> PlayerScreen(cam) { opened = null }
            else -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    GridScreen(servers.map { s -> groups[s.id] ?: Group(s, null, "загрузка…") }, servers.size > 1, lastKey) {
                        lastKey = it.key
                        opened = it
                    }
                }
                Footer(servers) { showSettings = true }
            }
        }
    }
}

@Composable
fun LoadingScreen(servers: List<Server>, groups: List<Group>, onSettings: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(200)
        runCatching { focus.requestFocus() }
    }
    val text = if (servers.isEmpty()) {
        "Сервер Frigate не настроен.\nОткройте «Настройки» и добавьте адрес сервера."
    } else servers.joinToString("\n") { s ->
        val g = groups.firstOrNull { it.server.id == s.id }
        "Подключение к Frigate (${s.id})…" + (g?.error?.let { "\n$it" } ?: "")
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = Color.White, fontSize = 22.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            TvButton("Настройки", Modifier.focusRequester(focus), onClick = onSettings)
        }
    }
}

@Composable
fun Footer(servers: List<Server>, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF0B0B0B)).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${Config.APP_NAME} · версия ${Config.APP_VERSION} от ${Config.APP_DATE}",
            color = Color(0xFF9AA0A6),
            fontSize = 14.sp,
        )
        Text(
            text = "   ·   " + if (servers.size == 1) "сервер ${servers[0].id}" else "серверов: ${servers.size}",
            color = Color(0xFF6F757B),
            fontSize = 14.sp,
        )
        Spacer(Modifier.weight(1f))
        TvButton("Настройки", onClick = onSettings)
    }
}

@Composable
fun TvButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Color(0xFF555555), shape)
            .background(if (focused) Color(0xFF2A3F55) else Color(0xFF1A1A1A), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 16.sp)
    }
}

// ───────────────────────────── Настройки ─────────────────────────────

private enum class Field { HOST, PORT, RTSP, USER, PASSWORD }

/**
 * Настройки: список серверов Frigate. Нажатие на сервер открывает его параметры.
 * «Готово» возвращает новый список, «Отмена» — null.
 */
@Composable
fun SettingsScreen(initial: List<Server>, onClose: (List<Server>?) -> Unit) {
    var servers by remember { mutableStateOf(initial) }
    var editIndex by remember { mutableStateOf<Int?>(null) }   // null: список, -1: новый сервер

    val idx = editIndex
    if (idx == null) {
        ServerListPage(
            servers = servers,
            onEdit = { editIndex = it },
            onDone = { onClose(servers) },
            onCancel = { onClose(null) },
        )
    } else {
        ServerEditPage(
            initial = if (idx >= 0) servers[idx] else Server(host = ""),
            canDelete = idx >= 0,
            otherIds = servers.filterIndexed { i, _ -> i != idx }.map { it.id },
            onSave = { s ->
                servers = if (idx >= 0) servers.mapIndexed { i, old -> if (i == idx) s else old } else servers + s
                editIndex = null
            },
            onDelete = {
                servers = servers.filterIndexed { i, _ -> i != idx }
                editIndex = null
            },
            onCancel = { editIndex = null },
        )
    }
}

@Composable
private fun ServerListPage(
    servers: List<Server>,
    onEdit: (Int) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { first.requestFocus() }
    }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF101418)).padding(horizontal = 40.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Настройки", color = Color.White, fontSize = 26.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "Серверы Frigate. Если их несколько, камеры всех серверов выводятся на главном экране по группам.",
            color = Color(0xFF9AA0A6),
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        servers.forEachIndexed { i, s ->
            TvButton(
                "${i + 1}.  ${s.label}    (изменить)",
                (if (i == 0) Modifier.focusRequester(first) else Modifier).width(560.dp),
            ) { onEdit(i) }
            Spacer(Modifier.height(8.dp))
        }
        TvButton("+ Добавить сервер", (if (servers.isEmpty()) Modifier.focusRequester(first) else Modifier).width(560.dp)) { onEdit(-1) }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TvButton("Готово", onClick = onDone)
            TvButton("Отмена", onClick = onCancel)
        }
    }
}

/** Параметры одного сервера: адрес, порт, HTTPS, сертификат, логин и пароль, порт RTSP. */
@Composable
private fun ServerEditPage(
    initial: Server,
    canDelete: Boolean,
    otherIds: List<String>,
    onSave: (Server) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var d by remember { mutableStateOf(initial) }
    var field by remember { mutableStateOf<Field?>(null) }
    var info by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }

    BackHandler(enabled = field == null) { onCancel() }

    when (field) {
        Field.HOST -> DigitPadPage("Адрес сервера", "IP-адрес (цифры и точка)", d.host, allowDot = true, maxLen = 40) { v ->
            if (v != null) d = d.copy(host = v.trim())
            field = null
        }
        Field.PORT -> DigitPadPage("Порт Frigate", "обычно 5000 (без пароля) или 8971 (с паролем, HTTPS)", d.port.toString(), allowDot = false, maxLen = 5) { v ->
            v?.toIntOrNull()?.takeIf { it in 1..65535 }?.let { d = d.copy(port = it) }
            field = null
        }
        Field.RTSP -> DigitPadPage("Порт RTSP (видео)", "обычно 8554", d.rtspPort.toString(), allowDot = false, maxLen = 5) { v ->
            v?.toIntOrNull()?.takeIf { it in 1..65535 }?.let { d = d.copy(rtspPort = it) }
            field = null
        }
        Field.USER -> TextInputPage("Логин Frigate", d.user, secret = false) { v ->
            if (v != null) d = d.copy(user = v.trim())
            field = null
        }
        Field.PASSWORD -> TextInputPage("Пароль Frigate", d.password, secret = true) { v ->
            if (v != null) d = d.copy(password = v)
            field = null
        }
        null -> {
            LaunchedEffect(Unit) {
                delay(150)
                runCatching { first.requestFocus() }
            }
            Column(
                Modifier.fillMaxSize().background(Color(0xFF101418)).padding(horizontal = 40.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Сервер Frigate", color = Color.White, fontSize = 24.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Если во Frigate включена авторизация, используйте порт 8971, HTTPS и логин с паролем.",
                    color = Color(0xFF9AA0A6),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                val w = Modifier.width(560.dp)
                TvButton("Адрес:  ${d.host.ifEmpty { "не задан" }}", w.focusRequester(first)) { field = Field.HOST }
                Spacer(Modifier.height(6.dp))
                TvButton("Порт Frigate:  ${d.port}", w) { field = Field.PORT }
                Spacer(Modifier.height(6.dp))
                TvButton("HTTPS:  ${if (d.https) "включён" else "выключен"}", w) {
                    val on = !d.https
                    // типовые порты: 5000 без шифрования, 8971 с авторизацией и HTTPS
                    val port = when {
                        on && d.port == Config.FRIGATE_PORT -> 8971
                        !on && d.port == 8971 -> Config.FRIGATE_PORT
                        else -> d.port
                    }
                    d = d.copy(https = on, port = port)
                }
                if (d.https) {
                    Spacer(Modifier.height(6.dp))
                    TvButton("Сертификат:  ${if (d.insecure) "принимать любой (самоподписанный)" else "проверять"}", w) {
                        d = d.copy(insecure = !d.insecure)
                    }
                }
                Spacer(Modifier.height(6.dp))
                TvButton("Логин:  ${d.user.ifEmpty { "не задан" }}", w) { field = Field.USER }
                Spacer(Modifier.height(6.dp))
                TvButton("Пароль:  ${if (d.password.isEmpty()) "не задан" else "•".repeat(minOf(d.password.length, 12))}", w) {
                    field = Field.PASSWORD
                }
                Spacer(Modifier.height(6.dp))
                TvButton("Порт RTSP (видео):  ${d.rtspPort}", w) { field = Field.RTSP }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TvButton("Проверить") {
                        scope.launch {
                            info = "Проверка…"
                            info = try {
                                "✓ " + Api.ping(d)
                            } catch (e: Exception) {
                                "✗ ${e.message ?: e.javaClass.simpleName}"
                            }
                        }
                    }
                    TvButton("Сохранить") {
                        val h = d.host.trim()
                        val s = d.copy(host = h)
                        info = when {
                            h.isEmpty() || !Regex("^[A-Za-z0-9.\\-]+$").matches(h) -> "Недопустимый адрес"
                            s.id in otherIds -> "Такой сервер уже есть"
                            else -> { onSave(s); "" }
                        }
                    }
                    if (canDelete) TvButton("Удалить", onClick = onDelete)
                    TvButton("Отмена", onClick = onCancel)
                }
                Spacer(Modifier.height(8.dp))
                Text(info, color = Color(0xFFE0E0E0), fontSize = 15.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Ввод числа или адреса: экранная цифровая клавиатура (цифровые клавиши пульта тоже работают). */
@Composable
private fun DigitPadPage(
    title: String,
    hint: String,
    initial: String,
    allowDot: Boolean,
    maxLen: Int,
    onResult: (String?) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { firstKey.requestFocus() }
    }
    BackHandler { onResult(null) }

    fun type(ch: String) {
        if (value.length < maxLen) value += ch
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            .padding(horizontal = 40.dp, vertical = 24.dp)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = ev.nativeKeyEvent.keyCode
                when (code) {
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { type((code - KeyEvent.KEYCODE_0).toString()); true }
                    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> { type((code - KeyEvent.KEYCODE_NUMPAD_0).toString()); true }
                    KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_NUMPAD_DOT -> { if (allowDot) type("."); true }
                    KeyEvent.KEYCODE_DEL -> { value = value.dropLast(1); true }
                    else -> false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.White, fontSize = 26.sp)
        Spacer(Modifier.height(6.dp))
        Text(hint, color = Color(0xFF9AA0A6), fontSize = 15.sp)
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .width(420.dp)
                .border(2.dp, Color(0xFF4FC3F7), RoundedCornerShape(8.dp))
                .background(Color(0xFF0B0F13), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(value + "▏", color = Color.White, fontSize = 26.sp)
        }
        Spacer(Modifier.height(14.dp))
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf(if (allowDot) "." else "", "0", "⌫"),
        )
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { key ->
                    val mod = Modifier.size(width = 84.dp, height = 46.dp)
                    if (key.isEmpty()) {
                        Spacer(mod)
                    } else {
                        TvButton(label = key, modifier = if (key == "5") mod.focusRequester(firstKey) else mod) {
                            if (key == "⌫") value = value.dropLast(1) else type(key)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TvButton("Готово") { onResult(value) }
            TvButton("Отмена") { onResult(null) }
        }
    }
}

/** Ввод текста (логин, пароль) системной клавиатурой телевизора. */
@Composable
private fun TextInputPage(title: String, initial: String, secret: Boolean, onResult: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var shown by remember { mutableStateOf(!secret) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        delay(200)
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    BackHandler { onResult(null) }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF101418)).padding(horizontal = 40.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.White, fontSize = 26.sp)
        Spacer(Modifier.height(6.dp))
        Text("Введите значение клавиатурой на экране, затем «Готово».", color = Color(0xFF9AA0A6), fontSize = 15.sp)
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .width(520.dp)
                .border(2.dp, Color(0xFF4FC3F7), RoundedCornerShape(8.dp))
                .background(Color(0xFF0B0F13), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 24.sp),
                cursorBrush = SolidColor(Color.White),
                visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done,
                    keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
                ),
                keyboardActions = KeyboardActions(onDone = { onResult(text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TvButton("Готово") { onResult(text) }
            if (secret) TvButton(if (shown) "Скрыть" else "Показать") { shown = !shown }
            TvButton("Отмена") { onResult(null) }
        }
    }
}

// ───────────────────────────── Главный экран ─────────────────────────────

/** Камеры, сгруппированные по серверам (заголовки групп только если серверов больше одного). */
@Composable
fun GridScreen(groups: List<Group>, showHeaders: Boolean, focusKey: String?, onOpen: (Camera) -> Unit) {
    val focus = remember { FocusRequester() }
    val allKeys = groups.flatMap { g -> g.cams.orEmpty().map { it.key } }
    LaunchedEffect(allKeys) {
        delay(150)
        runCatching { focus.requestFocus() }
    }
    val target = if (focusKey != null && focusKey in allKeys) focusKey else allKeys.firstOrNull()
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        groups.forEach { g ->
            if (showHeaders) {
                item(key = "header/${g.server.id}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "Сервер ${g.server.id}" + (g.cams?.let { " · камер: ${it.size}" } ?: ""),
                        color = Color(0xFF9AA0A6),
                        fontSize = 18.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            val cams = g.cams
            if (cams == null) {
                item(key = "error/${g.server.id}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "Нет связи с ${g.server.id}: ${g.error ?: ""}. Повторная попытка…",
                        color = Color(0xFFE57373),
                        fontSize = 16.sp,
                    )
                }
            } else {
                items(cams, key = { it.key }) { cam ->
                    CameraTile(
                        cam = cam,
                        modifier = if (cam.key == target) Modifier.focusRequester(focus) else Modifier,
                        onClick = { onOpen(cam) },
                    )
                }
            }
        }
    }
}

@Composable
fun CameraTile(cam: Camera, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(cam.key) {
        while (true) {
            Api.snapshot(cam)?.let { frame = it.asImageBitmap() }
            delay(Config.SNAPSHOT_INTERVAL_MS)
        }
    }

    Box(
        modifier
            .aspectRatio(16f / 9f)
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 4.dp else 1.dp, if (focused) Color.White else Color(0xFF333333))
            .background(Color(0xFF111111))
            .clickable(onClick = onClick),
    ) {
        frame?.let {
            Image(
                bitmap = it,
                contentDescription = cam.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = cam.name,
            color = Color.White,
            fontSize = 18.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(Color(0x99000000))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
