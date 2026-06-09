package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.Client
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class JavaDebuggerSessionStarterTest {

    @Test
    fun `existing process attach waits for debugger promise with detach default`() {
        val expectedSession = Any()
        val project = Mockito.mock(Project::class.java)
        val client = Mockito.mock(Client::class.java)
        FakeStartJavaDebuggerSession.promise = FakePromise(expectedSession)

        val session = JavaDebuggerSessionStarter(FakeStartJavaDebuggerSession::class.java.name)
            .attachExistingProcess(project, client)

        assertSame(expectedSession, session)
        assertSame(project, FakeStartJavaDebuggerSession.lastProject)
        assertSame(client, FakeStartJavaDebuggerSession.lastClient)
        assertNull(FakeStartJavaDebuggerSession.lastConsole)
        assertEquals(true, FakeStartJavaDebuggerSession.lastDetachIsDefault)
        assertEquals(15, FakeStartJavaDebuggerSession.promise.lastTimeout)
        assertEquals(TimeUnit.SECONDS, FakeStartJavaDebuggerSession.promise.lastTimeUnit)
    }

    @Test
    fun `debugger promise execution error is unwrapped`() {
        val expectedError = IllegalStateException("debug attach failed")
        FakeStartJavaDebuggerSession.promise = FakePromise(error = ExecutionException(expectedError))

        try {
            JavaDebuggerSessionStarter(FakeStartJavaDebuggerSession::class.java.name)
                .attachExistingProcess(Mockito.mock(Project::class.java), Mockito.mock(Client::class.java))
            fail("Expected attach error")
        } catch (error: Throwable) {
            assertSame(expectedError, error)
        }
    }

    class FakeStartJavaDebuggerSession {
        companion object {
            lateinit var promise: FakePromise
            var lastProject: Project? = null
            var lastClient: Client? = null
            var lastConsole: ConsoleView? = null
            var lastDetachIsDefault: Boolean? = null

            @JvmStatic
            fun startAndroidJavaDebuggerSession(
                project: Project,
                client: Client,
                console: ConsoleView?,
                detachIsDefault: Boolean,
            ): FakePromise {
                lastProject = project
                lastClient = client
                lastConsole = console
                lastDetachIsDefault = detachIsDefault
                return promise
            }
        }
    }

    class FakePromise(
        private val result: Any? = null,
        private val error: Throwable? = null,
    ) {
        var lastTimeout: Int? = null
        var lastTimeUnit: TimeUnit? = null

        fun blockingGet(timeout: Int, unit: TimeUnit): Any? {
            lastTimeout = timeout
            lastTimeUnit = unit
            error?.let { throw it }
            return result
        }
    }
}
