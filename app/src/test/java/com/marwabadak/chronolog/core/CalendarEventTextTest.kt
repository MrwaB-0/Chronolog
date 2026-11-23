package com.marwabadak.chronolog.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CalendarEventTextTest {

    private val seoul: ZoneId = ZoneId.of("Asia/Seoul")

    private val record = run {
        val start = ZonedDateTime.of(2026, 11, 23, 18, 33, 26, 123_000_000, seoul).toInstant().toEpochMilli()
        val elapsed = 26_333L
        RecordEntry(index = 7, startEpochMs = start, endEpochMs = start + elapsed, elapsedMs = elapsed)
    }

    @Test
    fun title_containsIndex() {
        assertEquals("#7 스톱워치 측정", CalendarHelper.eventTitle(record))
    }

    @Test
    fun description_containsAllFourRequiredFacts() {
        val text = CalendarHelper.eventDescription(record)
        assertTrue("회차", text.contains("회차: #7"))
        assertTrue("날짜", text.contains(TimeFormat.dateWithWeekday(record.startEpochMs)))
        assertTrue("시작 시각", text.contains(TimeFormat.clock(record.startEpochMs)))
        assertTrue("종료 시각", text.contains(TimeFormat.clock(record.endEpochMs)))
        assertTrue("소요 시간", text.contains("00시간 00분 26초 333밀리초"))
    }
}
