package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.Client
import com.intellij.openapi.project.Project
import org.junit.Before
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito

class AndroidStudioDebuggerAttachStarterTest {

    @Before
    fun resetFakeState() {
        FakeAndroidJavaDebugger.lastInstance = null
        FakeAndroidConnectDebugger.lastProject = null
        FakeAndroidConnectDebugger.lastDebugger = null
        FakeAndroidConnectDebugger.lastClient = null
        FakeAndroidConnectDebugger.lastRunConfiguration = null
        FakeAndroidConnectDebugger.error = null
    }

    @Test
    fun `existing process attach delegates to Android Studio connect debugger flow`() {
        val project = Mockito.mock(Project::class.java)
        val client = Mockito.mock(Client::class.java)

        AndroidStudioDebuggerAttachStarter(
            connectDebuggerClassName = FakeAndroidConnectDebugger::class.java.name,
            javaDebuggerClassName = FakeAndroidJavaDebugger::class.java.name,
        ).attachExistingProcess(project, client)

        assertSame(project, FakeAndroidConnectDebugger.lastProject)
        assertSame(client, FakeAndroidConnectDebugger.lastClient)
        assertSame(FakeAndroidJavaDebugger.lastInstance, FakeAndroidConnectDebugger.lastDebugger)
        assertSame(null, FakeAndroidConnectDebugger.lastRunConfiguration)
    }

    @Test
    fun `connect debugger invocation error is unwrapped`() {
        val expectedError = IllegalStateException("connect debugger failed")
        FakeAndroidConnectDebugger.error = expectedError

        try {
            AndroidStudioDebuggerAttachStarter(
                connectDebuggerClassName = FakeAndroidConnectDebugger::class.java.name,
                javaDebuggerClassName = FakeAndroidJavaDebugger::class.java.name,
            ).attachExistingProcess(Mockito.mock(Project::class.java), Mockito.mock(Client::class.java))
            fail("Expected attach error")
        } catch (error: Throwable) {
            assertSame(expectedError, error)
        }
    }

    class FakeAndroidJavaDebugger {
        companion object {
            var lastInstance: FakeAndroidJavaDebugger? = null
        }

        init {
            lastInstance = this
        }
    }

    class FakeAndroidConnectDebugger {
        companion object {
            var lastProject: Project? = null
            var lastDebugger: FakeAndroidJavaDebugger? = null
            var lastClient: Client? = null
            var lastRunConfiguration: Any? = null
            var error: Throwable? = null

            @JvmStatic
            fun closeOldSessionAndRun(
                project: Project,
                debugger: FakeAndroidJavaDebugger,
                client: Client,
                runConfiguration: Any?,
            ) {
                error?.let { throw it }
                lastProject = project
                lastDebugger = debugger
                lastClient = client
                lastRunConfiguration = runConfiguration
            }
        }
    }
}
