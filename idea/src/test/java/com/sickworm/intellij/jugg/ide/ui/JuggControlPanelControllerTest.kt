package com.sickworm.intellij.jugg.ide.ui

import com.android.ddmlib.IDevice
import com.intellij.execution.RunManager
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.JuggManager
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.mock.TestGlobal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

class JuggControlPanelControllerTest {
    @get:org.junit.Rule
    val temporaryFolder = org.junit.rules.TemporaryFolder()

    private val scopeJob = Job()
    private val scope = CoroutineScope(scopeJob + Dispatchers.IO)
    private val queryStarted = CountDownLatch(1)
    private val releaseQuery = CountDownLatch(1)
    private val queryCount = AtomicInteger()
    private val targetManager = Mockito.mock(IDeployTargetManager::class.java)
    private lateinit var controller: JuggControlPanelController
    private var oldCompatRecords = ""

    @Before
    fun setUp() {
        TestGlobal.init()
        oldCompatRecords = JuggSettings.deviceCompatRecordJson
        JuggSettings.deviceCompatRecordJson = "[]"
        val project = Mockito.mock(Project::class.java)
        Mockito.doReturn(Mockito.mock(RunManager::class.java)).`when`(project).getService(RunManager::class.java)
        val manager = Mockito.mock(JuggManager::class.java)
        Mockito.`when`(manager.coroutineContext).thenReturn(scope.coroutineContext)
        val device = Mockito.mock(IDevice::class.java)
        Mockito.`when`(device.getProperty("ro.product.manufacturer")).thenReturn("Google")
        Mockito.`when`(device.getProperty("ro.product.model")).thenReturn("Pixel 8")
        Mockito.`when`(targetManager.getDeviceNameList()).thenReturn("Pixel 8")
        Mockito.`when`(targetManager.getConnectedDevices()).thenReturn(listOf(device))
        controller = JuggControlPanelController(
            project = project,
            manager = manager,
            deployTargetManager = targetManager,
            deployHistoryManager = Mockito.mock(IDeployHistoryManager::class.java),
            deployFileManager = Mockito.mock(DeployFileManager::class.java),
            logger = TestGlobal.logger,
        )
    }

    @After
    fun tearDown() {
        releaseQuery.countDown()
        awaitDeviceRefresh()
        scope.cancel()
        SwingUtilities.invokeAndWait {}
        JuggSettings.deviceCompatRecordJson = oldCompatRecords
    }

    @Test
    fun `file manager preserves checkbox edits across search and refresh until confirmation`() {
        val project = Mockito.mock(Project::class.java)
        val dialogController = Mockito.mock(JuggControlPanelController::class.java)
        val root = temporaryFolder.newFolder("main")
        val first = java.io.File(root, "First.kt").apply { writeText("class First") }
        val second = java.io.File(root, "Second.kt").apply { writeText("class Second") }
        val third = java.io.File(root, "Third.kt").apply { writeText("class Third") }
        fun changed(file: java.io.File) = com.sickworm.intellij.jugg.project.ChangedFile(
            com.sickworm.intellij.jugg.compiler.CompileFile.Type.Kotlin, file, root,
            com.sickworm.intellij.jugg.project.data.ModuleInfo.virtualModule)
        val initial = listOf(changed(first), changed(second))
        Mockito.`when`(dialogController.fileChangeCandidates()).thenReturn(initial)
        Mockito.`when`(dialogController.selectedFilePaths()).thenReturn(setOf(first.absolutePath))
        val captured = mutableListOf<Set<String>>()
        Mockito.`when`(dialogController.applyFileSelection(Mockito.anyList(), Mockito.anySet())).thenAnswer {
            captured.add(it.getArgument<Set<String>>(1).toSet())
            true
        }
        Mockito.doAnswer {
            Mockito.`when`(dialogController.fileChangeCandidates()).thenReturn(initial + changed(third))
            Mockito.`when`(dialogController.selectedFilePaths()).thenReturn(setOf(first.absolutePath, third.absolutePath))
            it.getArgument<(Boolean) -> Unit>(0).invoke(true)
            null
        }.`when`(dialogController).refreshFileChanges(org.mockito.kotlin.any())
        second.setLastModified(System.currentTimeMillis() - 305_000)
        third.setLastModified(System.currentTimeMillis() - 3_605_000)
        SwingUtilities.invokeAndWait { withFileIcons { exerciseFileSelection(dialogController, captured) } }
        assertEquals(listOf(setOf(second.absolutePath, third.absolutePath)), captured)
    }

    @Test
    fun `double clicking a file keeps the dialog and checkbox edits open until confirmation`() {
        val controller = Mockito.mock(JuggControlPanelController::class.java)
        val source = temporaryFolder.newFile("Open.kt")
        val changed = com.sickworm.intellij.jugg.project.ChangedFile(
            com.sickworm.intellij.jugg.compiler.CompileFile.Type.Kotlin, source, temporaryFolder.root,
            com.sickworm.intellij.jugg.project.data.ModuleInfo.virtualModule)
        Mockito.`when`(controller.fileChangeCandidates()).thenReturn(listOf(changed))
        Mockito.`when`(controller.selectedFilePaths()).thenReturn(emptySet())
        Mockito.`when`(controller.applyFileSelection(Mockito.anyList(), Mockito.anySet())).thenReturn(true)
        SwingUtilities.invokeAndWait { withFileIcons {
            Mockito.mockConstruction(com.intellij.openapi.fileEditor.OpenFileDescriptor::class.java).use { opened ->
                val dialog = FileChangeManagerDialog(Mockito.mock(Project::class.java), controller)
                try {
                    val search = dialog.preferredFocusedComponent
                    val scroll = (search.parent.parent as java.awt.Container).components
                        .filterIsInstance<javax.swing.JScrollPane>().single()
                    val list = scroll.viewport.view as javax.swing.JList<*>
                    list.selectedIndex = 0
                    clickFileRow(list, 1)
                    clickFileRow(list, 100, 2)
                    assertEquals(1, opened.constructed().size)
                    Mockito.verify(opened.constructed().single()).navigate(true)
                    Mockito.verify(controller, Mockito.never()).applyFileSelection(Mockito.anyList(), Mockito.anySet())
                    assertTrue("Navigation must leave the dialog available", !dialog.isDisposed)
                    confirmDialog(dialog)
                    Mockito.verify(controller).applyFileSelection(listOf(changed), setOf(source.absolutePath))
                } finally {
                    com.intellij.openapi.util.Disposer.dispose(dialog.disposable)
                }
            }
        } }
    }

    private fun exerciseFileSelection(
        controller: JuggControlPanelController,
        captured: List<Set<String>>,
    ) {
        val dialog = FileChangeManagerDialog(Mockito.mock(Project::class.java), controller)
        try {
            val search = dialog.preferredFocusedComponent as com.intellij.ui.components.JBTextField
            val scroll = (search.parent.parent as java.awt.Container).components
                .filterIsInstance<javax.swing.JScrollPane>().single()
            @Suppress("UNCHECKED_CAST")
            val list = scroll.viewport.view as javax.swing.JList<com.sickworm.intellij.jugg.ide.controlpanel.JuggEvent.ChangedFileSnapshot>
            search.text = "main"
            assertEquals(0, list.model.size)
            search.text = "Second"
            assertEquals(1, list.model.size)
            list.selectedIndex = 0
            clickFileRow(list, 100)
            assertFileRow(list, 0, false, "5 min")
            clickFileRow(list, 1)
            assertFileRow(list, 0, true, "5 min")
            search.text = "First"
            list.selectedIndex = 0
            clickFileRow(list, 1)
            search.text = ""
            (search.parent as java.awt.Container).components.filterIsInstance<javax.swing.JButton>().single().doClick()
            assertEquals(3, list.model.size)
            list.addSelectionInterval(0, 2)
            assertEquals(1, list.selectedIndices.size)
            list.background = java.awt.Color.WHITE
            list.selectionBackground = java.awt.Color.BLUE
            assertEquals(java.awt.Color.BLUE.rgb, paintedRowBackground(list, 2))
            assertEquals(java.awt.Color.WHITE.rgb, paintedRowBackground(list, 0))
            assertEquals(java.awt.Color.WHITE.rgb, paintedRowBackground(list, 1))
            assertFileRow(list, 0, false, "0 min")
            assertFileRow(list, 1, true, "5 min")
            assertFileRow(list, 2, true, "1h")
            assertTrue(captured.isEmpty())
            confirmDialog(dialog)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(dialog.disposable)
        }
    }

    private fun clickFileRow(list: javax.swing.JList<*>, x: Int, count: Int = 1) {
        list.setSize(600, 400)
        val cell = list.getCellBounds(list.selectedIndex, list.selectedIndex)
        val event = java.awt.event.MouseEvent(list, java.awt.event.MouseEvent.MOUSE_CLICKED,
            System.currentTimeMillis(), 0, x, cell.y + cell.height / 2, count, false, java.awt.event.MouseEvent.BUTTON1)
        list.mouseListeners.forEach { it.mouseClicked(event) }
    }

    private fun paintedRowBackground(
        list: javax.swing.JList<com.sickworm.intellij.jugg.ide.controlpanel.JuggEvent.ChangedFileSnapshot>,
        index: Int,
    ): Int {
        val row = list.cellRenderer.getListCellRendererComponent(list, list.model.getElementAt(index), index,
            list.isSelectedIndex(index), false) as javax.swing.JPanel
        row.setSize(600, 32)
        row.doLayout()
        val image = java.awt.image.BufferedImage(600, 32, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            row.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image.getRGB(590, 16)
    }

    private fun assertFileRow(
        list: javax.swing.JList<com.sickworm.intellij.jugg.ide.controlpanel.JuggEvent.ChangedFileSnapshot>,
        index: Int,
        checked: Boolean,
        age: String,
    ) {
        val row = list.cellRenderer.getListCellRendererComponent(list, list.model.getElementAt(index), index,
            list.isSelectedIndex(index), false) as javax.swing.JPanel
        assertEquals(checked, row.components.filterIsInstance<javax.swing.JCheckBox>().single().isSelected)
        val label = row.components.filterIsInstance<com.intellij.ui.SimpleColoredComponent>().single()
        assertTrue(label.getCharSequence(false).toString().endsWith(age))
    }

    private fun confirmDialog(dialog: com.intellij.openapi.ui.DialogWrapper) {
        // The headless peer has no root pane; invoke the platform's standard confirmation action.
        val getter = com.intellij.openapi.ui.DialogWrapper::class.java.getDeclaredMethod("getOKAction")
        getter.isAccessible = true
        val action = getter.invoke(dialog) as javax.swing.Action
        action.actionPerformed(java.awt.event.ActionEvent(dialog, 0, "confirm"))
    }

    private fun withFileIcons(action: () -> Unit) {
        val fs = Mockito.mock(com.intellij.openapi.vfs.LocalFileSystem::class.java)
        val file = Mockito.mock(com.intellij.openapi.vfs.VirtualFile::class.java)
        val type = Mockito.mock(com.intellij.openapi.fileTypes.FileType::class.java)
        Mockito.`when`(fs.findFileByPath(org.mockito.kotlin.any())).thenReturn(file)
        Mockito.`when`(file.fileType).thenReturn(type)
        Mockito.`when`(type.icon).thenReturn(javax.swing.ImageIcon())
        Mockito.mockStatic(com.intellij.openapi.vfs.LocalFileSystem::class.java).use { mocked ->
            mocked.`when`<com.intellij.openapi.vfs.LocalFileSystem> {
                com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            }.thenReturn(fs)
            action()
        }
    }

    @Test
    fun `panel refresh stays responsive while selected devices are unavailable`() {
        Mockito.`when`(targetManager.getDeviceNameList()).thenAnswer {
            waitForDevices()
            "Pixel 8"
        }

        assertResponsiveRefresh { controller.refresh() }
    }

    @Test
    fun `settings refresh stays responsive while connected devices are unavailable`() {
        val devices = targetManager.getConnectedDevices()
        Mockito.`when`(targetManager.getConnectedDevices()).thenAnswer {
            waitForDevices()
            devices
        }

        assertResponsiveRefresh { controller.refreshSettings() }
    }

    @Test
    fun `cleared panel ignores a pending device result`() {
        Mockito.`when`(targetManager.getDeviceNameList()).thenAnswer {
            waitForDevices()
            "Pixel 8"
        }
        val uiReturned = CountDownLatch(1)
        SwingUtilities.invokeLater {
            controller.refresh()
            uiReturned.countDown()
        }
        assertTrue(queryStarted.await(5, TimeUnit.SECONDS))
        assertTrue("The UI must not wait for devices", uiReturned.await(2, TimeUnit.SECONDS))
        SwingUtilities.invokeAndWait { controller.clear() }

        releaseQuery.countDown()
        awaitDeviceRefresh()
        SwingUtilities.invokeAndWait {}

        assertTrue(controller.model.snapshot().context.devices.isEmpty())
        assertTrue(controller.model.snapshot().settings.forceCompatDevices.isEmpty())
    }

    @Test
    fun `selected device API failure preserves connected devices and manager scope`() {
        Mockito.`when`(targetManager.getDeviceNameList()).thenThrow(NoClassDefFoundError("Unavailable device API"))

        SwingUtilities.invokeAndWait { controller.refreshSettings() }
        awaitDeviceRefresh()
        SwingUtilities.invokeAndWait {}

        assertTrue(scopeJob.isActive)
        assertEquals(listOf("Google Pixel 8"), controller.model.snapshot().settings.forceCompatDevices.map { it.displayName })
    }

    @Test
    fun `connected device API failure preserves selected devices and manager scope`() {
        Mockito.`when`(targetManager.getConnectedDevices()).thenThrow(NoClassDefFoundError("Unavailable bridge API"))

        SwingUtilities.invokeAndWait { controller.refreshSettings() }
        awaitDeviceRefresh()
        SwingUtilities.invokeAndWait {}

        assertTrue(scopeJob.isActive)
        assertEquals(listOf("Pixel 8"), controller.model.snapshot().context.devices)
        assertTrue(controller.model.snapshot().settings.forceCompatDevices.isEmpty())
    }

    private fun assertResponsiveRefresh(refresh: () -> Unit) {
        val uiReturned = CountDownLatch(1)
        SwingUtilities.invokeLater {
            refresh()
            controller.refreshSettings()
            controller.refreshSettings()
            uiReturned.countDown()
        }
        assertTrue(queryStarted.await(5, TimeUnit.SECONDS))
        assertTrue("The UI must not wait for devices", uiReturned.await(2, TimeUnit.SECONDS))
        assertTrue(controller.model.snapshot().settings.forceCompatDevices.isEmpty())

        releaseQuery.countDown()
        awaitDeviceRefresh()
        SwingUtilities.invokeAndWait {}

        val snapshot = controller.model.snapshot()
        assertEquals(listOf("Pixel 8"), snapshot.context.devices)
        assertEquals(listOf("Google Pixel 8"), snapshot.settings.forceCompatDevices.map { it.displayName })
        assertEquals("Repeated refreshes must share the pending device query", 1, queryCount.get())
    }

    private fun waitForDevices() {
        queryCount.incrementAndGet()
        queryStarted.countDown()
        assertTrue("Device query was not released", releaseQuery.await(10, TimeUnit.SECONDS))
    }

    private fun awaitDeviceRefresh() = runBlocking {
        withTimeout(5_000) { scopeJob.children.toList().joinAll() }
    }
}
