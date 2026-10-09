package com.nirmalamgroup.nirmalamdhanam.domain.usecase

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Calendar-day boundaries for money calculations that are labelled "today".
 *
 * A day must follow the user's current local timezone; epoch-day arithmetic based on UTC
 * would make the boundary occur at 05:30 in India and at similarly incorrect wall-clock
 * times elsewhere. Call [window] again periodically because the device timezone/date can
 * change while the app remains open.
 */
data class LocalDayWindow(
    val date: LocalDate,
    val zoneId: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
)

object LocalDayClock {
    private const val MAX_BOUNDARY_RECHECK_MS = 60_000L
    private const val MIN_BOUNDARY_RECHECK_MS = 250L

    fun window(
        nowEpochMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): LocalDayWindow {
        val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return LocalDayWindow(
            date = today,
            zoneId = zone.id,
            startEpochMs = start,
            endEpochMs = end,
        )
    }

    /**
     * Re-check at least once per minute so timezone/date changes are noticed without an
     * app restart. Near midnight, wake just after the exact local boundary.
     */
    fun delayUntilBoundaryRecheck(
        nowEpochMs: Long,
        window: LocalDayWindow,
    ): Long = (window.endEpochMs - nowEpochMs + MIN_BOUNDARY_RECHECK_MS)
        .coerceIn(MIN_BOUNDARY_RECHECK_MS, MAX_BOUNDARY_RECHECK_MS)
}
