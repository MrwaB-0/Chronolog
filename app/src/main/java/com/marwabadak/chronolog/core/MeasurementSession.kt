package com.marwabadak.chronolog.core

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 앱과 포그라운드 서비스가 함께 쓰는 측정 세션.
 *
 * 측정 상태를 프로세스 전역에 두므로 화면(Activity)이 사라지거나 화면이 꺼져도
 * 알림([com.marwabadak.chronolog.service.StopwatchService])이 같은 세션을 계속 갱신할 수 있다.
 * 경과 시간은 [SystemClock.elapsedRealtimeNanos] 기반이라 기기가 잠들어도 정확하다.
 */
object MeasurementSession {

    /** 측정 상태가 바뀌면 호출된다. (시작, 정지와 저장, 초기화) */
    interface Listener {
        fun onMeasurementChanged()
    }

    private val engine = StopwatchEngine { SystemClock.elapsedRealtimeNanos() }
    private val listeners = CopyOnWriteArrayList<Listener>()

    /** 현재 측정 회차. 측정 중이 아니면 0. */
    var index: Int = 0
        private set

    private var lastRecord: RecordEntry? = null

    val isRunning: Boolean
        get() = engine.running

    val startEpochMs: Long
        get() = engine.startEpochMs

    /** 마지막으로 저장한 측정 기록. 아직 없으면 null. */
    fun lastResult(): RecordEntry? = lastRecord

    fun elapsedMs(): Long = engine.elapsedMs()

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** 측정을 시작하고 이번 회차 번호를 돌려준다. 이미 측정 중이면 현재 회차를 돌려준다. */
    fun start(context: Context): Int {
        if (engine.running) return index
        val store = RecordStore(context)
        index = RecordCodec.nextIndex(store.loadRecords())
        lastRecord = null
        engine.start(System.currentTimeMillis())
        notifyChanged()
        return index
    }

    /** 측정을 정지하고 기록을 저장한다. 측정 중이 아니면 null. */
    fun stopAndSave(context: Context): StopResult? {
        if (!engine.running) return null
        val elapsed = engine.stop()
        val start = engine.startEpochMs
        val end = start + elapsed

        val store = RecordStore(context)
        val records = store.loadRecords()
        val record = RecordEntry(RecordCodec.nextIndex(records), start, end, elapsed)
        store.appendRecord(records, record)
        lastRecord = record

        var calendarSaved = false
        if (store.autoCalendarEnabled && store.calendarId > 0L && CalendarHelper.hasPermission(context)) {
            val uri = CalendarHelper.insertEvent(
                context,
                store.calendarId,
                CalendarHelper.eventTitle(record),
                CalendarHelper.eventDescription(record),
                start,
                end
            )
            calendarSaved = uri != null
        }

        notifyChanged()
        return StopResult(record, calendarSaved)
    }

    /** 표시 상태만 초기화한다. 저장된 기록은 그대로 남는다. */
    fun reset() {
        engine.reset()
        index = 0
        lastRecord = null
        notifyChanged()
    }

    private fun notifyChanged() {
        for (listener in listeners) {
            try {
                listener.onMeasurementChanged()
            } catch (t: Throwable) {
                // 리스너 하나의 실패가 다른 쪽 상태 갱신을 막지 않게 한다.
            }
        }
    }

    /** 측정 정지 결과. [calendarSaved] 는 자동 등록 성공 여부. */
    data class StopResult(
        val record: RecordEntry,
        val calendarSaved: Boolean
    )
}
