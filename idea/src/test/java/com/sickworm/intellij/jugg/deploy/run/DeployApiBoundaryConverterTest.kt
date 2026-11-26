package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice as StudioDevice
import com.android.tools.deployer.model.Apk as StudioApk
import com.sickworm.intellij.jugg.deploy.api.Apk
import com.sickworm.intellij.jugg.deploy.api.DexClass
import com.sickworm.intellij.jugg.deploy.api.DexComparator
import com.sickworm.intellij.jugg.deploy.api.FieldReInitState
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class DeployApiBoundaryConverterTest {

    @Test
    fun `IDE changed class conversion keeps nullable dex`() {
        val changes = DexComparator.ChangedClasses(
            newClasses = listOf(DexClass("new.Class", 1L, byteArrayOf(1), null)),
            modifiedClasses = listOf(DexClass("modified.Class", 2L, byteArrayOf(2), null)),
        )

        val converted = LegacyDeployApiConverter().toStudioChangedClasses(changes)

        assertNull(converted.newClasses.single().dex)
        assertNull(converted.modifiedClasses.single().dex)
    }

    @Test
    fun `IDE changed class conversion preserves field reinitialization states for every runtime boundary`() {
        val state = FieldReInitState(
            name = "value",
            type = "I",
            staticVar = true,
            state = FieldReInitState.VariableState.CONSTANT,
            value = "7",
        )
        val changes = DexComparator.ChangedClasses(
            newClasses = listOf(DexClass("demo.Class", 1L, byteArrayOf(1), null, listOf(state))),
            modifiedClasses = emptyList(),
        )

        val legacy = LegacyDeployApiConverter().toStudioChangedClasses(changes).newClasses.single().variableStates
        val quail = QuailDeployApiConverter().toStudioChangedClasses(changes).newClasses.single().variableStates

        listOf(legacy.single(), quail.single()).forEach { converted ->
            assertEquals("value", converted.name)
            assertEquals("I", converted.type)
            assertEquals(true, converted.staticVar)
            assertEquals("CONSTANT", converted.state.name)
            assertEquals("7", converted.value)
        }
    }

    @Test
    fun `IDE boundary preserves device identity and stable fields`() {
        val device = mock<StudioDevice>()
        whenever(device.serialNumber).thenReturn("serial-1")
        whenever(device.name).thenReturn("Pixel")

        val legacy = LegacyDeployApiConverter()
        val first = legacy.toJuggDevice(device)
        val second = legacy.toJuggDevice(device)

        assertSame(first, second)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals("serial-1", first.serialNumber)
        assertEquals("Pixel", first.name)
    }

    @Test
    fun `IDE device can cross compatibility converters in one runtime`() {
        val studioDevice = mock<StudioDevice>()
        val device = QuailDeployApiConverter().toJuggDevice(studioDevice)

        assertSame(studioDevice, LegacyDeployApiConverter().toStudioDevice(device))
    }

    @Test
    fun `IDE APK runtime object does not depend on converter instance`() {
        val studioApk = mock<StudioApk>()
        val apk = Apk(
            "base.apk", "checksum", "/base.apk", "demo", emptyList(), emptyList(), emptyList(), emptyMap(),
            runtimeObject = studioApk,
        )

        assertSame(studioApk, LegacyDeployApiConverter().toStudioApk(apk))
        assertSame(studioApk, QuailDeployApiConverter().toStudioApk(apk))
    }

}
