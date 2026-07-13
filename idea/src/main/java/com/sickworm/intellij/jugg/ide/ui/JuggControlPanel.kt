package com.sickworm.intellij.jugg.ide.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.OnOffButton
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Rectangle
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JToggleButton
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Project-level preview of the Jugg control panel using IntelliJ native components.
 * The current content is intentionally backed by fixed review data and has no business side effects.
 */
class JuggControlPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val tabs = createTabs()

    init {
        border = JBUI.Borders.empty()
        add(tabs, BorderLayout.CENTER)
    }

    private fun createTabs(): JBTabbedPane = JBTabbedPane().apply {
        setTabComponentInsets(null)
        addTab("Overview", createOverview())
        addTab("Logs", createLogs())
        addTab("Settings", createSettings())
    }

    private fun createOverview(): JComponent = scrollPanel("overview.scroll") {
        add(contextSection())
        add(currentTaskSection())
        add(overviewSection("Quick actions", "quickActions") { add(createQuickActions()) })
        add(lastDeploySection())
        add(projectHealthSection())
        add(recentActivitySection())
    }

    private fun contextSection(): JComponent = overviewSection(null, "context") {
        add(JPanel(BorderLayout()).transparent().apply {
            add(primaryLabel(MockData.configuration), BorderLayout.CENTER)
            add(ActionLink("Edit configuration") {}, BorderLayout.EAST)
        })
        add(verticalGap(7))
        add(contentPanel(5).apply {
            add(metadataRow("app · debug", "com.sickworm.demo", "Pixel 8 API 35"))
            add(metadataRow("· 3 changed files"))
        })
        add(verticalGap(8))
        add(statusLabel("Hot reload is available", AllIcons.General.InspectionsOK))
    }

    private fun currentTaskSection(): JComponent = overviewSection("Current task", "currentTask") {
        add(JPanel(BorderLayout(JBUI.scale(10), 0)).transparent().apply {
            add(contentPanel(0).apply {
                add(primaryLabel("Idle"))
                add(secondaryLabel("Last deploy 14:32 · Hot reload"))
            }, BorderLayout.CENTER)
            add(monoLabel("1.8s"), BorderLayout.EAST)
        })
    }

    private fun createQuickActions(): JComponent = JPanel(GridLayout(2, 2, JBUI.scale(8), JBUI.scale(8))).transparent().apply {
        name = "quickActions"
        add(actionButton("Full Gradle Build", "Build, install and launch", true))
        add(actionButton("Restart App", "Pixel 8 API 35"))
        add(actionButton("Clean & Reinstall", "Clears app data"))
        add(actionButton("More…", "Maintenance tools") { showMoreMenu(it) })
    }

    private fun lastDeploySection(): JComponent = overviewSection("Last deploy", "lastDeploy") {
        val timeline = contentPanel(0).apply {
            border = JBUI.Borders.emptyLeft(7)
            MockData.timeline.forEach { item -> add(timelineRow(item)) }
        }
        add(timeline)
        add(ActionLink("View related logs →") { select(Page.LOGS) })
    }

    private fun timelineRow(item: TimelineItem): JComponent {
        val detail = if (item.detail.isEmpty()) item.label else "${item.label}  ${item.detail}"
        return threeColumnRow(
            JBLabel(AllIcons.Actions.Checked),
            JBLabel(detail),
            monoLabel(item.duration),
            leftWidth = 18,
            gap = 6,
            height = 31,
        )
    }

    private fun projectHealthSection(): JComponent = overviewSection("Project health", "projectHealth") {
        add(threeColumnRow(
            JBLabel(AllIcons.General.InspectionsWarning),
            secondaryLabel("Gradle project info may be outdated"),
            ActionLink("Sync") {},
            leftWidth = 16,
            gap = 7,
        ))
        add(verticalGap(9))
        add(threeColumnRow(
            JBLabel(AllIcons.General.InspectionsOK),
            secondaryLabel("Deploy baseline is ready"),
            JPanel().transparent(),
            leftWidth = 16,
            gap = 7,
        ))
    }

    private fun recentActivitySection(): JComponent = overviewSection("Recent activity", "recentActivity") {
        MockData.activities.forEachIndexed { index, item -> add(activityRow(item, index > 0)) }
    }

    private fun activityRow(item: ActivityItem, divider: Boolean): JComponent {
        return threeColumnRow(
            monoLabel(item.time),
            secondaryLabel(item.description),
            JBLabel(item.result),
            leftWidth = 48,
            gap = 8,
        ).apply {
            border = BorderFactory.createCompoundBorder(
                if (divider) BorderFactory.createMatteBorder(JBUI.scale(1), 0, 0, 0, JBColor.border()) else JBUI.Borders.empty(),
                JBUI.Borders.empty(6, 0),
            )
        }
    }

    private fun createLogs(): JComponent {
        var selectedSource = "deploy"
        val content = ViewportWidthPanel(VerticalLayout(0)).apply { border = JBUI.Borders.empty(9, 10, 20, 10) }
        val search = JBTextField().apply {
            name = "logs.search"
            emptyText.text = "Search logs"
        }
        fun refresh() {
            val query = search.text.trim()
            val lines = MockData.logs
                .asSequence()
                .filter { selectedSource in it.sources }
                .filter { query.isEmpty() || it.text.contains(query, ignoreCase = true) }
                .toList()
            content.removeAll()
            lines.forEach { content.add(logRow(it)) }
            content.revalidate()
            content.repaint()
        }
        val sources = JPanel(GridLayout(1, 3)).transparent().apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                JBUI.Borders.empty(2),
            )
        }
        val sourceGroup = ButtonGroup()
        listOf("deploy" to "Deploy", "runtime" to "Runtime", "mcp" to "CLI / MCP").forEachIndexed { index, (id, text) ->
            sources.add(JToggleButton(text).apply {
                name = "logs.source.${if (id == "mcp") "cliMcp" else id}"
                isSelected = index == 0
                sourceGroup.add(this)
                addActionListener { selectedSource = id; refresh() }
            })
        }
        search.document.addDocumentListener(documentListener(::refresh))
        refresh()
        return JPanel(BorderLayout()).apply {
            add(contentPanel(7).apply {
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                    JBUI.Borders.empty(7, 9),
                )
                add(sources)
                add(logControlRow(search))
            }, BorderLayout.NORTH)
            add(JBScrollPane(content).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            }, BorderLayout.CENTER)
        }
    }

    private fun logControlRow(search: JBTextField): JComponent = JPanel(BorderLayout(JBUI.scale(7), 0)).transparent().apply {
        add(search, BorderLayout.CENTER)
        add(JPanel(HorizontalLayout(7)).transparent().apply {
            add(logControlButton("All levels ⌄", 83))
            add(logControlButton("Current task ⌄", 104))
            add(logControlButton("Follow", 64))
            add(JButton("⋮").apply {
                toolTipText = "More log actions"
                margin = JBUI.emptyInsets()
                preferredSize = Dimension(JBUI.scale(27), maxOf(preferredSize.height, JBUI.scale(27)))
            })
        }, BorderLayout.EAST)
    }

    private fun logControlButton(text: String, width: Int): JButton = JButton(text).apply {
        margin = JBUI.insets(0, 4)
        preferredSize = Dimension(JBUI.scale(width), maxOf(preferredSize.height, JBUI.scale(27)))
    }

    private fun logRow(line: LogLine): JComponent {
        return JPanel(GridBagLayout()).transparent().apply {
            add(fixedWidth(logLabel(line.time), 76), gridBag(0))
            add(fixedWidth(logLabel(line.level), 62), gridBag(1))
            add(fixedWidth(logLabel(line.tag), 118), gridBag(2))
            add(logLabel(line.message), gridBag(3, weight = 1.0, fill = GridBagConstraints.HORIZONTAL))
        }
    }

    private fun gridBag(index: Int, weight: Double = 0.0, fill: Int = GridBagConstraints.NONE): GridBagConstraints {
        return GridBagConstraints().apply {
            gridx = index
            weightx = weight
            this.fill = fill
            anchor = GridBagConstraints.WEST
            if (index > 0) insets = JBUI.insetsLeft(8)
        }
    }

    private fun logLabel(text: String): JBLabel = JBLabel(text).apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
    }

    private fun createSettings(): JComponent {
        val groups = listOf(
            settingGroup("Run behavior", "runBehavior",
                settingToggle("Confirm fallback when no files changed", "Ask before running a full Gradle build.", true),
                settingToggle("Always restart app after deployment", "Disables hot reload when a restart is safer.", false)),
            settingGroup("Deployment", "deployment",
                settingToggle("Quick deploy", "Skip app startup when direct overlay is available.", true),
                settingToggle("Auto fallback after deploy failure", "Recover with a full Gradle build.", false),
                settingToggle("Embed changes into APK", "Supports Android RemoteViews with a slower deploy.", false)),
            settingGroup("Compiler", "compiler",
                settingToggle("Use project Kotlin compiler", "Matches the compiler configured by the project.", true),
                settingToggle("Backup classpath", "May improve recovery at the cost of extra storage.", false)),
            settingGroup("Device compatibility", "deviceCompatibility",
                settingAction("Pixel 8 API 35", "Default deploy strategy · emulator-5554", "Automatic")),
            settingGroup("Integrations", "integrations",
                settingAction("Jugg CLI and skills", "CLI 1.8.0 · Codex installed", "Manage…"),
                settingAction("Update channel", "Current plugin version is up to date.", "Check now"),
                settingAction("Custom server URL", "Use the default Jugg service when empty.", "Configure…")),
            settingGroup("Advanced", "advanced",
                settingToggle("Developer mode", "Shows diagnostic and test-only actions.", false),
                settingAction("Reset settings", "Restore Jugg project settings to defaults.", "Reset…")),
        )
        val search = JBTextField().apply {
            name = "settings.search"
            emptyText.text = "Search settings"
        }
        search.document.addDocumentListener(documentListener { filterSettings(groups, search.text) })
        return scrollPanel("settings.scroll", 10) {
            border = JBUI.Borders.empty(10)
            add(search)
            groups.forEach(::add)
        }
    }

    private fun settingGroup(title: String, id: String, vararg rows: JComponent): JPanel {
        return JPanel(BorderLayout()).apply {
            name = "settings.group.$id"
            border = BorderFactory.createLineBorder(JBColor.border())
            putClientProperty(SETTING_SEARCH_TEXT, (listOf(title) + rows.map { it.name.orEmpty() }).joinToString(" ").lowercase())
            add(JBLabel(title).apply {
                font = font.deriveFont(Font.BOLD)
                border = JBUI.Borders.empty(8, 10)
                isOpaque = true
                background = JBColor.namedColor("EditorTabs.inactive.background", UIUtil.getPanelBackground())
            }, BorderLayout.NORTH)
            add(contentPanel(0).apply {
                rows.forEachIndexed { index, row ->
                    if (index > 0) add(separator())
                    add(row)
                }
            }, BorderLayout.CENTER)
        }
    }

    private fun settingToggle(label: String, help: String, selected: Boolean): JComponent {
        return settingRow(label, help, OnOffButton().apply {
            isSelected = selected
            onText = ""
            offText = ""
        })
    }

    private fun settingAction(label: String, help: String, text: String): JComponent {
        return settingRow(label, help, JButton(text).apply {
            val size = preferredSize
            preferredSize = Dimension(maxOf(size.width, JBUI.scale(92)), size.height)
        })
    }

    private fun settingRow(label: String, help: String, control: JComponent): JComponent {
        val text = contentPanel(2).apply {
            add(JBLabel(label))
            add(secondaryLabel(help))
        }
        return JPanel(BorderLayout(JBUI.scale(12), 0)).transparent().apply {
            name = "$label $help"
            border = JBUI.Borders.empty(9, 10)
            add(text, BorderLayout.CENTER)
            add(control, BorderLayout.EAST)
        }
    }

    private fun overviewSection(title: String?, id: String, content: JPanel.() -> Unit): JComponent {
        return contentPanel(0).apply {
            name = "section.$id"
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, JBUI.scale(1), 0, JBColor.border()),
                JBUI.Borders.empty(12),
            )
            if (title != null) {
                add(eyebrow(title))
                add(verticalGap(8))
            }
            content()
        }
    }

    private fun scrollPanel(name: String, gap: Int = 0, content: ViewportWidthPanel.() -> Unit): JComponent {
        val panel = ViewportWidthPanel(VerticalLayout(gap)).apply(content)
        return JBScrollPane(panel).apply {
            this.name = name
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
    }

    private fun contentPanel(gap: Int): JPanel = JPanel(VerticalLayout(gap)).transparent()

    private fun metadataRow(vararg values: String): JComponent {
        return JPanel(HorizontalLayout(12)).transparent().apply {
            values.forEach { add(secondaryLabel(it)) }
        }
    }

    private fun fixedWidth(component: JComponent, width: Int): JComponent {
        return JPanel(BorderLayout()).transparent().apply {
            preferredSize = Dimension(JBUI.scale(width), component.preferredSize.height)
            add(component, BorderLayout.CENTER)
        }
    }

    private fun threeColumnRow(
        left: JComponent,
        center: JComponent,
        right: JComponent,
        leftWidth: Int,
        gap: Int,
        height: Int? = null,
    ): JPanel {
        return JPanel(BorderLayout(JBUI.scale(gap), 0)).transparent().apply {
            add(JPanel(BorderLayout()).transparent().apply {
                preferredSize = Dimension(JBUI.scale(leftWidth), left.preferredSize.height)
                add(left, BorderLayout.CENTER)
            }, BorderLayout.WEST)
            add(center, BorderLayout.CENTER)
            add(right, BorderLayout.EAST)
            height?.let { preferredSize = Dimension(preferredSize.width, JBUI.scale(it)) }
        }
    }

    private fun primaryLabel(text: String): JLabel = JBLabel(text).apply {
        font = font.deriveFont(Font.BOLD, font.size2D + 1f)
    }

    private fun secondaryLabel(text: String): JBLabel = JBLabel(text).apply {
        foreground = UIUtil.getContextHelpForeground()
    }

    private fun monoLabel(text: String): JBLabel = JBLabel(text).apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        foreground = UIUtil.getContextHelpForeground()
    }

    private fun eyebrow(text: String): JLabel = JBLabel(text.uppercase()).apply {
        foreground = UIUtil.getContextHelpForeground()
        font = font.deriveFont(Font.BOLD, font.size2D - 1f)
    }

    private fun statusLabel(text: String, icon: javax.swing.Icon): JComponent {
        return JBLabel(text, icon, SwingConstants.LEFT).apply {
            iconTextGap = JBUI.scale(7)
        }
    }

    private fun actionButton(
        text: String,
        help: String,
        primary: Boolean = false,
        action: (JButton) -> Unit = {},
    ): JButton {
        return JButton("<html><b>$text</b><br><small>$help</small></html>").apply {
            horizontalAlignment = JButton.LEFT
            margin = JBUI.insets(7, 9)
            val size = preferredSize
            preferredSize = Dimension(size.width, maxOf(size.height, JBUI.scale(48)))
            if (primary) putClientProperty("JButton.buttonType", "default")
            addActionListener { action(this) }
        }
    }

    private fun showMoreMenu(button: JButton) {
        JPopupMenu().apply {
            listOf("Gradle Clean", "Reinitialize Jugg", "Open Jugg Directory").forEach { add(mockMenuItem(it)) }
            addSeparator()
            listOf("Install Jugg Skills", "Check for Updates", "Report Issue").forEach { add(mockMenuItem(it)) }
            addSeparator()
            add(mockMenuItem("Reset Jugg Cache…"))
            show(button, 0, button.height)
        }
    }

    private fun mockMenuItem(text: String): JMenuItem = JMenuItem(text)

    private fun separator(): JComponent = JPanel().apply {
        background = JBColor.border()
        preferredSize = Dimension(0, JBUI.scale(1))
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(1))
    }

    private fun verticalGap(size: Int): Component = Box.createVerticalStrut(JBUI.scale(size))

    private fun documentListener(action: () -> Unit): DocumentListener = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = action()
        override fun removeUpdate(e: DocumentEvent) = action()
        override fun changedUpdate(e: DocumentEvent) = action()
    }

    private fun filterSettings(groups: List<JPanel>, keyword: String) {
        val query = keyword.trim().lowercase()
        groups.forEach { group ->
            val text = group.getClientProperty(SETTING_SEARCH_TEXT) as String
            group.isVisible = query.isEmpty() || text.contains(query)
        }
    }

    private fun select(page: Page) {
        tabs.selectedIndex = page.ordinal
    }

    private fun JPanel.transparent(): JPanel = apply { isOpaque = false }

    private enum class Page { OVERVIEW, LOGS, SETTINGS }

    private data class TimelineItem(val label: String, val detail: String, val duration: String)

    private data class ActivityItem(val time: String, val description: String, val result: String)

    private data class LogLine(
        val sources: Set<String>,
        val time: String,
        val level: String,
        val tag: String,
        val message: String,
    ) {
        val text: String = "$time $level $tag $message"
    }

    private object MockData {
        const val configuration = "run Jugg"

        val timeline = listOf(
            TimelineItem("Detect changes", "3 files", "120ms"),
            TimelineItem("Compile", "2 Kotlin · 1 resource", "860ms"),
            TimelineItem("Deploy", "Code swap", "540ms"),
            TimelineItem("Resume app", "", "210ms"),
        )

        val activities = listOf(
            ActivityItem("14:32", "Deploy · Hot reload", "Success"),
            ActivityItem("14:28", "CLI · restart", "Success"),
            ActivityItem("14:20", "Gradle build", "Failed"),
        )

        val logs = listOf(
            log(setOf("deploy", "runtime"), "14:32:08.012", "INFO", "JuggRunningTask", "Jugg compile started"),
            log(setOf("deploy", "runtime"), "14:32:08.119", "DEBUG", "FileChangesHandler", "3 changed files detected"),
            log(setOf("deploy", "runtime"), "14:32:08.983", "INFO", "JuggCompiler", "Incremental compile finished · 860ms"),
            log(setOf("deploy", "runtime"), "14:32:09.021", "DEBUG", "JuggDeployerHelper", "deploy start · device=Pixel_8_API_35"),
            log(setOf("deploy", "runtime"), "14:32:09.553", "INFO", "JuggDeployer", "Code swap applied successfully"),
            log(setOf("deploy", "runtime"), "14:32:09.771", "INFO", "JuggRunningTask", "Deploy completed · total 1.8s"),
            log(setOf("mcp"), "14:28:11.002", "DEBUG", "McpToolInvoker", "[MCP][TOOL] restart · CLI · success · 420ms"),
            log(setOf("runtime"), "14:20:41.241", "WARN", "GradleCompile", "Compile project failed, check build output"),
        )

        private fun log(sources: Set<String>, time: String, level: String, tag: String, message: String): LogLine {
            return LogLine(sources, time, level, tag, message)
        }
    }

    private class ViewportWidthPanel(layout: java.awt.LayoutManager) : JPanel(layout), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)

        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int {
            return if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width
        }

        override fun getScrollableTracksViewportWidth(): Boolean = true

        override fun getScrollableTracksViewportHeight(): Boolean = false
    }

    companion object {
        const val TOOL_WINDOW_ID = "Jugg Running Pannel"
        private const val SETTING_SEARCH_TEXT = "JuggControlPanel.settingSearchText"

        fun open(project: Project) = open(project, Page.OVERVIEW)

        fun openSettings(project: Project) = open(project, Page.SETTINGS)

        private fun open(project: Project, page: Page) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
            select(toolWindow, page)
            toolWindow.activate(Runnable { select(toolWindow, page) })
        }

        private fun select(toolWindow: ToolWindow, page: Page) {
            toolWindow.contentManager.contents
                .asSequence()
                .map { it.component }
                .filterIsInstance<JuggControlPanel>()
                .firstOrNull()
                ?.select(page)
        }
    }
}
