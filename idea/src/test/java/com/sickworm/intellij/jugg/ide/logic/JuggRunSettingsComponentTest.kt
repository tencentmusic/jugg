package com.sickworm.intellij.jugg.ide.logic

import com.intellij.execution.RunManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.OnOffButton
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import com.intellij.util.ui.JBUI
import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import com.sickworm.intellij.jugg.ide.JuggRunSettingsComponentWrapper
import com.sickworm.intellij.jugg.ide.JuggControlPanelHost
import com.sickworm.intellij.jugg.ide.bean.SyncMode
import com.sickworm.intellij.jugg.ide.controlpanel.JuggControlPanelModel
import com.sickworm.intellij.jugg.ide.controlpanel.JuggEvent
import com.sickworm.intellij.jugg.ide.ui.JuggControlPanel
import com.sickworm.intellij.jugg.ide.ui.JuggControlPanelController
import com.sickworm.intellij.jugg.ide.ui.MockJuggControlPanelModel
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.awt.Component
import java.awt.Container
import java.awt.GridLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JTextField
import javax.swing.JToggleButton

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
        val logSources = descendants(panel)
            .filterIsInstance<JToggleButton>()
            .mapNotNull { it.name }
            .filter { it.startsWith("logs.source.") }
            .toList()
        val buttonTexts = descendants(panel)
            .filterIsInstance<JButton>()
            .mapNotNull { it.text }
            .toSet()

        assertNotNull(tabs)
        assertEquals(listOf("Overview", "Logs", "Settings"), (0 until tabs!!.tabCount).map(tabs::getTitleAt))
        assertEquals(listOf(
            "section.context",
            "section.currentTask",
            "section.quickActions",
            "section.lastDeploy",
            "section.projectHealth",
            "section.recentActivity",
        ), sectionNames)
        assertTrue(quickActions!!.layout is GridLayout)
        val quickActionsLayout = quickActions.layout as GridLayout
        assertEquals(2, quickActionsLayout.columns)
        assertEquals(JBUI.scale(8), quickActionsLayout.hgap)
        assertEquals(JBUI.scale(8), quickActionsLayout.vgap)
        assertEquals(4, quickActions.componentCount)
        assertEquals(listOf(
            "settings.group.runBehavior",
            "settings.group.deployment",
            "settings.group.compiler",
            "settings.group.integrations",
            "settings.group.advanced",
        ), settingGroupNames)
        assertEquals(7, descendants(panel).filterIsInstance<OnOffButton>().count())
        assertEquals(listOf("logs.source.deploy", "logs.source.runtime", "logs.source.cliMcp"), logSources)
        assertTrue(buttonTexts.containsAll(listOf("All levels ⌄", "Current task ⌄", "Follow")))
        assertTrue("⋮" !in buttonTexts)
    }

    @Test
    fun `more options should open control panel settings`() {
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
        val link = readPrivateField<ActionLink>(component, "openControlPanelLink")
        link.doClick()

        assertEquals("More options", link.text)
        assertEquals(2, tabs.selectedIndex)
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

        assertEquals("run demo", findNamedComponent<JLabel>(panel, "overview.configuration")?.text)
        assertEquals("3 changed files", findNamedComponent<JLabel>(panel, "overview.changedFiles")?.text)
        assertEquals("Pixel 8 API 35", findNamedComponent<JLabel>(panel, "overview.devices")?.text)
        assertEquals("Compiling 3 changed files", findNamedComponent<JLabel>(panel, "overview.currentTask")?.text)
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
        assertEquals("Mock Jugg Run", findNamedComponent<JLabel>(panel, "overview.configuration")?.text)

        panel.bindModel(realModel)
        javax.swing.SwingUtilities.invokeAndWait {}
        assertEquals("real configuration", findNamedComponent<JLabel>(panel, "overview.configuration")?.text)
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
        assertEquals("app/src/debug/mock/**; **/*.keystore", textField.text)

        textField.text = "local-temp/; **/*.dat"
        val options = JuggRunConfigurationOptions()
        component.updateJuggRunConfigurationOptions(options)

        assertEquals("local-temp/; **/*.dat", options.remoteSyncExcludePatterns)
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

        assertEquals("Additional exclude patterns:", label.text)
        assertTrue(textField.toolTipText.contains("not gitignore"))
        assertTrue(textField.toolTipText.contains("*.class"))
        assertTrue(textField.toolTipText.contains("applied as entered"))
        assertTrue(textField.toolTipText.contains("Leading /"))
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
