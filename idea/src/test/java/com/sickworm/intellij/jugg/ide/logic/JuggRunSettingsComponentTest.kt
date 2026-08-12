package com.sickworm.intellij.jugg.ide.logic

import com.intellij.execution.RunManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import com.sickworm.intellij.jugg.ide.JuggRunSettingsComponentWrapper
import com.sickworm.intellij.jugg.ide.JuggControlPanelHost
import com.sickworm.intellij.jugg.ide.bean.SyncMode
import com.sickworm.intellij.jugg.ide.controlpanel.JuggControlPanelModel
import com.sickworm.intellij.jugg.ide.controlpanel.JuggEvent
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.ide.ui.JuggControlPanel
import com.sickworm.intellij.jugg.ide.ui.JuggControlPanelController
import com.sickworm.intellij.jugg.ide.ui.MockJuggControlPanelModel
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.awt.Component
import java.awt.Container
import java.awt.GridLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRootPane
import javax.swing.JTabbedPane
import javax.swing.JTextField

private typealias JuggPanelContext = JuggControlPanelModel.Context
private typealias JuggEventCategory = JuggEvent.Category
private typealias JuggEventLevel = JuggEvent.Level
private typealias JuggEventPhase = JuggEvent.Phase
private typealias JuggEventSource = JuggEvent.Source
private typealias JuggEventStatus = JuggEvent.Status

class JuggRunSettingsComponentTest {

    @Test
    fun `control panel preview should match approved native layout structure`() {
        TestGlobal.init()
        val panel = createPanel()
        val tabs = findComponent<JBTabbedPane>(panel)
        val quickActions = findNamedComponent<JPanel>(panel, "quickActions")
        val sectionNames = descendants(panel)
            .filterIsInstance<JPanel>()
            .mapNotNull { it.name }
            .filter { it.startsWith("section.") }
            .toList()
        val settingGroupNames = descendants(panel)
            .filterIsInstance<JPanel>()
            .mapNotNull { it.name }
            .filter { it.startsWith("settings.group.") }
            .toList()
        val quickActionGroups = quickActions!!.components.map { group ->
            descendants(group).filterIsInstance<JLabel>().first().text to
                    descendants(group).filterIsInstance<ActionLink>().mapNotNull { it.text }.toList()
        }
        val actionTexts = descendants(panel).filterIsInstance<ActionLink>().mapNotNull { it.text }.toSet()

        assertNotNull(tabs)
        assertEquals(listOf("Overview", "Logs", "Settings"), (0 until tabs!!.tabCount).map(tabs::getTitleAt))
        assertEquals(listOf(
            "section.currentTask",
            "section.changedFiles",
            "section.quickActions",
            "section.session",
            "section.recentRuns",
        ), sectionNames)
        assertTrue(quickActions.layout is GridLayout)
        val quickActionsLayout = quickActions.layout as GridLayout
        assertEquals(3, quickActionsLayout.columns)
        assertEquals(3, quickActions.componentCount)
        assertEquals(listOf(
            "Build" to listOf("Fallback to Gradle", "Clear Jugg Build"),
            "Device" to listOf("Restart App", "Clear app data"),
            "Jugg Plugin" to listOf("Report Issue", "Check updates", "Install CLI & Skill"),
        ), quickActionGroups)
        assertEquals(listOf(
            "settings.group.runBehavior",
            "settings.group.deployment",
            "settings.group.compiler",
            "settings.group.integrations",
            "settings.group.advanced",
        ), settingGroupNames)
        val settingCheckboxes = settingGroupNames.sumOf { groupName ->
            descendants(findNamedComponent<JPanel>(panel, groupName)!!).filterIsInstance<JBCheckBox>().count()
        }
        val settingRows = settingGroupNames.flatMap { groupName ->
            descendants(findNamedComponent<JPanel>(panel, groupName)!!).mapNotNull { it.name }.toList()
        }
        assertEquals(8, settingCheckboxes)
        assertTrue(settingRows.containsAll(listOf(
            "Install CLI and agent skills Install the Jugg CLI, agent skills, hooks, and required permissions.",
            "Check Jugg updates Check whether a newer Jugg plugin is available.",
            "Clear Jugg Build Delete Jugg project build data and reinitialize the project.",
        )))
        assertNotNull(findNamedComponent<JComponent>(panel, "logs.source"))
        assertNotNull(findNamedComponent<JComponent>(panel, "logs.level"))
        assertNotNull(findNamedComponent<JBList<*>>(panel, "logs.events"))
        assertNotNull(findNamedComponent<JBList<*>>(panel, "overview.changedFilesList"))
        assertNotNull(findNamedComponent<JBList<*>>(panel, "overview.recentRunsList"))
        assertTrue(actionTexts.containsAll(listOf(
            "Fallback to Gradle", "Clear Jugg Build", "Restart App", "Clear app data",
            "Report Issue", "Check updates", "Install CLI & Skill", "Install…", "Check now", "Clear…",
        )))
        assertTrue("More…" !in actionTexts)
    }

    @Test
    fun `more options should apply and close run configuration before opening settings`() {
        TestGlobal.init()
        val project = mockProject()
        val panel = createPanel(project)
        val tabs = findComponent<JTabbedPane>(panel)!!
        val content = Mockito.mock(Content::class.java)
        val contentManager = Mockito.mock(ContentManager::class.java)
        val toolWindow = Mockito.mock(ToolWindow::class.java)
        val toolWindowManager = Mockito.mock(ToolWindowManager::class.java)
        Mockito.`when`(content.component).thenReturn(panel)
        Mockito.`when`(contentManager.contents).thenReturn(arrayOf(content))
        Mockito.`when`(toolWindow.contentManager).thenReturn(contentManager)
        Mockito.`when`(toolWindowManager.getToolWindow("Jugg Running Pannel")).thenReturn(toolWindow)
        Mockito.doReturn(toolWindowManager).`when`(project).getService(ToolWindowManager::class.java)

        val component = JuggRunSettingsComponent()
        component.initUpload(project)
        var defaultActionInvoked = false
        JRootPane().apply {
            defaultButton = javax.swing.JButton().apply {
                addActionListener { defaultActionInvoked = true }
            }
            contentPane = JPanel().apply { add(component) }
        }
        val link = readPrivateField<ActionLink>(component, "openControlPanelLink")
        link.doClick()
        javax.swing.SwingUtilities.invokeAndWait {}

        assertTrue(defaultActionInvoked)
        assertEquals("More options", link.text)
        assertEquals(2, tabs.selectedIndex)
    }

    @Test
    fun `control panel should edit compat deploy setting`() {
        TestGlobal.init()
        JuggSettings.isEnableCompatibleDeploymentMode = true
        val controller = Mockito.mock(JuggControlPanelController::class.java)
        val model = JuggControlPanelModel().apply {
            updateSettings(JuggControlPanelModel.Settings(compatibleDeployment = true))
        }
        val panel = JuggControlPanel(mockProject(), model, controller)
        javax.swing.SwingUtilities.invokeAndWait {}

        val toggle = descendants(panel)
            .filterIsInstance<JBCheckBox>()
            .first { it.text == "Enable compat deploy" }
        assertTrue(toggle.isSelected)

        toggle.doClick()

        Mockito.verify(controller).updateSetting(
            JuggControlPanelController.Setting.COMPAT_DEPLOY,
            false,
        )
    }

    @Test
    fun `control panel host replaces the hot reload component`() {
        val host = JuggControlPanelHost()
        val first = JPanel()
        val second = JPanel()

        host.setImpl(first)
        host.setImpl(second)

        assertEquals(1, host.componentCount)
        assertSame(second, host.getComponent(0))
    }

    @Test
    fun `control panel renders the latest model snapshot`() {
        TestGlobal.init()
        val model = JuggControlPanelModel()
        val panel = createPanel(model = model)

        model.updateContext(JuggPanelContext(
            configuration = "run demo",
            packageName = "com.sickworm.demo",
            devices = listOf("Pixel 8 API 35"),
            changedFileCount = 3,
            hasBaseline = true,
        ))
        model.record(JuggEvent(
            taskId = "task-1",
            source = JuggEventSource.IDE,
            category = JuggEventCategory.COMPILE,
            phase = JuggEventPhase.COMPILING,
            status = JuggEventStatus.STARTED,
            level = JuggEventLevel.INFO,
            title = "Compiling 3 changed files",
            timestamp = 1L,
        ))
        javax.swing.SwingUtilities.invokeAndWait {}

        assertEquals(null, findNamedComponent<JLabel>(panel, "overview.configuration"))
        assertEquals(null, findNamedComponent<JLabel>(panel, "overview.package"))
        assertEquals(null, findNamedComponent<JLabel>(panel, "overview.devices"))
        assertEquals("Compiling", findNamedComponent<JLabel>(panel, "overview.currentTask")?.text)
    }

    @Test
    fun `recent runs should show successful deploy result and total duration`() {
        TestGlobal.init()
        val model = JuggControlPanelModel()
        val panel = createPanel(model = model)
        val event = JuggEvent(
            taskId = "task-1",
            source = JuggEventSource.IDE,
            category = JuggEventCategory.COMPILE,
            phase = JuggEventPhase.COMPILING,
            status = JuggEventStatus.STARTED,
            level = JuggEventLevel.INFO,
            title = "Compiling",
            timestamp = 1_000L,
            compileMode = JuggEvent.CompileMode.INCREMENTAL,
        )
        model.record(event)
        model.record(event.copy(
            status = JuggEventStatus.SUCCEEDED, title = "Compiled",
            timestamp = 2_000L,
            durationMillis = 2_400L,
        ))
        model.record(event.copy(
            category = JuggEventCategory.DEPLOY,
            phase = JuggEventPhase.DEPLOYING,
            status = JuggEventStatus.SUCCEEDED,
            title = "Deployed",
            timestamp = 3_000L,
            durationMillis = 250L,
        ))
        model.record(event.copy(
            category = JuggEventCategory.DEPLOY,
            phase = JuggEventPhase.COMPLETED,
            status = JuggEventStatus.SUCCEEDED,
            title = "Completed",
            timestamp = 4_000L,
            durationMillis = 3_000L,
            deployType = JuggDeployData.DeployType.HOT_RELOAD,
            isTaskTerminal = true,
        ))
        javax.swing.SwingUtilities.invokeAndWait {}

        val text = renderRecentRunText(panel)

        assertTrue(text.contains("Incremental → Hot reload"))
        assertTrue(text.contains("3.0s"))
        assertTrue(!text.contains("0.3s"))
        assertTrue(!text.contains("2.4s"))
        assertTrue(!text.contains("ms"))
    }

    @Test
    fun `recent runs should always show terminal result and total duration`() {
        TestGlobal.init()

        assertRecentRunText(
            terminalCategory = JuggEventCategory.COMPILE,
            terminalStatus = JuggEventStatus.SUCCEEDED,
            expectedResult = "Compile only",
        )
        assertRecentRunText(
            terminalCategory = JuggEventCategory.COMPILE,
            terminalStatus = JuggEventStatus.FAILED,
            expectedResult = "Compile failed",
        )
        assertRecentRunText(
            terminalCategory = JuggEventCategory.DEPLOY,
            terminalStatus = JuggEventStatus.FAILED,
            expectedResult = "Deploy failed",
        )
        assertRecentRunText(
            terminalCategory = JuggEventCategory.DEPLOY,
            terminalStatus = JuggEventStatus.FAILED,
            detail = "No device found. Stop deploying.",
            expectedResult = "No device",
        )
    }

    @Test
    fun `mock model uses the same rendering path and can switch back to real data`() {
        TestGlobal.init()
        val realModel = JuggControlPanelModel().apply {
            updateContext(JuggPanelContext(configuration = "real configuration"))
        }
        val panel = createPanel(model = realModel)
        val mockModel = MockJuggControlPanelModel().apply {
            load(MockJuggControlPanelModel.Scenario.RUNNING)
        }

        panel.bindModel(mockModel.model)
        javax.swing.SwingUtilities.invokeAndWait {}
        assertEquals("Compiling", findNamedComponent<JLabel>(panel, "overview.currentTask")?.text)

        panel.bindModel(realModel)
        javax.swing.SwingUtilities.invokeAndWait {}
        assertEquals("Full Gradle build required", findNamedComponent<JLabel>(panel, "overview.currentTask")?.text)
    }

    @Test
    fun `single column rows should stay left aligned`() {
        val component = JuggRunSettingsComponent()
        val androidTestCheckBox = readPrivateField<JCheckBox>(component, "enableAndroidTestCheckBox")
        val rowPanel = invokeCreatePairPanel(component, androidTestCheckBox, null)

        assertEquals(Component.LEFT_ALIGNMENT, rowPanel.alignmentX, 0.0f)
    }

    @Test
    fun `settings editor layers should advertise left alignment`() {
        val component = JuggRunSettingsComponent()
        val wrapper = JuggRunSettingsComponentWrapper()
        wrapper.setImpl(component)

        assertEquals(Component.LEFT_ALIGNMENT, wrapper.alignmentX, 0.0f)
        assertEquals(Component.LEFT_ALIGNMENT, component.alignmentX, 0.0f)
    }

    @Test
    fun `settings component should fill wide editor instead of drifting to center`() {
        val component = JuggRunSettingsComponent()
        component.updateUi(JuggRunConfigurationOptions().apply {
            isRemoteCompile = true
            syncMode = SyncMode.RSYNC_SIMPLE.modeName
        }, "jugg:test")
        val wrapper = JuggRunSettingsComponentWrapper()
        wrapper.setImpl(component)

        layoutInWideParent(wrapper, width = 1600, height = 900)

        assertEquals(0, component.x)
        assertEquals(wrapper.width, component.width)
    }

    @Test
    fun `remote exclude patterns should round trip between settings and component`() {
        val component = JuggRunSettingsComponent()
        component.updateUi(JuggRunConfigurationOptions().apply {
            isRemoteCompile = true
            syncMode = SyncMode.RSYNC_SIMPLE.modeName
            remoteSyncExcludePatterns = "app/src/debug/mock/**\n**/*.keystore"
        }, "jugg:test")

        val textField = readPrivateField<JBTextField>(component, "remoteSyncExcludePatternsTextField")
        assertEquals(
            "local.properties; .idea/; *.iml; .git/objects/; .git/modules/; .cxx/",
            textField.text,
        )

        textField.text = "local-temp/; **/*.dat"
        val options = JuggRunConfigurationOptions()
        component.updateJuggRunConfigurationOptions(options)

        assertEquals("local-temp/; **/*.dat", options.remoteSyncExcludePatterns)
        assertTrue(options.isRemoteSyncExcludePatternsCustomized)
    }

    @Test
    fun `customized remote exclude patterns should display configured list and allow clearing`() {
        val component = JuggRunSettingsComponent()
        component.updateUi(JuggRunConfigurationOptions().apply {
            isRemoteCompile = true
            syncMode = SyncMode.RSYNC_SIMPLE.modeName
            remoteSyncExcludePatterns = "local-temp/**"
            isRemoteSyncExcludePatternsCustomized = true
        }, "jugg:test")

        val textField = readPrivateField<JBTextField>(component, "remoteSyncExcludePatternsTextField")
        assertEquals("local-temp/**", textField.text)

        textField.text = ""
        val options = JuggRunConfigurationOptions()
        component.updateJuggRunConfigurationOptions(options)

        assertNull(options.remoteSyncExcludePatterns)
        assertTrue(options.isRemoteSyncExcludePatternsCustomized)
    }

    @Test
    fun `default remote exclude patterns should save as not customized`() {
        val component = JuggRunSettingsComponent()
        component.updateUi(JuggRunConfigurationOptions().apply {
            isRemoteCompile = true
            syncMode = SyncMode.RSYNC_SIMPLE.modeName
            remoteSyncExcludePatterns = "local-temp/**"
            isRemoteSyncExcludePatternsCustomized = true
        }, "jugg:test")

        val textField = readPrivateField<JBTextField>(component, "remoteSyncExcludePatternsTextField")
        textField.text =
            "local.properties; .idea/; *.iml; .git/objects/; .git/modules/; .cxx/"
        val options = JuggRunConfigurationOptions()
        component.updateJuggRunConfigurationOptions(options)

        assertNull(options.remoteSyncExcludePatterns)
        assertFalse(options.isRemoteSyncExcludePatternsCustomized)
    }

    @Test
    fun `remote exclude patterns should use semicolon in tooltip without owning placeholder hint`() {
        val component = JuggRunSettingsComponent()
        val textField = readPrivateField<JBTextField>(component, "remoteSyncExcludePatternsTextField")

        assertTrue(textField.emptyText.text.isNotBlank())
        assertTrue(textField.emptyText.text.contains(";"))
        assertTrue(textField.toolTipText.contains(";"))
    }

    @Test
    fun `remote exclude patterns should explain rsync matching difference from gitignore`() {
        val component = JuggRunSettingsComponent()
        val label = readPrivateField<JLabel>(component, "remoteSyncExcludePatternsLabel")
        val textField = readPrivateField<JBTextField>(component, "remoteSyncExcludePatternsTextField")

        assertEquals("Exclude patterns:", label.text)
        assertTrue(textField.toolTipText.contains("not gitignore"))
        assertTrue(textField.toolTipText.contains("Default patterns"))
        assertTrue(textField.toolTipText.contains("clear"))
        assertTrue(textField.toolTipText.contains("applied as entered"))
        assertTrue(textField.toolTipText.contains("Leading /"))
        assertTrue(textField.toolTipText.contains(".gradle and build are always excluded"))
    }

    @Test
    fun `remote single line fields should not stretch to exclude patterns height`() {
        val component = JuggRunSettingsComponent()
        component.updateUi(JuggRunConfigurationOptions().apply {
            isRemoteCompile = true
            syncMode = SyncMode.IFT.modeName
        }, "jugg:test")

        layoutInWideParent(component, width = 1200, height = component.preferredSize.height)

        val userTextField = readPrivateField<JTextField>(component, "userTextField")
        assertEquals(userTextField.preferredSize.height, userTextField.height)
    }

    @Test
    fun `remote exclude patterns should use single line field height`() {
        val component = JuggRunSettingsComponent()
        component.updateUi(JuggRunConfigurationOptions().apply {
            isRemoteCompile = true
            syncMode = SyncMode.IFT.modeName
        }, "jugg:test")

        layoutInWideParent(component, width = 1200, height = component.preferredSize.height)

        val userTextField = readPrivateField<JTextField>(component, "userTextField")
        val excludePatternsTextField = readPrivateField<JBTextField>(component, "remoteSyncExcludePatternsTextField")
        assertEquals(userTextField.height, excludePatternsTextField.height)
    }

    private fun <T> readPrivateField(target: Any, fieldName: String): T {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(target) as T
    }

    private fun mockProject(): Project {
        val project = Mockito.mock(Project::class.java)
        Mockito.doReturn(Mockito.mock(RunManager::class.java)).`when`(project).getService(RunManager::class.java)
        return project
    }

    private fun createPanel(
        project: Project = mockProject(),
        model: JuggControlPanelModel = JuggControlPanelModel(),
    ): JuggControlPanel {
        val controller = Mockito.mock(JuggControlPanelController::class.java)
        return JuggControlPanel(project, model, controller)
    }

    private fun assertRecentRunText(
        terminalCategory: JuggEventCategory,
        terminalStatus: JuggEventStatus,
        detail: String? = null,
        expectedResult: String,
    ) {
        val model = JuggControlPanelModel()
        val panel = createPanel(model = model)
        val started = JuggEvent(
            taskId = "task",
            source = JuggEventSource.IDE,
            category = JuggEventCategory.COMPILE,
            phase = JuggEventPhase.COMPILING,
            status = JuggEventStatus.STARTED,
            level = JuggEventLevel.INFO,
            title = "Compiling",
            timestamp = 1_000L,
            compileMode = JuggEvent.CompileMode.INCREMENTAL,
        )
        model.record(started)
        model.record(started.copy(
            category = terminalCategory,
            phase = JuggEventPhase.COMPLETED,
            status = terminalStatus,
            level = if (terminalStatus == JuggEventStatus.FAILED) JuggEventLevel.WARN else JuggEventLevel.INFO,
            title = expectedResult,
            detail = detail,
            timestamp = 2_500L,
            durationMillis = 1_500L,
            isTaskTerminal = true,
        ))
        javax.swing.SwingUtilities.invokeAndWait {}

        val text = renderRecentRunText(panel)
        assertTrue(text.contains("Incremental → $expectedResult"))
        assertTrue(text.contains("1.5s"))
    }

    private fun renderRecentRunText(panel: JuggControlPanel): String {
        val list = findNamedComponent<JBList<JuggControlPanelModel.RunSummary>>(panel, "overview.recentRunsList")!!
        val value = list.model.getElementAt(0)
        val renderer = list.cellRenderer.getListCellRendererComponent(list, value, 0, false, false)
                as SimpleColoredComponent
        return renderer.getCharSequence(false).toString()
    }

    private inline fun <reified T : Component> findNamedComponent(component: Component, name: String): T? {
        return descendants(component).filterIsInstance<T>().firstOrNull { it.name == name }
    }

    private inline fun <reified T : Component> findComponent(component: Component): T? {
        return descendants(component).filterIsInstance<T>().firstOrNull()
    }

    private fun descendants(component: Component): Sequence<Component> = sequence {
        yield(component)
        if (component is Container) {
            component.components.forEach { yieldAll(descendants(it)) }
        }
    }

    private fun invokeCreatePairPanel(component: JuggRunSettingsComponent, left: JCheckBox, right: Any?): javax.swing.JPanel {
        val method = component.javaClass.getDeclaredMethod(
            "createPairPanel",
            javax.swing.JComponent::class.java,
            javax.swing.JComponent::class.java,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(component, left, right, 0, false, true, 4, 0) as javax.swing.JPanel
    }

    private fun layoutInWideParent(component: Component, width: Int, height: Int) {
        val parent = JPanel()
        parent.layout = BoxLayout(parent, BoxLayout.Y_AXIS)
        parent.add(component)
        parent.setSize(width, height)
        layoutRecursively(parent)
    }

    private fun layoutRecursively(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach { layoutRecursively(it) }
        }
    }
}
