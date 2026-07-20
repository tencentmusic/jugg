package com.sickworm.intellij.jugg.ide.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
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
import com.sickworm.intellij.jugg.ide.JuggControlPanelHost
import com.sickworm.intellij.jugg.ide.controlpanel.JuggControlPanelModel
import com.sickworm.intellij.jugg.ide.controlpanel.JuggEvent
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Rectangle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

private typealias JuggControlPanelSnapshot = JuggControlPanelModel.Snapshot
private typealias JuggPanelSettings = JuggControlPanelModel.Settings
private typealias JuggEventCategory = JuggEvent.Category
private typealias JuggEventLevel = JuggEvent.Level
private typealias JuggEventSource = JuggEvent.Source
private typealias JuggEventStatus = JuggEvent.Status

/**
 * Project-level Jugg control panel rendered from a shared immutable model snapshot.
 */
class JuggControlPanel(
    private val project: Project,
    model: JuggControlPanelModel,
    private val controller: JuggControlPanelController,
) : JPanel(BorderLayout()), Disposable {

    private val configurationLabel = primaryLabel("").apply { name = "overview.configuration" }
    private val packageLabel = secondaryLabel("").apply { name = "overview.package" }
    private val devicesLabel = secondaryLabel("").apply { name = "overview.devices" }
    private val changedFilesLabel = secondaryLabel("").apply { name = "overview.changedFiles" }
    private val availabilityLabel = JBLabel("", AllIcons.General.InspectionsWarning, SwingConstants.LEFT)
    private val currentTaskLabel = primaryLabel("Idle").apply { name = "overview.currentTask" }
    private val currentTaskDetailLabel = secondaryLabel("No Jugg activity yet")
    private val currentTaskDurationLabel = monoLabel("")
    private val timelinePanel = contentPanel(0)
    private val healthPanel = contentPanel(8)
    private val activityPanel = contentPanel(0)
    private val logContent = ViewportWidthPanel(VerticalLayout(0)).apply {
        name = "logs.events"
        border = JBUI.Borders.empty(9, 10, 20, 10)
    }
    private val settingToggles = mutableMapOf<JuggControlPanelController.Setting, OnOffButton>()
    private var latestSnapshot = JuggControlPanelSnapshot()
    private var modelSubscription: AutoCloseable? = null
    private var selectedLogSource = "deploy"
    private var selectedLogLevel: JuggEventLevel? = null
    private var currentTaskOnly = false
    private var followLogs = true
    private var logScrollPane: JBScrollPane? = null
    private var logSearch: JBTextField? = null
    private var isRenderingSettings = false

    private val tabs = createTabs()

    init {
        border = JBUI.Borders.empty()
        add(tabs, BorderLayout.CENTER)
        bindModel(model)
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
            add(configurationLabel, BorderLayout.CENTER)
        })
        add(verticalGap(7))
        add(contentPanel(5).apply {
            add(JPanel(HorizontalLayout(12)).transparent().apply {
                add(packageLabel)
                add(devicesLabel)
            })
            add(changedFilesLabel)
        })
        add(verticalGap(8))
        add(availabilityLabel)
    }

    private fun currentTaskSection(): JComponent = overviewSection("Current task", "currentTask") {
        add(JPanel(BorderLayout(JBUI.scale(10), 0)).transparent().apply {
            add(contentPanel(0).apply {
                add(currentTaskLabel)
                add(currentTaskDetailLabel)
            }, BorderLayout.CENTER)
            add(currentTaskDurationLabel, BorderLayout.EAST)
        })
    }

    private fun createQuickActions(): JComponent = JPanel(GridLayout(2, 2, JBUI.scale(8), JBUI.scale(8))).transparent().apply {
        name = "quickActions"
        add(actionButton("Full Gradle Build", "Build, install and launch", true) { controller.fullGradleBuild() })
        add(actionButton("Restart App", "Current selected devices") { controller.restartApp() })
        add(actionButton("Clean & Reinstall", "Clears app data") { controller.cleanAndReinstall() })
        add(actionButton("More…", "Maintenance tools") { showMoreMenu(it) })
    }

    private fun lastDeploySection(): JComponent = overviewSection("Last deploy", "lastDeploy") {
        timelinePanel.border = JBUI.Borders.emptyLeft(7)
        add(timelinePanel)
        add(ActionLink("View related logs →") { select(Page.LOGS) })
    }

    private fun timelineRow(event: JuggEvent): JComponent {
        val detail = event.detail?.takeIf(String::isNotEmpty)?.let { "${event.title}  $it" } ?: event.title
        return threeColumnRow(
            JBLabel(eventIcon(event)),
            JBLabel(detail),
            monoLabel(event.durationMillis?.let(::formatDuration).orEmpty()),
            leftWidth = 18,
            gap = 6,
            height = 31,
        )
    }

    private fun projectHealthSection(): JComponent = overviewSection("Project health", "projectHealth") {
        add(healthPanel)
    }

    private fun recentActivitySection(): JComponent = overviewSection("Recent activity", "recentActivity") {
        add(activityPanel)
    }

    private fun activityRow(event: JuggEvent, divider: Boolean): JComponent {
        return threeColumnRow(
            monoLabel(formatTime(event.timestamp)),
            secondaryLabel(event.title),
            JBLabel(event.status.displayName),
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
        val search = JBTextField().apply {
            name = "logs.search"
            emptyText.text = "Search events"
        }
        logSearch = search
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
                addActionListener { selectedLogSource = id; refreshLogs() }
            })
        }
        search.document.addDocumentListener(documentListener(::refreshLogs))
        return JPanel(BorderLayout()).apply {
            add(contentPanel(7).apply {
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                    JBUI.Borders.empty(7, 9),
                )
                add(sources)
                add(logControlRow(search))
            }, BorderLayout.NORTH)
            add(JBScrollPane(logContent).apply {
                logScrollPane = this
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            }, BorderLayout.CENTER)
        }
    }

    private fun logControlRow(search: JBTextField): JComponent = JPanel(BorderLayout(JBUI.scale(7), 0)).transparent().apply {
        add(search, BorderLayout.CENTER)
        add(JPanel(HorizontalLayout(7)).transparent().apply {
            add(logControlButton("All levels ⌄", 83) { button -> cycleLogLevel(button) })
            add(logControlButton("Current task ⌄", 104) { button ->
                currentTaskOnly = !currentTaskOnly
                button.text = if (currentTaskOnly) "Current task ✓" else "Current task ⌄"
                refreshLogs()
            })
            add(logControlButton("Follow", 64) { button ->
                followLogs = !followLogs
                button.text = if (followLogs) "Follow" else "Paused"
                refreshLogs()
            })
        }, BorderLayout.EAST)
    }

    private fun logControlButton(text: String, width: Int, action: (JButton) -> Unit): JButton = JButton(text).apply {
        margin = JBUI.insets(0, 4)
        preferredSize = Dimension(JBUI.scale(width), maxOf(preferredSize.height, JBUI.scale(27)))
        addActionListener { action(this) }
    }

    private fun cycleLogLevel(button: JButton) {
        selectedLogLevel = when (selectedLogLevel) {
            null -> JuggEventLevel.INFO
            JuggEventLevel.INFO -> JuggEventLevel.WARN
            JuggEventLevel.WARN -> JuggEventLevel.ERROR
            JuggEventLevel.ERROR -> null
        }
        button.text = selectedLogLevel?.let { "${it.name.lowercase().replaceFirstChar(Char::uppercase)} ⌄" } ?: "All levels ⌄"
        refreshLogs()
    }

    private fun logRow(event: JuggEvent): JComponent {
        return JPanel(GridBagLayout()).transparent().apply {
            add(fixedWidth(logLabel(formatTimeWithSeconds(event.timestamp)), 76), gridBag(0))
            add(fixedWidth(logLabel(event.level.name), 62), gridBag(1))
            add(fixedWidth(logLabel(event.category.name), 118), gridBag(2))
            add(logLabel(event.detail?.let { "${event.title} · $it" } ?: event.title),
                gridBag(3, weight = 1.0, fill = GridBagConstraints.HORIZONTAL))
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
                settingToggle("Confirm fallback when no files changed", "Ask before running a full Gradle build.", JuggControlPanelController.Setting.CONFIRM_FALLBACK),
                settingToggle("Always restart app after deployment", "Disables hot reload when a restart is safer.", JuggControlPanelController.Setting.ALWAYS_RESTART)),
            settingGroup("Deployment", "deployment",
                settingToggle("Quick deploy", "Skip app startup when direct overlay is available.", JuggControlPanelController.Setting.QUICK_DEPLOY),
                settingToggle("Auto fallback after deploy failure", "Recover with a full Gradle build.", JuggControlPanelController.Setting.AUTO_FALLBACK),
                settingToggle("Embed changes into APK", "Supports Android RemoteViews with a slower deploy.", JuggControlPanelController.Setting.EMBED_APK)),
            settingGroup("Compiler", "compiler",
                settingToggle("Use project Kotlin compiler", "Matches the compiler configured by the project.", JuggControlPanelController.Setting.PROJECT_KOTLIN),
                settingToggle("Backup classpath", "May improve recovery at the cost of extra storage.", JuggControlPanelController.Setting.BACKUP_CLASSPATH)),
            settingGroup("Integrations", "integrations",
                settingAction("Jugg CLI and skills", "Manage the installed Jugg development tools.", "Manage…", controller::installSkills),
                settingAction("Update channel", "Check whether a newer Jugg plugin is available.", "Check now", controller::checkUpdates)),
            settingGroup("Advanced", "advanced",
                settingAction("Reset Jugg cache", "Delete Jugg project caches and reinitialize the project.", "Reset…", controller::resetJuggCache)),
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

    private fun settingToggle(
        label: String,
        help: String,
        setting: JuggControlPanelController.Setting,
    ): JComponent {
        val toggle = OnOffButton().apply {
            onText = ""
            offText = ""
            addActionListener {
                if (!isRenderingSettings) controller.updateSetting(setting, isSelected)
            }
        }
        settingToggles[setting] = toggle
        return settingRow(label, help, toggle)
    }

    private fun settingAction(label: String, help: String, text: String, action: () -> Unit): JComponent {
        return settingRow(label, help, JButton(text).apply {
            val size = preferredSize
            preferredSize = Dimension(maxOf(size.width, JBUI.scale(92)), size.height)
            addActionListener { action() }
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
            add(menuItem("Install Jugg Skills", controller::installSkills))
            add(menuItem("Check for Updates", controller::checkUpdates))
            add(menuItem("Report Issue", controller::reportIssue))
            addSeparator()
            add(menuItem("Reset Jugg Cache…", controller::resetJuggCache))
            show(button, 0, button.height)
        }
    }

    private fun menuItem(text: String, action: () -> Unit): JMenuItem = JMenuItem(text).apply {
        addActionListener { action() }
    }

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

    fun bindModel(model: JuggControlPanelModel) {
        modelSubscription?.close()
        modelSubscription = model.subscribe(::scheduleRender)
    }

    fun selectPage(page: String) {
        select(Page.entries.firstOrNull { it.id == page.lowercase() } ?: Page.OVERVIEW)
    }

    override fun dispose() {
        modelSubscription?.close()
        modelSubscription = null
    }

    private fun scheduleRender(snapshot: JuggControlPanelSnapshot) {
        latestSnapshot = snapshot
        if (SwingUtilities.isEventDispatchThread()) {
            render(snapshot)
        } else {
            SwingUtilities.invokeLater { render(latestSnapshot) }
        }
    }

    private fun render(snapshot: JuggControlPanelSnapshot) {
        renderContext(snapshot)
        renderCurrentTask(snapshot)
        renderTimeline(snapshot)
        renderHealth(snapshot)
        renderActivity(snapshot)
        renderSettings(snapshot.settings)
        refreshLogs()
    }

    private fun renderContext(snapshot: JuggControlPanelSnapshot) {
        val context = snapshot.context
        configurationLabel.text = context.configuration.ifEmpty { "No Jugg run configuration" }
        packageLabel.text = context.packageName.ifEmpty { "Package unavailable" }
        devicesLabel.text = context.devices.joinToString().ifEmpty { "No selected device" }
        changedFilesLabel.text = "${context.changedFileCount} changed ${if (context.changedFileCount == 1) "file" else "files"}"
        if (context.hasBaseline) {
            availabilityLabel.text = "Hot reload baseline is ready"
            availabilityLabel.icon = AllIcons.General.InspectionsOK
        } else {
            availabilityLabel.text = "Full Gradle build required"
            availabilityLabel.icon = AllIcons.General.InspectionsWarning
        }
    }

    private fun renderCurrentTask(snapshot: JuggControlPanelSnapshot) {
        val task = snapshot.currentTask
        if (task == null) {
            currentTaskLabel.text = "Idle"
            currentTaskDetailLabel.text = snapshot.recentEvents.lastOrNull()?.title ?: "No Jugg activity yet"
            currentTaskDurationLabel.text = ""
            return
        }
        currentTaskLabel.text = task.title
        currentTaskDetailLabel.text = task.phase?.displayName ?: task.source.name
        currentTaskDurationLabel.text = formatDuration(System.currentTimeMillis() - task.startedAt)
    }

    private fun renderTimeline(snapshot: JuggControlPanelSnapshot) {
        val taskId = snapshot.currentTask?.taskId ?: snapshot.recentEvents.lastOrNull { it.taskId != null }?.taskId
        val events = snapshot.recentEvents.filter { it.taskId == taskId }.takeLast(6)
        timelinePanel.removeAll()
        if (events.isEmpty()) {
            timelinePanel.add(secondaryLabel("No task events yet"))
        } else {
            events.forEach { timelinePanel.add(timelineRow(it)) }
        }
        refreshPanel(timelinePanel)
    }

    private fun renderHealth(snapshot: JuggControlPanelSnapshot) {
        healthPanel.removeAll()
        if (snapshot.healthItems.isEmpty()) {
            healthPanel.add(statusLabel("Jugg setup is healthy", AllIcons.General.InspectionsOK))
        } else {
            snapshot.healthItems.forEach { item ->
                val icon = if (item.level == JuggEventLevel.INFO) AllIcons.General.Information else AllIcons.General.InspectionsWarning
                healthPanel.add(statusLabel(item.message, icon))
            }
        }
        refreshPanel(healthPanel)
    }

    private fun renderActivity(snapshot: JuggControlPanelSnapshot) {
        activityPanel.removeAll()
        val events = snapshot.recentEvents.takeLast(3).reversed()
        if (events.isEmpty()) {
            activityPanel.add(secondaryLabel("No recent activity"))
        } else {
            events.forEachIndexed { index, event -> activityPanel.add(activityRow(event, index > 0)) }
        }
        refreshPanel(activityPanel)
    }

    private fun renderSettings(settings: JuggPanelSettings) {
        val values = mapOf(
            JuggControlPanelController.Setting.CONFIRM_FALLBACK to settings.confirmFallbackWhenNoFileChanges,
            JuggControlPanelController.Setting.ALWAYS_RESTART to settings.alwaysRestartAppAfterDeployment,
            JuggControlPanelController.Setting.QUICK_DEPLOY to settings.quickDeploy,
            JuggControlPanelController.Setting.AUTO_FALLBACK to settings.autoFallbackAfterDeployFailure,
            JuggControlPanelController.Setting.EMBED_APK to settings.embedChangesIntoApk,
            JuggControlPanelController.Setting.PROJECT_KOTLIN to settings.useProjectKotlinCompiler,
            JuggControlPanelController.Setting.BACKUP_CLASSPATH to settings.backupClasspath,
        )
        isRenderingSettings = true
        values.forEach { (setting, enabled) -> settingToggles[setting]?.isSelected = enabled }
        isRenderingSettings = false
    }

    private fun refreshLogs() {
        val query = logSearch?.text?.trim()?.lowercase().orEmpty()
        val events = latestSnapshot.recentEvents.filter(::matchesSelectedLogSource).filter { event ->
            val matchesLevel = selectedLogLevel == null || event.level == selectedLogLevel
            val matchesTask = !currentTaskOnly || event.taskId == latestSnapshot.currentTask?.taskId
            matchesLevel && matchesTask && (query.isEmpty() || listOf(event.title, event.detail, event.category.name, event.level.name)
                .filterNotNull()
                .any { it.lowercase().contains(query) })
        }
        logContent.removeAll()
        if (events.isEmpty()) {
            logContent.add(secondaryLabel("No matching events").apply { name = "logs.empty" })
        } else {
            events.forEach { logContent.add(logRow(it)) }
        }
        refreshPanel(logContent)
        if (followLogs) {
            SwingUtilities.invokeLater {
                logScrollPane?.verticalScrollBar?.let { it.value = it.maximum }
            }
        }
    }

    private fun matchesSelectedLogSource(event: JuggEvent): Boolean {
        return when (selectedLogSource) {
            "deploy" -> event.category in setOf(JuggEventCategory.COMPILE, JuggEventCategory.DEPLOY, JuggEventCategory.APP)
            "runtime" -> event.source == JuggEventSource.IDE
            "mcp" -> event.source in setOf(JuggEventSource.CLI, JuggEventSource.MCP) ||
                event.category in setOf(JuggEventCategory.CLI, JuggEventCategory.MCP)
            else -> true
        }
    }

    private fun refreshPanel(panel: JComponent) {
        panel.revalidate()
        panel.repaint()
    }

    private fun eventIcon(event: JuggEvent): javax.swing.Icon {
        return when {
            event.status == JuggEventStatus.FAILED -> AllIcons.General.Error
            event.status == JuggEventStatus.WARNING -> AllIcons.General.InspectionsWarning
            event.status == JuggEventStatus.SUCCEEDED -> AllIcons.General.InspectionsOK
            else -> AllIcons.General.Information
        }
    }

    private fun formatDuration(durationMillis: Long): String {
        return if (durationMillis < 1_000) "${durationMillis}ms" else String.format(Locale.US, "%.1fs", durationMillis / 1_000.0)
    }

    private fun formatTime(timestamp: Long): String = TIME_FORMAT.format(Date(timestamp))

    private fun formatTimeWithSeconds(timestamp: Long): String = TIME_WITH_SECONDS_FORMAT.format(Date(timestamp))

    private fun select(page: Page) {
        tabs.selectedIndex = page.ordinal
    }

    private fun JPanel.transparent(): JPanel = apply { isOpaque = false }

    private enum class Page(val id: String) {
        OVERVIEW("overview"),
        LOGS("logs"),
        SETTINGS("settings"),
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
        private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.US)
        private val TIME_WITH_SECONDS_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

        fun open(project: Project) = open(project, Page.OVERVIEW)

        fun openSettings(project: Project) = open(project, Page.SETTINGS)

        private fun open(project: Project, page: Page) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
            if (!select(toolWindow, page)) {
                JuggControlPanelHost.open(project, page.id)
            }
            toolWindow.activate(Runnable { select(toolWindow, page) })
        }

        private fun select(toolWindow: ToolWindow, page: Page): Boolean {
            val panel = toolWindow.contentManager.contents
                .asSequence()
                .map { it.component }
                .mapNotNull(::findPanel)
                .firstOrNull()
                ?: return false
            panel.select(page)
            return true
        }

        private fun findPanel(component: Component): JuggControlPanel? {
            if (component is JuggControlPanel) return component
            if (component !is java.awt.Container) return null
            return component.components.asSequence().mapNotNull(::findPanel).firstOrNull()
        }
    }
}

private val JuggEventStatus.displayName: String
    get() = name.lowercase().replaceFirstChar(Char::uppercase)

private val JuggEvent.Phase.displayName: String
    get() = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
