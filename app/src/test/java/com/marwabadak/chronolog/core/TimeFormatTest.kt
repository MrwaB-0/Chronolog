package com.marwabadak.chronolog.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TimeFormatTest {

    private val seoul: ZoneId = ZoneId.of("Asia/Seoul")

    private val sampleEpochMs: Long = ZonedDateTime
        .of(2026, 11, 23, 18, 33, 26, 123_000_000, seoul)
        .toInstant()
        .toEpochMilli()

    @Test
    fun elapsedHms_formatsMilliseconds() {
        assertEquals("00:00:00.000", TimeFormat.elapsedHms(0L))
        assertEquals("00:00:01.001", TimeFormat.elapsedHms(1_001L))
        assertEquals("01:02:03.004", TimeFormat.elapsedHms(3_723_004L))
        assertEquals("10:00:00.000", TimeFormat.elapsedHms(36_000_000L))
    }

    @Test
    fun elapsedHms_clampsNegativeValue() {
        assertEquals("00:00:00.000", TimeFormat.elapsedHms(-500L))
    }

    @Test
    fun elapsedHmsShort_dropsMillisecondsForNotification() {
        assertEquals("00:00:00", TimeFormat.elapsedHmsShort(0L))
        assertEquals("00:00:26", TimeFormat.elapsedHmsShort(26_999L))
        assertEquals("01:02:03", TimeFormat.elapsedHmsShort(3_723_004L))
        assertEquals("12:00:00", TimeFormat.elapsedHmsShort(43_200_000L))
        assertEquals("00:00:00", TimeFormat.elapsedHmsShort(-1L))
    }

    @Test
    fun timeHms_formatsClockWithoutMillis() {
        assertEquals("18:33:26", TimeFormat.timeHms(sampleEpochMs, seoul))
        // epoch 0 은 UTC 자정이므로 서울 기준 09:00:00 이다.
        assertEquals("09:00:00", TimeFormat.timeHms(0L, seoul))
    }

    @Test
    fun koreanDuration_usesKoreanUnits() {
        assertEquals("00시간 00분 00초 000밀리초", TimeFormat.koreanDuration(0L))
        assertEquals("01시간 02분 03초 004밀리초", TimeFormat.koreanDuration(3_723_004L))
        assertEquals("00시간 00분 26초 333밀리초", TimeFormat.koreanDuration(26_333L))
    }

    @Test
    fun clockAndWeekday_useRequestedZone() {
        assertEquals("2026-11-23 18:33:26.123", TimeFormat.clock(sampleEpochMs, seoul))
        assertEquals("2026-11-23 (월)", TimeFormat.dateWithWeekday(sampleEpochMs, seoul))
        assertEquals("18:33:26.123", TimeFormat.timeOfDay(sampleEpochMs, seoul))
    }

    @Test
    fun weekday_isCorrectForEveryDayOfWeek() {
        // 2026-11-23 은 월요일. 하루씩 더해 요일 표기를 확인한다.
        val expected = listOf("월", "화", "수", "목", "금", "토", "일")
        for (offset in 0 until 7) {
            val epoch = sampleEpochMs + offset * 86_400_000L
            val text = TimeFormat.dateWithWeekday(epoch, seoul)
            assertEquals(true, text.endsWith("(${expected[offset]})"))
        }
    }
}
