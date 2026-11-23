package com.marwabadak.chronolog.core

/**
 * 한 번의 스톱워치 측정 기록.
 *
 * - [index]       : 누적 회차 번호 (#1, #2, ...)
 * - [startEpochMs]: 측정 시작 시각 (UTC epoch millis)
 * - [endEpochMs]  : 측정 종료 시각 (UTC epoch millis)
 * - [elapsedMs]   : 실제 경과 시간 (밀리초)
 */
data class RecordEntry(
    val index: Int,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val elapsedMs: Long
)

/**
 * 측정 기록 직렬화/역직렬화.
 *
 * SharedPreferences 문자열 하나에 모든 기록을 담는 경량 텍스트 형식을 쓴다.
 * 첫 줄은 형식 버전, 이후 한 줄에 한 건씩 `index|start|end|elapsed` 순서로 기록한다.
 * 외부 라이브러리에 의존하지 않으므로 기기와 JVM 테스트에서 동일하게 동작한다.
 */
object RecordCodec {

    const val FORMAT_VERSION = 1

    private const val HEADER = "chronolog-records-v1"

    fun encode(records: List<RecordEntry>): String {
        val sb = StringBuilder(HEADER)
        sb.append('\n')
        sb.append(FORMAT_VERSION).append('\n')
        for (record in records) {
            sb.append(record.index).append('|')
                .append(record.startEpochMs).append('|')
                .append(record.endEpochMs).append('|')
                .append(record.elapsedMs).append('\n')
        }
        return sb.toString()
    }

    fun decode(raw: String?): List<RecordEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        val lines = raw.split('\n')
        if (lines.isEmpty() || lines[0].trim() != HEADER) return emptyList()
        val result = ArrayList<RecordEntry>(lines.size)
        for (line in lines.drop(2)) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split('|')
            if (parts.size != 4) continue
            val index = parts[0].toIntOrNull() ?: continue
            val start = parts[1].toLongOrNull() ?: continue
            val end = parts[2].toLongOrNull() ?: continue
            val elapsed = parts[3].toLongOrNull() ?: continue
            result.add(RecordEntry(index, start, end, elapsed))
        }
        return result
    }

    /** 다음 회차 번호. 기록이 없으면 1. */
    fun nextIndex(records: List<RecordEntry>): Int =
        (records.maxOfOrNull { it.index } ?: 0) + 1
}
