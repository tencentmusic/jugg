package com.sickworm.intellij.jugg.deploy.run

import org.junit.Assert.assertEquals
import org.junit.Test

class AsDeployerCompatDispatcherTest {

    @Test
    fun `falls back to lower priority implementation for compat errors`() {
        val priority = FakeCompatImpl("priority", error = NoClassDefFoundError("missing deployer API"))
        val fallback = FakeCompatImpl("fallback")
        val dispatcher = AsDeployerCompatDispatcher(
            implementations = listOf(priority, fallback),
            priorityImplementation = priority,
            nameOf = { it.name },
            logDebug = {},
            logWarn = {},
        )

        val result = dispatcher.invoke { it.call() }

        assertEquals("fallback", result)
    }

    @Test
    fun `does not fall back for business errors`() {
        val priority = FakeCompatImpl("priority", error = IllegalStateException("install failed"))
        val fallback = FakeCompatImpl("fallback")
        val dispatcher = AsDeployerCompatDispatcher(
            implementations = listOf(priority, fallback),
            priorityImplementation = priority,
            nameOf = { it.name },
            logDebug = {},
            logWarn = {},
        )

        try {
            dispatcher.invoke { it.call() }
        } catch (e: IllegalStateException) {
            assertEquals("install failed", e.message)
            assertEquals(0, fallback.callCount)
            return
        }

        throw AssertionError("Expected business error")
    }

    private class FakeCompatImpl(
        val name: String,
        private val error: Throwable? = null,
    ) {
        var callCount = 0
            private set

        fun call(): String {
            callCount++
            error?.let { throw it }
            return name
        }
    }
}
