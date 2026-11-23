package com.marwabadak.chronolog.core

/**
 * 스톱워치 엔진.
 *
 * 시간 측정은 주입된 나노초 클럭에만 의존하므로 Android 없이도 단위 테스트할 수 있다.
 * 실제 앱에서는 `SystemClock.elapsedRealtimeNanos()` 를 넘겨
 * 화면이 꺼지거나 기기가 잠들어도 흐른 시간이 정확히 누적되게 한다.
 */
class StopwatchEngine(
    private val nanoClock: () -> Long = { System.nanoTime() }
) {

    private var accumulatedMs: Long = 0L
    private var segmentStartNanos: Long = 0L

    var running: Boolean = false
        private set

    /** 측정 시작 시각 (epoch millis). 측정을 시작하지 않았으면 0. */
    var startEpochMs: Long = 0L
        private set

    /** 현재까지의 경과 시간(밀리초). */
    fun elapsedMs(): Long {
        if (!running) return accumulatedMs
        val delta = (nanoClock() - segmentStartNanos) / 1_000_000L
        return accumulatedMs + if (delta < 0L) 0L else delta
    }

    /**
     * 측정 시작. 이미 측정 중이면 아무 일도 하지 않고 false 를 돌려준다.
     * [nowEpochMs] 는 시작 시각으로 기록할 현재 벽시계 시각.
     */
    fun start(nowEpochMs: Long): Boolean {
        if (running) return false
        segmentStartNanos = nanoClock()
        running = true
        if (accumulatedMs == 0L) {
            startEpochMs = nowEpochMs
        }
        return true
    }

    /** 측정 정지 후 최종 경과 시간을 돌려준다. */
    fun stop(): Long {
        if (running) {
            val delta = (nanoClock() - segmentStartNanos) / 1_000_000L
            accumulatedMs += if (delta < 0L) 0L else delta
            running = false
        }
        return accumulatedMs
    }

    /** 표시 상태만 초기화한다. (누적 기록은 저장소에 남는다) */
    fun reset() {
        accumulatedMs = 0L
        running = false
        segmentStartNanos = 0L
        startEpochMs = 0L
    }

    /** 정지 상태에서 측정 종료 시각을 계산한다. */
    fun endEpochMs(): Long = startEpochMs + accumulatedMs
}
