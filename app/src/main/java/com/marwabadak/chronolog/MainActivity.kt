package com.marwabadak.chronolog

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.core.content.ContextCompat
import com.marwabadak.chronolog.core.CalendarHelper
import com.marwabadak.chronolog.core.MeasurementSession
import com.marwabadak.chronolog.core.RecordEntry
import com.marwabadak.chronolog.core.RecordStore
import com.marwabadak.chronolog.core.TimeFormat
import com.marwabadak.chronolog.service.StopwatchService
import com.marwabadak.chronolog.ui.ChronographDialView

class MainActivity : Activity() {

    private lateinit var store: RecordStore

    private lateinit var dial: ChronographDialView
    private lateinit var tvDigital: TextView
    private lateinit var tvWindow: TextView
    private lateinit var tvServiceState: TextView
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
    private var frameScheduled = false
    private var suppressSwitch = false
    private var pendingPermissionAction: (() -> Unit)? = null
    private var screenVisible = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameScheduled = false
            if (!MeasurementSession.isRunning) return
            renderRunning(MeasurementSession.elapsedMs())
            scheduleFrame()
        }
    }

    private val sessionListener = object : MeasurementSession.Listener {
        override fun onMeasurementChanged() {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                records = store.loadRecords()
                renderRecords()
                if (screenVisible) syncUiWithSession()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = RecordStore(this)

        dial = findViewById(R.id.dial)
        tvDigital = findViewById(R.id.tvDigital)
        tvWindow = findViewById(R.id.tvWindow)
        tvServiceState = findViewById(R.id.tvServiceState)
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

        btnStartStop.setOnClickListener { if (MeasurementSession.isRunning) stopMeasurement() else startMeasurement() }
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

        MeasurementSession.addListener(sessionListener)

        renderRecords()
        renderIdle()
    }

    override fun onResume() {
        super.onResume()
        screenVisible = true
        updateCalendarButton()
        records = store.loadRecords()
        renderRecords()
        syncUiWithSession()
    }

    override fun onPause() {
        super.onPause()
        screenVisible = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameScheduled = false
    }

    override fun onDestroy() {
        super.onDestroy()
        MeasurementSession.removeListener(sessionListener)
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    // ---------------------------------------------------------------- 측정

    private fun startMeasurement() {
        if (MeasurementSession.isRunning) return
        requestNotificationPermissionOnce()
        MeasurementSession.start(this)
        StopwatchService.start(this)
    }

    private fun stopMeasurement() {
        if (!MeasurementSession.isRunning) return
        val result = MeasurementSession.stopAndSave(this)
        StopwatchService.stop(this)
        if (result != null && result.calendarSaved) {
            val name = store.calendarLabel.ifBlank { getString(R.string.calendar_pick) }
            toast(getString(R.string.calendar_saved, name))
        }
    }

    private fun resetMeasurement() {
        if (MeasurementSession.isRunning) return
        MeasurementSession.reset()
        dial.running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameScheduled = false
    }

    /** 현재 세션 상태에 맞춰 화면 전체를 다시 맞춘다. */
    private fun syncUiWithSession() {
        if (MeasurementSession.isRunning) {
            dial.running = true
            updateStartStopButton(true)
            btnReset.isEnabled = false
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            tvServiceState.text = getString(R.string.background_state_running)
            renderRunning(MeasurementSession.elapsedMs())
            scheduleFrame()
            return
        }

        dial.running = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateStartStopButton(false)

        val record = MeasurementSession.lastResult()
        if (record != null) {
            btnReset.isEnabled = true
            dial.elapsedMs = record.elapsedMs
            tvDigital.text = TimeFormat.elapsedHms(record.elapsedMs)
            tvWindow.text = getString(
                R.string.window_stopped,
                TimeFormat.clock(record.startEpochMs),
                TimeFormat.clock(record.endEpochMs)
            )
            tvServiceState.text = getString(R.string.background_state_saved, record.index)
        } else {
            btnReset.isEnabled = false
            renderIdle()
        }
    }

    private fun updateStartStopButton(running: Boolean) {
        if (running) {
            btnStartStop.setText(R.string.btn_stop)
            btnStartStop.setBackgroundResource(R.drawable.btn_stop)
            btnStartStop.setTextColor(0xFF2A0A05.toInt())
        } else {
            btnStartStop.setText(R.string.btn_start)
            btnStartStop.setBackgroundResource(R.drawable.btn_start)
            btnStartStop.setTextColor(0xFF04231A.toInt())
        }
    }

    private fun renderIdle() {
        dial.elapsedMs = 0L
        tvDigital.text = getString(R.string.digital_zero)
        tvWindow.text = getString(R.string.window_idle)
        tvServiceState.text = getString(R.string.background_state_idle)
    }

    private fun renderRunning(elapsed: Long) {
        dial.elapsedMs = elapsed
        tvDigital.text = TimeFormat.elapsedHms(elapsed)
        val start = MeasurementSession.startEpochMs
        tvWindow.text = getString(
            R.string.window_running,
            TimeFormat.clock(start),
            TimeFormat.clock(start + elapsed)
        )
    }

    private fun scheduleFrame() {
        if (frameScheduled) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    // ---------------------------------------------------------------- 알림 권한

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        if (store.notificationPrompted) return
        store.notificationPrompted = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    // ---------------------------------------------------------------- 소리

    private fun toggleSound() {
        val enabled = !store.soundEnabled
        store.soundEnabled = enabled
        updateSoundButton(enabled)
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!granted && MeasurementSession.isRunning) {
                toast(getString(R.string.notification_permission_denied))
            }
            return
        }
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
            val labels = ordered.map { if (it.isSamsung) "[삼성] " + it.label else it.label }.toTypedArray()
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
        private const val REQUEST_NOTIFICATIONS = 4103
    }
}
