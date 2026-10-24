package local.frigatetv

import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "FrigateTV"
private val clockFormat = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())

/**
 * Камера на весь экран.
 *  - живой поток: RTSP из go2rtc;
 *  - ← / → (нажатие): прыжок на 10 с, из живого потока ← открывает архив (HLS /vod Frigate);
 *  - ← / → (удержание): первые 10 с по минуте за 0,5 с, дальше по 30 минут за 0,5 с;
 *    пока крутим, показываем кадры из записей на момент курсора, по отпусканию грузим нужный кусок архива;
 *  - → у живого края возвращает живой поток; OK — пауза в архиве; «Назад» — из архива в живой.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(cam: Camera, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val exo = remember {
        // по умолчанию плеер копит 2,5 с перед стартом; для живого видео хватает 0,3 с
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1000, 8000, 300, 600)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        ExoPlayer.Builder(ctx).setLoadControl(loadControl).build()
    }
    val focus = remember { FocusRequester() }

    var inHistory by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var holdActive by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("● ЖИВОЙ") }
    var error by remember { mutableStateOf<String?>(null) }
    var archive by remember { mutableStateOf<Archive?>(null) }
    var pendingSeekWall by remember { mutableStateOf<Double?>(null) }
    var cursor by remember { mutableStateOf<Double?>(null) }
    var needReload by remember { mutableStateOf(false) }
    var spanIdx by remember { mutableIntStateOf(0) }
    var lastCenter by remember { mutableDoubleStateOf(0.0) }
    var holdJob by remember { mutableStateOf<Job?>(null) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var previewJob by remember { mutableStateOf<Job?>(null) }

    // кадры из записей для быстрой перемотки
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var pendingT by remember { mutableStateOf<Double?>(null) }
    var inFlight by remember { mutableIntStateOf(0) }
    var frameSeq by remember { mutableIntStateOf(0) }
    var shownSeq by remember { mutableIntStateOf(0) }
    var frameWall by remember { mutableDoubleStateOf(0.0) }

    // пока живой поток не пошёл (ждём ключевой кадр), показываем свежие снимки вместо чёрного экрана
    var poster by remember { mutableStateOf<ImageBitmap?>(null) }
    var firstFrame by remember { mutableStateOf(false) }
    var liveReady by remember { mutableStateOf(false) }
    var liveT0 by remember { mutableStateOf(0L) }
    val lastFrameAt = remember { java.util.concurrent.atomic.AtomicLong(0) }

    /** Реальное время (unix, сек), которое сейчас показывает основной плеер в архиве. */
    fun currentWall(): Double {
        pendingSeekWall?.let { return it }
        val a = archive ?: return Api.nowSec(cam.server)
        if (exo.duration == C.TIME_UNSET) return a.lastWall
        return a.wallAt(exo.currentPosition)
    }

    /**
     * Показать кадр из записи на момент [t] (Frigate: /recordings/<ts>/snapshot.jpg).
     * Одновременно в полёте не больше двух запросов; пока они идут, запоминаем только самый
     * свежий момент. Ответы приходят в любом порядке, показываем только более новый запрос.
     */
    fun showPreviewAt(t: Double) {
        pendingT = t
        if (inFlight >= 2) return
        val next = pendingT ?: return
        pendingT = null
        inFlight++
        val seq = ++frameSeq
        previewJob = scope.launch {
            val bmp = Api.recordingFrame(cam, next.toLong())
            inFlight--
            if (bmp != null && scrubbing && seq > shownSeq) {
                shownSeq = seq
                frame = bmp.asImageBitmap()
                frameWall = next
            }
            val again = pendingT
            if (again != null && scrubbing) showPreviewAt(again)
        }
    }

    fun playLive(why: String) {
        Log.i(TAG, "playLive: $why")
        loadJob?.cancel()
        previewJob?.cancel()
        holdJob?.cancel()
        holdActive = false
        scrubbing = false
        inHistory = false
        paused = false
        error = null
        archive = null
        cursor = null
        needReload = false
        pendingSeekWall = null
        spanIdx = 0
        frame = null
        pendingT = null
        firstFrame = false
        liveReady = false
        liveT0 = SystemClock.elapsedRealtime()
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setTimeoutMs(10_000)
            .createMediaSource(MediaItem.fromUri("${cam.rtsp}/${cam.liveStream}"))
        exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = true
    }

    /** Загрузить окно архива вокруг момента [center] и встать на него. */
    fun loadWindow(center: Double) {
        loadJob?.cancel()
        lastCenter = center
        inHistory = true
        error = null
        needReload = false
        cursor = null
        loadJob = scope.launch {
            val nowW = Api.nowSec(cam.server)
            val span = Config.WINDOW_SPANS_SEC[spanIdx]
            val after = (center - span / 2).toLong()
            val before = minOf(nowW, center + span / 2).toLong() + 1
            val recs = try {
                Api.recordings(cam, after, before)
            } catch (e: Exception) {
                Log.e(TAG, "recordings failed", e)
                error = "нет связи с Frigate"
                scrubbing = false
                return@launch
            }
            if (recs.isEmpty()) {
                Log.w(TAG, "no recordings in $after..$before")
                error = "нет записи в это время"
                scrubbing = false
                if (archive == null) playLive("нет записей")
                return@launch
            }
            val a = Archive(recs)
            archive = a
            pendingSeekWall = center
            val url = "${cam.http}/vod/${cam.name}/start/$after/end/$before/master.m3u8"
            Log.i(TAG, "history window: $url (${recs.size} recs, span ${span}s)")
            val http = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(30000)
                .setDefaultRequestProperties(Auth.headers(cam.server))
            exo.setMediaSource(HlsMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(url)))
            exo.prepare()
            exo.playWhenReady = false
        }
    }

    /** Сдвинуть момент просмотра на [delta] секунд (минус — назад). */
    fun moveCursor(delta: Double) {
        val nowW = Api.nowSec(cam.server)
        if (!inHistory) {
            if (delta > 0) return
            // вход в архив из живого потока: записи отстают от «сейчас» на LIVE_EDGE_SEC
            inHistory = true
            paused = false
            error = null
            exo.playWhenReady = false
            val t = nowW - Config.LIVE_EDGE_SEC + delta
            cursor = t
            archive = null
            needReload = true
            scrubbing = true
            showPreviewAt(t)
            return
        }
        val base = cursor ?: currentWall()
        var t = base + delta
        if (delta > 0 && t >= nowW - Config.LIVE_EDGE_SEC) {
            playLive("→ дошли до живого края")
            return
        }
        t = t.coerceIn(nowW - Config.ARCHIVE_DAYS * 86400.0, nowW - Config.LIVE_EDGE_SEC)
        val a = archive
        val inWindow = a != null && pendingSeekWall == null && t >= a.firstWall && t <= a.lastWall
        if (inWindow && !holdActive) {
            // одиночное нажатие внутри загруженного окна: просто перематываем плеер
            cursor = null
            exo.seekTo(a!!.posMs(t))
            return
        }
        cursor = t
        scrubbing = true
        needReload = !inWindow
        exo.playWhenReady = false
        showPreviewAt(t)
    }

    /** Отпустили клавишу: применить выбранный момент. */
    fun commit() {
        Log.i(TAG, "commit cursor=$cursor needReload=$needReload")
        pendingT = null
        val t = cursor
        if (t == null) {
            exo.playWhenReady = !paused
            return
        }
        cursor = null
        val a = archive
        if (!needReload && a != null && pendingSeekWall == null) {
            exo.seekTo(a.posMs(t))
            exo.playWhenReady = !paused
            scope.launch {
                delay(400)
                scrubbing = false
            }
        } else {
            loadWindow(t)
        }
    }

    fun startHold(dir: Int) {
        holdJob?.cancel()
        holdJob = scope.launch {
            delay(Config.HOLD_START_DELAY_MS)
            holdActive = true
            val t0 = System.currentTimeMillis()
            while (isActive) {
                val held = (System.currentTimeMillis() - t0) / 1000.0
                val step = if (held < Config.HOLD_SLOW_PHASE_SEC) Config.HOLD_SLOW_STEP_SEC else Config.HOLD_FAST_STEP_SEC
                moveCursor(dir * step)
                delay(Config.HOLD_TICK_MS)
            }
        }
    }

    fun stopHold() {
        holdJob?.cancel()
        holdJob = null
        holdActive = false
        if (inHistory) commit()
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                Log.i(TAG, "state=$state inHistory=$inHistory pending=$pendingSeekWall")
                if (state == Player.STATE_READY) {
                    val wall = pendingSeekWall
                    val a = archive
                    if (wall != null && a != null) {
                        pendingSeekWall = null
                        exo.seekTo(a.posMs(wall))
                        exo.playWhenReady = !paused && !holdActive
                        scope.launch {
                            delay(500)
                            if (pendingSeekWall == null && cursor == null) scrubbing = false
                        }
                    }
                } else if (state == Player.STATE_ENDED && inHistory) {
                    val a = archive
                    if (a != null) {
                        Log.i(TAG, "ENDED в архиве, lastWall=${a.lastWall}")
                        if (a.lastWall >= Api.nowSec(cam.server) - Config.LIVE_EDGE_SEC - 5) playLive("конец архива у живого края") else loadWindow(a.lastWall + 1)
                    }
                }
            }

            override fun onRenderedFirstFrame() {
                Log.i(TAG, "первый кадр через ${SystemClock.elapsedRealtime() - liveT0} мс (inHistory=$inHistory)")
                firstFrame = true
                if (!inHistory && exo.isPlaying) liveReady = true
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) Log.i(TAG, "воспроизведение пошло через ${SystemClock.elapsedRealtime() - liveT0} мс")
                if (!inHistory && isPlaying && firstFrame) liveReady = true
            }

            override fun onPlayerError(e: PlaybackException) {
                Log.e(TAG, "player error", e)
                if (inHistory) {
                    if (spanIdx < Config.WINDOW_SPANS_SEC.lastIndex) {
                        spanIdx++
                        loadWindow(lastCenter)
                    } else {
                        error = e.errorCodeName
                    }
                } else {
                    error = e.errorCodeName
                    scope.launch {
                        delay(3000)
                        if (!inHistory) playLive("повтор после ошибки")
                    }
                }
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            exo.release()
        }
    }

    // RTSP: метки времени кадров идут от начала сессии, а часы плеера от нуля. Если ключевой кадр пришёл не сразу,
    // плеер «отстаёт» от своего буфера на время ожидания и новые кадры не показываются, пока часы не дойдут.
    // Перематывать живой RTSP нельзя (плеер застревает), поэтому ускоряем часы, пока отставание не исчезнет.
    LaunchedEffect(Unit) {
        while (true) {
            val want = if (!inHistory && exo.playbackState == Player.STATE_READY && exo.isPlaying) {
                val lag = exo.bufferedPosition - exo.currentPosition
                (1f + (lag - Config.LIVE_CATCHUP_TARGET_MS) / Config.LIVE_CATCHUP_TAU_MS.toFloat())
                    .coerceIn(1f, Config.LIVE_CATCHUP_MAX_SPEED)
            } else {
                1f
            }
            if (kotlin.math.abs(exo.playbackParameters.speed - want) > 0.15f) exo.setPlaybackSpeed(want)
            delay(50)
        }
    }

    // диагностика живого потока: сколько кадров реально отрисовано и какие у них метки времени
    LaunchedEffect(Unit) {
        var frames = 0
        var lastPtsMs = 0L
        exo.setVideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
            frames++
            lastPtsMs = presentationTimeUs / 1000
            lastFrameAt.set(SystemClock.elapsedRealtime())
        }
        var prev = 0
        while (true) {
            if (!inHistory) {
                Log.d(
                    TAG,
                    "live: t=${SystemClock.elapsedRealtime() - liveT0}мс state=${exo.playbackState} playing=${exo.isPlaying} " +
                        "pos=${exo.currentPosition} buf=${exo.bufferedPosition} кадров+=${frames - prev} (всего $frames) pts=$lastPtsMs " +
                        "ready=$liveReady first=$firstFrame"
                )
            }
            prev = frames
            delay(500)
        }
    }

    LaunchedEffect(inHistory, liveReady) {
        while (!inHistory && !liveReady) {
            Api.snapshot(cam, 720)?.let { poster = it.asImageBitmap() }
            delay(1000)
        }
    }

    LaunchedEffect(status) { Log.i(TAG, "статус → $status") }

    LaunchedEffect(scrubbing) {
        if (!scrubbing) {
            frame = null
            pendingT = null
        }
    }

    LaunchedEffect(Unit) {
        playLive("старт")
        runCatching { focus.requestFocus() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            status = when {
                inHistory -> {
                    // во время перемотки метка соответствует показанному кадру, а не курсору
                    val wall = if (scrubbing && frame != null) frameWall else (cursor ?: currentWall())
                    val loading = pendingSeekWall != null || loadJob?.isActive == true
                    historyLabel(cam.server, wall, paused, loading)
                }
                !liveReady -> "○ подключение…"
                // состояние плеера (READY/BUFFERING) при почти пустом буфере мигает, поэтому смотрим на реальные кадры
                SystemClock.elapsedRealtime() - lastFrameAt.get() > Config.LIVE_STALL_MS -> "○ нет кадров…"
                else -> "● ЖИВОЙ"
            }
            delay(200)
        }
    }

    BackHandler(enabled = inHistory) { playLive("кнопка Назад") }

    // в архиве вокруг картинки чёрная рамка, чтобы сразу было видно, что это не живое видео
    val frameMargin = if (inHistory) 40.dp else 0.dp

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                val down = ev.type == KeyEventType.KeyDown
                val up = ev.type == KeyEventType.KeyUp
                when (ev.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        val dir = if (ev.key == Key.DirectionLeft) -1 else 1
                        if (down && ev.nativeKeyEvent.repeatCount == 0) {
                            moveCursor(dir * Config.TAP_STEP_SEC)
                            startHold(dir)
                        }
                        if (up) stopHold()
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> {
                        if (up && inHistory) {
                            paused = !paused
                            exo.playWhenReady = !paused
                        }
                        true
                    }
                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    useController = false
                    player = exo
                    keepScreenOn = true
                    isFocusable = false
                }
            },
            modifier = Modifier.fillMaxSize().padding(frameMargin),
        )
        if (!inHistory && !liveReady) {
            poster?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (scrubbing) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                frame?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(frameMargin),
                    )
                }
            }
        }
        Text(
            text = "${cam.name}   $status" + (error?.let { "   ⚠ $it" } ?: ""),
            color = Color.White,
            fontSize = 20.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(if (inHistory) 4.dp else 24.dp)
                .background(Color(0x99000000))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (inHistory) {
            Text(
                text = "←/→ удержание: перемотка   OK: пауза   Назад: к живому",
                color = Color(0xFFCCCCCC),
                fontSize = 16.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(4.dp)
                    .background(Color(0x99000000))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

private fun historyLabel(server: Server, wall: Double, paused: Boolean, loading: Boolean): String {
    val off = (Api.nowSec(server) - wall).toLong().coerceAtLeast(0)
    val behind = when {
        off >= 86400 -> "−%dд %dч".format(off / 86400, (off % 86400) / 3600)
        off >= 3600 -> "−%dч %02dм".format(off / 3600, (off % 3600) / 60)
        else -> "−%d:%02d".format(off / 60, off % 60)
    }
    val clock = clockFormat.format(Date((wall * 1000).toLong()))
    return "АРХИВ  $clock  $behind" + (if (loading) "  …" else "") + (if (paused) "  ⏸" else "")
}
