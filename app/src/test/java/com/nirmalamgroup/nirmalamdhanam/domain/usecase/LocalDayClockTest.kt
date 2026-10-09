package com.nirmalamgroup.nirmalamdhanam.domain.usecase

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalDayClockTest {

    @Test
    fun `india today starts at local midnight not UTC midnight`() {
        val zone = ZoneId.of("Asia/Kolkata")
        val now = Instant.parse("2026-10-07T00:45:00Z").toEpochMilli() // 06:15 IST

        val window = LocalDayClock.window(now, zone)

        assertEquals(LocalDate.of(2026, 10, 7), window.date)
        assertEquals("Asia/Kolkata", window.zoneId)
        assertEquals(
            Instant.parse("2026-10-06T18:30:00Z").toEpochMilli(),
            window.startEpochMs,
        )
        assertEquals(
            Instant.parse("2026-10-07T18:30:00Z").toEpochMilli(),
            window.endEpochMs,
        )
    }

    @Test
    fun `day length follows timezone rules rather than assuming 24 hours`() {
        val zone = ZoneId.of("America/New_York")
        val now = Instant.parse("2026-11-01T16:00:00Z").toEpochMilli()

        val window = LocalDayClock.window(now, zone)

        // DST ends on 2026-11-01 in New York, so the local calendar day is 25 hours.
        assertEquals(25L * 60L * 60L * 1000L, window.endEpochMs - window.startEpochMs)
    }

    @Test
    fun `boundary recheck is prompt near midnight and periodic otherwise`() {
        val zone = ZoneId.of("Asia/Kolkata")
        val midday = Instant.parse("2026-10-07T06:30:00Z").toEpochMilli() // noon IST
        val middayWindow = LocalDayClock.window(midday, zone)
        assertEquals(60_000L, LocalDayClock.delayUntilBoundaryRecheck(midday, middayWindow))

        val nearMidnight = Instant.parse("2026-10-07T18:29:59.900Z").toEpochMilli()
        val nearWindow = LocalDayClock.window(nearMidnight, zone)
        val delay = LocalDayClock.delayUntilBoundaryRecheck(nearMidnight, nearWindow)
        assertTrue(delay in 250L..1_000L)
    }
}
