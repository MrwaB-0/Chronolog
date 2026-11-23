package com.marwabadak.chronolog

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.marwabadak.chronolog.core.CalendarHelper
import com.marwabadak.chronolog.core.RecordCodec
import com.marwabadak.chronolog.core.RecordEntry
import com.marwabadak.chronolog.core.RecordStore
import com.marwabadak.chronolog.core.StopwatchEngine
import com.marwabadak.chronolog.core.TickPlayer
import com.marwabadak.chronolog.core.TimeFormat
import com.marwabadak.chronolog.ui.ChronographDialView

class MainActivity : Activity() {

    private lateinit var store: RecordStore
    private lateinit var tickPlayer: TickPlayer

    /** 화면이 꺼져도 흐른 시간이 유지되는 단조 시계를 사용한다. */
    private val engine = StopwatchEngine { SystemClock.elapsedRealtimeNanos() }

    private lateinit var dial: ChronographDialView
    private lateinit var tvDigital: TextView
    private lateinit var tvWindow: TextView
    private lateinit var tvRecordCount: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var btnStartStop: Button
    private lateinit var btnReset: Button
    private lateinit var btnSound: Button
    private lateinit var btnPickCalendar: Button
    private lateinit var btnClearAll: Button
    private lateinit var switchAutoCalendar: Switch
    private lateinit var recordsContainer: LinearLayout
    private lateinit var scrollRoot: ScrollView

    private var records: MutableList<RecordEntry> = mutableListOf()
    private var lastTickSecond = -1L
    private var frameScheduled = false
    private var suppressSwitch = false
    private var pendingPermissionAction: (() -> Unit)? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameScheduled = false
            if (!engine.running) return
            renderRunning(engine.elapsedMs())
            scheduleFrame()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = RecordStore(this)
        tickPlayer = TickPlayer()

        dial = findViewById(R.id.dial)
        tvDigital = findViewById(R.id.tvDigital)
        tvWindow = findViewById(R.id.tvWindow)
        tvRecordCount = findViewById(R.id.tvRecordCount)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnStartStop = findViewById(R.id.btnStartStop)
        btnReset = findViewById(R.id.btnReset)
        btnSound = findViewById(R.id.btnSound)
        btnPickCalendar = findViewById(R.id.btnPickCalendar)
        btnClearAll = findViewById(R.id.btnClearAll)
        switchAutoCalendar = findViewById(R.id.switchAutoCalendar)
        recordsContainer = findViewById(R.id.recordsContainer)
        scrollRoot = findViewById(R.id.scrollRoot)

        records = store.loadRecords()

        btnStartStop.setOnClickListener { if (engine.running) stopMeasurement() else startMeasurement() }
        btnReset.setOnClickListener { resetMeasurement() }
        btnSound.setOnClickListener { toggleSound() }
        btnPickCalendar.setOnClickListener { pickCalendar() }
        btnClearAll.setOnClickListener { confirmClearAll() }

        updateSoundButton(store.soundEnabled)
        updateCalendarButton()

        suppressSwitch = true
        switchAutoCalendar.isChecked = store.autoCalendarEnabled
        suppressSwitch = false
        switchAutoCalendar.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            onAutoCalendarToggled(isChecked)
        }

        renderRecords()
        renderIdle()
    }

    override fun onResume() {
        super.onResume()
        updateCalendarButton()
        if (engine.running) {
            scheduleFrame()
        }
    }

    override fun onPause() {
        super.onPause()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameScheduled = false
    }

    override fun onDestroy() {
        super.onDestroy()
        tickPlayer.release()
    }

    // ---------------------------------------------------------------- 측정

    private fun startMeasurement() {
        if (engine.running) return
        lastTickSecond = -1L
        tickPlayer.resetPhase()
        engine.start(System.currentTimeMillis())
        dial.running = true
        dial.elapsedMs = 0L
        btnStartStop.setText(R.string.btn_stop)
        btnStartStop.setBackgroundResource(R.drawable.btn_stop)
        btnStartStop.setTextColor(0xFF2A0A05.toInt())
        btnReset.isEnabled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        renderRunning(0L)
        scheduleFrame()
    }

    private fun stopMeasurement() {
        val elapsed = engine.stop()
        val startEpoch = engine.startEpochMs
        val endEpoch = startEpoch + elapsed

        dial.running = false
        dial.elapsedMs = elapsed
        btnStartStop.setText(R.string.btn_start)
        btnStartStop.setBackgroundResource(R.drawable.btn_start)
        btnStartStop.setTextColor(0xFF04231A.toInt())
        btnReset.isEnabled = true
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tvDigital.text = TimeFormat.elapsedHms(elapsed)
        tvWindow.text = getString(
            R.string.window_stopped,
            TimeFormat.clock(startEpoch),
            TimeFormat.clock(endEpoch)
        )
        if (store.soundEnabled) tickPlayer.tick()

        val record = RecordEntry(RecordCodec.nextIndex(records), startEpoch, endEpoch, elapsed)
        records = store.appendRecord(records, record)
        renderRecords()

        if (store.autoCalendarEnabled) autoRegister(record)
    }

    private fun resetMeasurement() {
        if (engine.running) return
        engine.reset()
        dial.elapsedMs = 0L
        renderIdle()
        btnReset.isEnabled = false
    }

    private fun renderIdle() {
        tvDigital.text = getString(R.string.digital_zero)
        tvWindow.text = getString(R.string.window_idle)
    }

    private fun renderRunning(elapsed: Long) {
        dial.elapsedMs = elapsed
        tvDigital.text = TimeFormat.elapsedHms(elapsed)
        val nowEpoch = engine.startEpochMs + elapsed
        tvWindow.text = getString(
            R.string.window_running,
            TimeFormat.clock(engine.startEpochMs),
            TimeFormat.clock(nowEpoch)
        )
        val currentSecond = elapsed / 1000L
        if (currentSecond > lastTickSecond) {
            lastTickSecond = currentSecond
            if (store.soundEnabled) tickPlayer.tick()
        }
    }

    private fun scheduleFrame() {
        if (frameScheduled) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    // ---------------------------------------------------------------- 소리

    private fun toggleSound() {
        val enabled = !store.soundEnabled
        store.soundEnabled = enabled
        updateSoundButton(enabled)
        if (enabled) tickPlayer.tick()
    }

    private fun updateSoundButton(enabled: Boolean) {
        btnSound.setText(if (enabled) R.string.sound_on else R.string.sound_off)
        btnSound.setTextColor(if (enabled) 0xFF2FE3A6.toInt() else 0xFF8296A6.toInt())
    }

    // ---------------------------------------------------------------- 기록

    private fun renderRecords() {
        recordsContainer.removeAllViews()
        tvRecordCount.text = getString(R.string.records_count, records.size)
        tvEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        for (record in records) {
            val item = inflater.inflate(R.layout.item_record, recordsContainer, false)
            item.findViewById<TextView>(R.id.tvRecordIndex).text = "#${record.index}"
            item.findViewById<TextView>(R.id.tvRecordDate).text = TimeFormat.dateWithWeekday(record.startEpochMs)
            item.findViewById<TextView>(R.id.tvRecordWindow).text =
                TimeFormat.timeOfDay(record.startEpochMs) + " ~ " + TimeFormat.timeOfDay(record.endEpochMs)
            item.findViewById<TextView>(R.id.tvRecordDuration).text = TimeFormat.koreanDuration(record.elapsedMs)
            item.findViewById<TextView>(R.id.tvRecordPrecise).text = TimeFormat.elapsedHms(record.elapsedMs)
            item.findViewById<Button>(R.id.btnRecordCalendar).setOnClickListener { registerToCalendar(record) }
            item.findViewById<Button>(R.id.btnRecordDelete).setOnClickListener { confirmDelete(record) }
            recordsContainer.addView(item)
        }
    }

    private fun confirmDelete(record: RecordEntry) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.record_delete_title)
            .setMessage(getString(R.string.record_delete_message, record.index))
            .setPositiveButton(R.string.delete) { _, _ ->
                records = store.deleteRecord(records, record.index)
                renderRecords()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmClearAll() {
        if (records.isEmpty()) {
            toast(getString(R.string.records_count, 0))
            return
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.clear_all_title)
            .setMessage(getString(R.string.clear_all_message, records.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                store.clearRecords()
                records = mutableListOf()
                renderRecords()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- 캘린더

    private fun withCalendarPermission(action: () -> Unit) {
        if (CalendarHelper.hasPermission(this)) {
            action()
            return
        }
        pendingPermissionAction = action
        requestPermissions(
            arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            REQUEST_CALENDAR
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CALENDAR) return
        val action = pendingPermissionAction
        pendingPermissionAction = null
        if (CalendarHelper.hasPermission(this)) {
            updateCalendarButton()
            action?.invoke()
        } else {
            setAutoCalendarChecked(false, persist = true)
            toast(getString(R.string.calendar_permission_needed))
        }
    }

    private fun updateCalendarButton() {
        val label = store.calendarLabel
        btnPickCalendar.text = if (label.isBlank()) {
            getString(R.string.calendar_pick)
        } else {
            getString(R.string.calendar_current, label)
        }
    }

    private fun pickCalendar() {
        withCalendarPermission {
            val calendars = CalendarHelper.writableCalendars(this)
            if (calendars.isEmpty()) {
                toast(getString(R.string.calendar_no_calendars))
                return@withCalendarPermission
            }
            val ordered = calendars.sortedByDescending { it.isSamsung }
            val labels = ordered.map { if (it.isSamsung) "★ " + it.label else it.label }.toTypedArray()
            val current = ordered.indexOfFirst { it.id == store.calendarId }
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.calendar_dialog_title)
                .setSingleChoiceItems(labels, current) { dialog, which ->
                    val chosen = ordered[which]
                    store.calendarId = chosen.id
                    store.calendarLabel = chosen.label
                    updateCalendarButton()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun onAutoCalendarToggled(isChecked: Boolean) {
        if (!isChecked) {
            store.autoCalendarEnabled = false
            return
        }
        withCalendarPermission {
            if (store.calendarId <= 0L) {
                setAutoCalendarChecked(false, persist = true)
                toast(getString(R.string.calendar_select_first))
                pickCalendar()
            } else {
                store.autoCalendarEnabled = true
            }
        }
    }

    private fun setAutoCalendarChecked(checked: Boolean, persist: Boolean) {
        suppressSwitch = true
        switchAutoCalendar.isChecked = checked
        suppressSwitch = false
        if (persist) store.autoCalendarEnabled = checked
    }

    private fun autoRegister(record: RecordEntry) {
        val calendarId = store.calendarId
        if (calendarId <= 0L) return
        if (!CalendarHelper.hasPermission(this)) return
        val uri = CalendarHelper.insertEvent(
            this,
            calendarId,
            CalendarHelper.eventTitle(record),
            CalendarHelper.eventDescription(record),
            record.startEpochMs,
            record.endEpochMs
        )
        if (uri != null) {
            val name = store.calendarLabel.ifBlank { getString(R.string.calendar_pick) }
            toast(getString(R.string.calendar_saved, name))
        }
    }

    private fun registerToCalendar(record: RecordEntry) {
        fun insert() {
            if (store.calendarId <= 0L) {
                toast(getString(R.string.calendar_select_first))
                pickCalendar()
                return
            }
            val uri = CalendarHelper.insertEvent(
                this,
                store.calendarId,
                CalendarHelper.eventTitle(record),
                CalendarHelper.eventDescription(record),
                record.startEpochMs,
                record.endEpochMs
            )
            if (uri != null) {
                val name = store.calendarLabel.ifBlank { getString(R.string.calendar_pick) }
                toast(getString(R.string.calendar_saved, name))
            } else {
                toast(getString(R.string.calendar_failed_insert))
                val opened = CalendarHelper.openInsertIntent(
                    this,
                    CalendarHelper.eventTitle(record),
                    CalendarHelper.eventDescription(record),
                    record.startEpochMs,
                    record.endEpochMs
                )
                if (!opened) toast(getString(R.string.calendar_open_app))
            }
        }
        withCalendarPermission { insert() }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQUEST_CALENDAR = 4101
    }
}
