package com.sickworm.intellij.jugg.deploy.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.mock

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

    @Test
    fun `fallback session remains bound to the implementation that created it`() {
        val priority = FakeCompatImpl("priority", error = NoSuchMethodError("priority deployer API"))
        val fallback = FakeCompatImpl("fallback")
        val dispatcher = AsDeployerCompatDispatcher(
            implementations = listOf(priority, fallback),
            priorityImplementation = priority,
            nameOf = { it.name },
            logDebug = {},
            logWarn = {},
        )

        val session = dispatcher.invoke { it.createSession() }

        assertSame(fallback, session.applyChangesExecutor)
        assertEquals(1, fallback.callCount)
    }

    private class FakeCompatImpl(
        val name: String,
        private val error: Throwable? = null,
    ) : IApplyChangesExecutor by mock() {
        var callCount = 0
            private set

        fun call(): String {
            callCount++
            error?.let { throw it }
            return name
        }

        fun createSession(): JuggInstallSession {
            call()
            return JuggInstallSession(this, mock(), name, { true }, {})
        }
    }
}
