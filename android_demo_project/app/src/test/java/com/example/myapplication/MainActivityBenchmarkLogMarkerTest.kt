package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityBenchmarkLogMarkerTest {

    @Test
    fun benchmarkMarkerShouldUseDedicatedPrefix() {
        assertEquals("[JUGG_BENCH] MAIN_ACTIVITY_READY", MainActivity.BENCHMARK_LOG_MARKER)
    }
}
