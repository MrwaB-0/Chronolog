package com.marwabadak.chronolog.core

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.core.content.ContextCompat
import java.util.TimeZone

/** 기기에 등록된 캘린더 한 개. */
data class CalendarAccount(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val isSamsung: Boolean
) {
    val label: String
        get() = if (accountName.isBlank() || accountName == displayName) displayName else "$displayName · $accountName"
}

/**
 * 삼성 캘린더(기본 캘린더 포함) 연동.
 *
 * - [writableCalendars] : 기기에 있는 캘린더 목록 (쓰기 가능한 것만)
 * - [insertEvent]       : CalendarContract.Events 에 일정을 직접 등록 (원터치)
 * - [openInsertIntent]  : 직접 등록이 불가할 때 삼성 캘린더 앱의 새 일정 화면을 여는 폴백
 */
object CalendarHelper {

    fun hasPermission(context: Context): Boolean {
        val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
        val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)
        return read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED
    }

    fun writableCalendars(context: Context): List<CalendarAccount> {
        val result = mutableListOf<CalendarAccount>()
        val projection = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
            Calendars.ACCOUNT_TYPE,
            Calendars.CALENDAR_ACCESS_LEVEL
        )
        try {
            context.contentResolver.query(
                Calendars.CONTENT_URI, projection, null, null,
                Calendars.CALENDAR_DISPLAY_NAME + " ASC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(Calendars._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(Calendars.CALENDAR_DISPLAY_NAME)
                val accountIndex = cursor.getColumnIndexOrThrow(Calendars.ACCOUNT_NAME)
                val typeIndex = cursor.getColumnIndexOrThrow(Calendars.ACCOUNT_TYPE)
                val accessIndex = cursor.getColumnIndexOrThrow(Calendars.CALENDAR_ACCESS_LEVEL)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val access = cursor.getInt(accessIndex)
                    if (access < Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                    val name = cursor.getString(nameIndex) ?: continue
                    val account = cursor.getString(accountIndex) ?: ""
                    val type = cursor.getString(typeIndex) ?: ""
                    val samsung = type.contains("samsung", true) || name.contains("삼성") || name.contains("samsung", true)
                    result.add(CalendarAccount(id, name, account, samsung))
                }
            }
        } catch (t: Throwable) {
            // 권한 문제나 캘린더 공급자 오류. 빈 목록으로 처리한다.
        }
        return result
    }

    /**
     * 일정을 캘린더에 직접 등록한다.
     * @return 등록된 이벤트 Uri, 실패하면 null
     */
    fun insertEvent(
        context: Context,
        calendarId: Long,
        title: String,
        description: String,
        startMs: Long,
        endMs: Long
    ): Uri? {
        if (calendarId <= 0L) return null
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.TITLE, title)
            put(Events.DESCRIPTION, description)
            put(Events.DTSTART, startMs)
            put(Events.DTEND, if (endMs > startMs) endMs else startMs + 1_000L)
            put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(Events.HAS_ALARM, 0)
            put(Events.EVENT_LOCATION, "")
        }
        return try {
            context.contentResolver.insert(Events.CONTENT_URI, values)
        } catch (t: Throwable) {
            null
        }
    }

    /** 삼성 캘린더 앱의 새 일정 화면을 연다. (직접 등록 실패 시 폴백) */
    fun openInsertIntent(
        context: Context,
        title: String,
        description: String,
        startMs: Long,
        endMs: Long
    ): Boolean {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = Events.CONTENT_URI
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, if (endMs > startMs) endMs else startMs + 1_000L)
            putExtra(Events.TITLE, title)
            putExtra(Events.DESCRIPTION, description)
            putExtra(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 일정 제목: `#회차 스톱워치 측정` */
    fun eventTitle(record: RecordEntry): String = "#${record.index} 스톱워치 측정"

    /** 일정 설명: 회차 · 측정 날짜 · 시작~종료 시각 · 소요 시간을 모두 담는다. */
    fun eventDescription(record: RecordEntry): String = buildString {
        append("Chronolog 스톱워치 기록")
        append('\n')
        append("회차: #").append(record.index)
        append('\n')
        append("측정 날짜: ").append(TimeFormat.dateWithWeekday(record.startEpochMs))
        append('\n')
        append("측정 구간: ").append(TimeFormat.clock(record.startEpochMs))
            .append(" ~ ").append(TimeFormat.clock(record.endEpochMs))
        append('\n')
        append("소요 시간: ").append(TimeFormat.koreanDuration(record.elapsedMs))
        append('\n')
        append("정밀 표기: ").append(TimeFormat.elapsedHms(record.elapsedMs))
    }
}
