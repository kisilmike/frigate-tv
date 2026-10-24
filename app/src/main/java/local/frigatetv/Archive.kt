package local.frigatetv

/** Один записанный сегмент: начало (unix, сек) и длительность (сек). */
data class Rec(val start: Double, val dur: Double)

/**
 * Загруженное окно архива. HLS-плейлист Frigate склеивает записи подряд без пропусков,
 * поэтому позиция плеера — это накопленная длительность записей, а не реальное время.
 * Этот класс переводит одно в другое.
 */
class Archive(private val recs: List<Rec>) {
    private val cum = DoubleArray(recs.size)

    init {
        var sum = 0.0
        for (i in recs.indices) {
            cum[i] = sum
            sum += recs[i].dur
        }
    }

    private val total = recs.sumOf { it.dur }

    val firstWall: Double get() = recs.first().start
    val lastWall: Double get() = recs.last().start + recs.last().dur

    /** Позиция плеера (мс) для реального времени [wall]; внутри пропуска — начало следующего сегмента. */
    fun posMs(wall: Double): Long {
        val i = lastIndexStartingAtOrBefore(wall)
        if (i < 0) return 0
        val r = recs[i]
        val sec = if (wall >= r.start + r.dur) {
            if (i + 1 < recs.size) cum[i + 1] else total
        } else {
            cum[i] + (wall - r.start)
        }
        return (sec * 1000).toLong()
    }

    /** Реальное время (unix, сек) для позиции плеера [posMs]. */
    fun wallAt(posMs: Long): Double {
        val sec = posMs / 1000.0
        var lo = 0
        var hi = recs.size - 1
        var idx = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] <= sec) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return recs[idx].start + (sec - cum[idx]).coerceAtMost(recs[idx].dur)
    }

    private fun lastIndexStartingAtOrBefore(wall: Double): Int {
        var lo = 0
        var hi = recs.size - 1
        var res = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (recs[mid].start <= wall) {
                res = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return res
    }
}
