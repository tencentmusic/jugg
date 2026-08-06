package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.tools.deploy.proto.Deploy
import com.android.tools.deployer.DexComparator as StudioDexComparator
import com.android.tools.deployer.model.DexClass as StudioDexClass
import com.android.tools.deployer.Version
import com.google.common.collect.ImmutableList
import com.sickworm.intellij.jugg.deploy.api.DexClass
import com.sickworm.intellij.jugg.deploy.api.DexComparator
import com.sickworm.intellij.jugg.deploy.api.ILogger
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StandaloneApplyChangesExecutorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `create install session uses matching Quail protocol version`() {
        val executor = StandaloneApplyChangesExecutor()
        val converter = StandaloneDeployApiConverter()
        val session = executor.createInstallSession(
            temporaryFolder.newFolder("installer").path,
            converter.toJuggDevice(mock<IDevice>()),
            mock<ILogger>(),
            { true },
            {},
        )

        assertEquals(Version.hash(), session.installerVersion)
        assertTrue(session.rawInstaller.javaClass.name == "com.android.tools.deployer.AdbInstaller")
    }

    @Test
    fun `standalone changed class conversion keeps nullable dex`() {
        val changedClasses = DexComparator.ChangedClasses(
            newClasses = listOf(DexClass("new.Class", 1L, byteArrayOf(1), null)),
            modifiedClasses = listOf(DexClass("modified.Class", 2L, byteArrayOf(2), null)),
        )

        val converted = StandaloneDeployApiConverter().toStudioChangedClasses(changedClasses)

        assertNull(converted.newClasses.single().dex)
        assertNull(converted.modifiedClasses.single().dex)
    }

    @Test
    fun `standalone device conversion preserves identity and stable fields`() {
        val device = mock<IDevice>()
        whenever(device.serialNumber).thenReturn("serial-1")
        whenever(device.name).thenReturn("Pixel")

        val converter = StandaloneDeployApiConverter()
        val first = converter.toJuggDevice(device)
        val second = converter.toJuggDevice(device)

        assertSame(first, second)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals("serial-1", first.serialNumber)
        assertEquals("Pixel", first.name)
    }

    @Test
    fun `standalone changed class conversion preserves field reinitialization states`() {
        val state = Deploy.ClassDef.FieldReInitState.newBuilder()
            .setName("value")
            .setType("I")
            .setStaticVar(true)
            .setState(Deploy.ClassDef.FieldReInitState.VariableState.CONSTANT)
            .setValue("7")
            .build()
        val rawClass = StudioDexClass("demo.Class", 1L, byteArrayOf(1), null, ImmutableList.of(state))
        val converter = StandaloneDeployApiConverter()

        val owned = converter.toJuggChangedClasses(StudioDexComparator.ChangedClasses(listOf(rawClass), emptyList()))
        val restored = converter.toStudioChangedClasses(owned)

        assertEquals(listOf(state), restored.newClasses.single().variableStates)
    }
}
