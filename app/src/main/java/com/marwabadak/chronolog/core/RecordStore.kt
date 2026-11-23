package com.marwabadak.chronolog.core

import android.content.Context

/**
 * 앱 설정과 측정 기록을 SharedPreferences 에 영구 저장한다.
 * 기록은 [RecordCodec] 형식의 문자열 하나로 보관한다.
 */
class RecordStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- 측정 기록 ----

    fun loadRecords(): MutableList<RecordEntry> = RecordCodec.decode(prefs.getString(KEY_RECORDS, null)).toMutableList()

    fun saveRecords(records: List<RecordEntry>) {
        prefs.edit().putString(KEY_RECORDS, RecordCodec.encode(records)).apply()
    }

    fun appendRecord(records: List<RecordEntry>, record: RecordEntry): MutableList<RecordEntry> {
        val updated = records.toMutableList()
        updated.add(0, record)
        saveRecords(updated)
        return updated
    }

    fun deleteRecord(records: List<RecordEntry>, index: Int): MutableList<RecordEntry> {
        val updated = records.filterNot { it.index == index }.toMutableList()
        saveRecords(updated)
        return updated
    }

    fun clearRecords() {
        prefs.edit().remove(KEY_RECORDS).apply()
    }

    // ---- 설정 ----

    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND, value).apply()

    var autoCalendarEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CALENDAR, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CALENDAR, value).apply()

    var calendarId: Long
        get() = prefs.getLong(KEY_CALENDAR_ID, -1L)
        set(value) = prefs.edit().putLong(KEY_CALENDAR_ID, value).apply()

    var calendarLabel: String
        get() = prefs.getString(KEY_CALENDAR_LABEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CALENDAR_LABEL, value).apply()

    companion object {
        private const val PREFS_NAME = "chronolog_prefs"
        private const val KEY_RECORDS = "records"
        private const val KEY_SOUND = "sound_enabled"
        private const val KEY_AUTO_CALENDAR = "auto_calendar_enabled"
        private const val KEY_CALENDAR_ID = "calendar_id"
        private const val KEY_CALENDAR_LABEL = "calendar_label"
    }
}
