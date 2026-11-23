package com.marwabadak.chronolog.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopwatchEngineTest {

    private var nowNanos = 0L
    private val engine = StopwatchEngine { nowNanos }

    @Test
    fun startAndElapsed_trackNanoseconds() {
        assertTrue(engine.start(1_000_000L))
        assertEquals(1_000_000L, engine.startEpochMs)
        nowNanos = 1_500_000_000L
        assertEquals(1_500L, engine.elapsedMs())
        assertTrue(engine.running)
    }

    @Test
    fun doubleStart_isIgnored() {
        engine.start(500L)
        nowNanos = 1_000_000_000L
        assertFalse(engine.start(9_999L))
        assertEquals(500L, engine.startEpochMs)
    }

    @Test
    fun stop_freezesElapsedAndResumeAccumulates() {
        engine.start(1_000L)
        nowNanos = 2_000_000_000L
        assertEquals(2_000L, engine.stop())
        assertFalse(engine.running)

        // 정지 중에는 시간이 흘러도 값이 변하지 않는다.
        nowNanos = 9_000_000_000L
        assertEquals(2_000L, engine.elapsedMs())

        engine.start(50_000L)
        nowNanos = 9_250_000_000L
        assertEquals(2_250L, engine.stop())
        // 첫 시작 시각은 유지된다.
        assertEquals(1_000L, engine.startEpochMs)
        assertEquals(3_250L, engine.endEpochMs())
    }

    @Test
    fun reset_clearsEverything() {
        engine.start(1_000L)
        nowNanos = 1_000_000_000L
        engine.stop()
        engine.reset()
        assertEquals(0L, engine.elapsedMs())
        assertFalse(engine.running)
        assertEquals(0L, engine.startEpochMs)
    }
}
