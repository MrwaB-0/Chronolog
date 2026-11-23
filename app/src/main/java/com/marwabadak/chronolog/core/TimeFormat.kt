package com.marwabadak.chronolog.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * 시간 표시 형식 유틸리티.
 *
 * 모든 함수는 Android 프레임워크에 의존하지 않는 순수 Kotlin 이므로
 * JVM 단위 테스트에서 그대로 검증한다.
 */
object TimeFormat {

    private val WEEKDAYS_KO = arrayOf("월", "화", "수", "목", "금", "토", "일")

    /** 스톱워치 경과 시간: HH:MM:SS.mmm */
    fun elapsedHms(elapsedMs: Long): String {
        val total = if (elapsedMs < 0L) 0L else elapsedMs
        val hour = total / 3_600_000L
        val minute = (total / 60_000L) % 60L
        val second = (total / 1_000L) % 60L
        val millis = total % 1_000L
        return String.format(Locale.KOREA, "%02d:%02d:%02d.%03d", hour, minute, second, millis)
    }

    /** 알림용 경과 시간: HH:MM:SS */
    fun elapsedHmsShort(elapsedMs: Long): String {
        val total = if (elapsedMs < 0L) 0L else elapsedMs
        val hour = total / 3_600_000L
        val minute = (total / 60_000L) % 60L
        val second = (total / 1_000L) % 60L
        return String.format(Locale.KOREA, "%02d:%02d:%02d", hour, minute, second)
    }

    /** 알림용 시각: HH:mm:ss */
    fun timeHms(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone)
        return String.format(
            Locale.KOREA, "%02d:%02d:%02d",
            t.hour, t.minute, t.second
        )
    }

    /** 시각 전체 표기: YYYY-MM-DD HH:mm:ss.SSS */
    fun clock(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone)
        return String.format(
            Locale.KOREA, "%04d-%02d-%02d %02d:%02d:%02d.%03d",
            t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second, t.nano / 1_000_000
        )
    }

    /** 날짜 + 요일: YYYY-MM-DD (요일) */
    fun dateWithWeekday(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone)
        return String.format(
            Locale.KOREA, "%04d-%02d-%02d (%s)",
            t.year, t.monthValue, t.dayOfMonth, WEEKDAYS_KO[t.dayOfWeek.value - 1]
        )
    }

    /** 시간만 표기: HH:mm:ss.SSS */
    fun timeOfDay(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone)
        return String.format(
            Locale.KOREA, "%02d:%02d:%02d.%03d",
            t.hour, t.minute, t.second, t.nano / 1_000_000
        )
    }

    /** 소요 시간을 한국어 단위로 표기: 00시간 00분 00초 000밀리초 */
    fun koreanDuration(elapsedMs: Long): String {
        val total = if (elapsedMs < 0L) 0L else elapsedMs
        val hour = total / 3_600_000L
        val minute = (total / 60_000L) % 60L
        val second = (total / 1_000L) % 60L
        val millis = total % 1_000L
        return String.format(Locale.KOREA, "%02d시간 %02d분 %02d초 %03d밀리초", hour, minute, second, millis)
    }
}
