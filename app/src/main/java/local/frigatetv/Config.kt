package local.frigatetv

/** Параметры по умолчанию. Адреса серверов задаются в приложении (Настройки). */
object Config {
    const val APP_NAME = "Frigate TV"
    const val APP_VERSION = "0.4"
    const val APP_DATE = "30.10.2026"

    const val FRIGATE_PORT = 5000
    const val RTSP_PORT = 8554
    const val GO2RTC_PORT = 1984

    /** Высота снимков в сетке, пикселей. */
    const val SNAPSHOT_HEIGHT = 360

    /** Как часто обновлять снимки в сетке. */
    const val SNAPSHOT_INTERVAL_MS = 1000L

    /** Размеры окна архива (сек), которые пробуем по очереди: Frigate не отдаёт HLS длиннее ~4 ч. */
    val WINDOW_SPANS_SEC = longArrayOf(3 * 3600, 2 * 3600, 3600)

    /** Глубина хранения записей во Frigate, дней (дальше назад перематывать некуда). */
    const val ARCHIVE_DAYS = 10

    /** Записи отстают от живого потока; ближе этого к «сейчас» считаем, что мы у живого края. */
    const val LIVE_EDGE_SEC = 25.0

    /**
     * Живой поток: плеер догоняет край буфера, ускоряя воспроизведение: скорость = 1 + (отставание - TARGET) / TAU,
     * не больше MAX. Нужно, потому что метки времени первого ключевого кадра отстают от часов плеера.
     */
    const val LIVE_CATCHUP_TARGET_MS = 1600L
    const val LIVE_CATCHUP_TAU_MS = 900L
    const val LIVE_CATCHUP_MAX_SPEED = 12f

    /** Если живых кадров нет дольше этого времени, в подписи вместо «ЖИВОЙ» будет «нет кадров». */
    const val LIVE_STALL_MS = 3000L
    /** Если кадров нет дольше этого, живой поток переподключается. */
    const val LIVE_RESTART_MS = 5000L

    /** Одиночное нажатие ← / →. */
    const val TAP_STEP_SEC = 10.0

    /** Удержание: пауза до начала непрерывной перемотки и период тиков. */
    const val HOLD_START_DELAY_MS = 450L
    const val HOLD_TICK_MS = 500L

    /** Первые HOLD_SLOW_PHASE_SEC секунд удержания шаг HOLD_SLOW_STEP_SEC, потом HOLD_FAST_STEP_SEC. */
    const val HOLD_SLOW_PHASE_SEC = 10.0
    const val HOLD_SLOW_STEP_SEC = 60.0
    const val HOLD_FAST_STEP_SEC = 1800.0

    /** Высота кадров из записей при быстрой перемотке (пикселей). */
    const val FRAME_HEIGHT = 480
}
