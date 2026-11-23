package com.marwabadak.chronolog.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordCodecTest {

    private val first = RecordEntry(index = 1, startEpochMs = 1_700_000_000_000L, endEpochMs = 1_700_000_026_333L, elapsedMs = 26_333L)
    private val second = RecordEntry(index = 2, startEpochMs = 1_700_000_100_000L, endEpochMs = 1_700_003_700_000L, elapsedMs = 3_600_000L)

    @Test
    fun encodeDecode_roundTrip() {
        val records = listOf(second, first)
        val decoded = RecordCodec.decode(RecordCodec.encode(records))
        assertEquals(records, decoded)
    }

    @Test
    fun decode_emptyOrBrokenInputReturnsEmptyList() {
        assertTrue(RecordCodec.decode(null).isEmpty())
        assertTrue(RecordCodec.decode("").isEmpty())
        assertTrue(RecordCodec.decode("garbage").isEmpty())
        assertTrue(RecordCodec.decode("chronolog-records-v1\n1\nbroken-line\n").isEmpty())
    }

    @Test
    fun decode_skipsInvalidLinesButKeepsValidOnes() {
        val raw = "chronolog-records-v1\n1\n1|100|200|100\n3|300|400|100\nnope|1|2|3\n"
        val decoded = RecordCodec.decode(raw)
        assertEquals(2, decoded.size)
        assertEquals(1, decoded[0].index)
        assertEquals(3, decoded[1].index)
    }

    @Test
    fun nextIndex_countsFromHighestIndex() {
        assertEquals(1, RecordCodec.nextIndex(emptyList()))
        assertEquals(3, RecordCodec.nextIndex(listOf(first, second)))
        assertEquals(8, RecordCodec.nextIndex(listOf(RecordEntry(7, 0L, 1L, 1L))))
    }
}
