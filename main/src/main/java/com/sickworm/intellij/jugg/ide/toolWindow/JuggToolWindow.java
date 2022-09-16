// Copyright 2000-2020 JetBrains s.r.o. and other contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package com.sickworm.intellij.jugg.ide.toolWindow;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.messages.AlertMessagesManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.JBColor;
import com.sickworm.intellij.jugg.JuggManager;
import com.sickworm.intellij.jugg.deploy.JuggDeployState;
import com.sickworm.intellij.jugg.ide.JuggSettings;
import com.sickworm.intellij.jugg.deploy.DeployAction;
import com.sickworm.intellij.jugg.logger.JuggLogger;
import com.sickworm.intellij.jugg.project.JuggPathManager;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

public class JuggToolWindow implements JuggStateListener {

  public JPanel myToolWindowContent;
  private JButton deployButton;
  private JTextPane runningLog;
  private JCheckBox deployOnSaveCheckBox;
  private JCheckBox enableDebugLogCheckBox;
  private JCheckBox restartActivityCheckBox;
  private JLabel statusIconLabel;
  private JLabel statusLabel;
  private JTable fileStatusTable;
  private JPanel invisibleActionPanel;
  private JButton resetButton;

  private final Object[] tableColumns = { "File", "Status" };

  private JuggManager juggManager;
  private final JuggPathManager pathManager;

  private final LoggerPrinter loggerPrinter = new LoggerPrinter();

  private final Project project;

  @TestOnly
  public JuggToolWindow() {
    this.juggManager = null;
    this.pathManager = null;
    this.project = null;
  }

  @SuppressWarnings("unused")
  public JuggToolWindow(Project project, ToolWindow toolWindow) {
    this.project = project;

    String projectDir = project.getBasePath();
    loggerPrinter.info("Start Jugg on " + projectDir);
    if (projectDir == null || (!new File(projectDir).exists())) {
      loggerPrinter.warn("Can not get project directory, exit");
      juggManager = null;
      pathManager = null;
      return;
    }

    pathManager = new JuggPathManager(project, new File(projectDir));
    JuggLogger.INSTANCE.register(project, pathManager.getLogDir());
    JuggLogger.INSTANCE.listenProjectLog(project, loggerPrinter);

    juggManager = new JuggManager(project, pathManager, this);
    juggManager.init();

    deployButton.addActionListener(e -> deploy());
    resetButton.addActionListener(e -> reset());

    deployOnSaveCheckBox.setSelected(JuggSettings.INSTANCE.getDeployOnSave());
    deployOnSaveCheckBox.addItemListener(e -> JuggSettings.INSTANCE.setDeployOnSave(e.getStateChange() == ItemEvent.SELECTED));

    enableDebugLogCheckBox.setSelected(JuggSettings.INSTANCE.getLogDebug());
    enableDebugLogCheckBox.addItemListener(e -> JuggSettings.INSTANCE.setLogDebug(e.getStateChange() == ItemEvent.SELECTED));

    restartActivityCheckBox.setSelected(JuggSettings.INSTANCE.getRestartActivity());
    restartActivityCheckBox.addItemListener(e -> JuggSettings.INSTANCE.setRestartActivity(e.getStateChange() == ItemEvent.SELECTED));

    MutableAttributeSet set = new SimpleAttributeSet(runningLog.getParagraphAttributes());
    StyleConstants.setLineSpacing(set, 0.2f);
    runningLog.setParagraphAttributes(set, true);

    runningLog.addMouseListener(new OnRightClickListener() {
      @Override
      public void onRightClick(@NotNull MouseEvent e) {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem menuItem = new JMenuItem("Clear All", AllIcons.Actions.GC);
        menuItem.addActionListener(actionEvent -> {
          if (actionEvent.getID() == ActionEvent.ACTION_PERFORMED) {
            runningLog.setText("");
          }
        });
        popup.add(menuItem);
        popup.show(e.getComponent(), e.getX(), e.getY());
      }
    });

    AnAction action = new DeployAction(juggManager);
    ActionManager.getInstance().registerAction("Jugg Deploy", action);
    DefaultActionGroup actionGroup = new DefaultActionGroup(action);
    ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("JuggToolWindow", actionGroup, false);
    toolbar.setTargetComponent(invisibleActionPanel);
    invisibleActionPanel.add(toolbar.getComponent());

    statusIconLabel.setOpaque(true);

    fileStatusTable.setFocusable(false);

    onFileStatesUpdate(new ArrayList<>());
  }

  private String currentIconRes = null;

  @Override
  public void onDeployStateUpdate(@NotNull JuggDeployState state) {
    String iconRes;
    if (state.isReadyDeploy()) {
      iconRes = "/res/icon_green.png";
    } else if (state.isReadyIncCompile()) {
      iconRes = "/res/icon_yellow.png";
    } else if (state.isReadyRunFullBuild()) {
      iconRes = "/res/icon_orange.png";
    } else {
      iconRes = "/res/icon_red.png";
    }
    if (!iconRes.equals(currentIconRes)) {
      ImageIcon icon = new ImageIcon(Objects.requireNonNull(getClass().getResource(iconRes)));
      Image image = icon.getImage(); // transform it
      Image newImg = image.getScaledInstance(8, 8,  java.awt.Image.SCALE_SMOOTH);
      ImageIcon newImgIcon = new ImageIcon(newImg);
      statusIconLabel.setIcon(newImgIcon);
      currentIconRes = iconRes;
    }

    if (state.isGradleBuilding()) {
      deployButton.setEnabled(false);
      resetButton.setEnabled(false);
    } else {
      deployButton.setEnabled(true);
      resetButton.setEnabled(true);
      deployButton.setText(state.getDeployButtonText());
    }

    statusLabel.setText(state.getMsg());
  }

  private final List<ChangedFileInfo> tableData = new ArrayList<>();

  @Override
  public void onFileStatesUpdate(@NotNull List<ChangedFileInfo> infos) {
    for (ChangedFileInfo info : infos) {
      int insertIndex = -1;
      for (int i = 0; i < tableData.size(); i++) {
        ChangedFileInfo curInfo = tableData.get(i);
        if (curInfo.getFile().getAbsolutePath().equals(info.getFile().getAbsolutePath())) {
          insertIndex = i;
          break;
        }
      }
      if (insertIndex >= 0) {
        tableData.set(insertIndex, info);
      } else {
        tableData.add(info);
      }
    }

    updateFileTable();
  }

  @Override
  public void onDeployed(boolean isInstall, @NotNull List<? extends File> files) {
    if (isInstall) {
      tableData.clear();
    } else {
      Iterator<ChangedFileInfo> iterator = tableData.iterator();
      while (iterator.hasNext()) {
        ChangedFileInfo changedFileInfo = iterator.next();
        boolean isExist = false;
        for (File file: files) {
          if (file.getAbsolutePath().equals(changedFileInfo.getFile().getAbsolutePath())) {
            isExist = true;
            break;
          }
        }
        if (isExist) {
          iterator.remove();
        }
      }
    }

    updateFileTable();
  }

  private void updateFileTable() {
    Object[][] data = new Object[tableData.size()][2];
    for (int i = 0; i < tableData.size(); i++) {
      ChangedFileInfo curInfo = tableData.get(i);
      data[i] = new Object[] { curInfo.getFile().getName(), curInfo.getState().name() };
    }

    FileTableModel tableModel = new FileTableModel(data, tableColumns);
    fileStatusTable.setModel(tableModel);
  }

  public void deploy() {
    juggManager.deployAsync(true);
  }

  public void reset() {
    AlertMessagesManager alertMessagesManager = project.getService(AlertMessagesManager.class);
    boolean reset = alertMessagesManager.showYesNoDialog(
            "Reset Jugg",
            "Are you sure to reset Jugg and delete all history deploy data?",
            "Yes",
            "No",
            WindowManager.getInstance().suggestParentWindow(project),
            null,
            null,
            null
    );
    if (reset) {
      loggerPrinter.info("Resetting Jugg...");
      try {
        FileUtils.deleteDirectory(pathManager.getJuggRootDir());
      } catch (IOException e) {
        loggerPrinter.error("Delete root directory failed", e);
      }
      juggManager.dispose();
      juggManager = new JuggManager(project, pathManager, this);
      juggManager.init();
      loggerPrinter.info("Reset Jugg completed.");
    }
  }

  public JPanel getContent() {
    return myToolWindowContent;
  }

  private void append(String message, Color c) {
    EventQueue.invokeLater(() -> {
      MutableAttributeSet set = new SimpleAttributeSet();

      int len = runningLog.getDocument().getLength();
      runningLog.setCaretPosition(len);

      SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
      Date date = new Date();
      String dateString = sdf.format(date);
      StyleConstants.setForeground(set, JBColor.GRAY.darker());
      runningLog.setCharacterAttributes(set, false);
      runningLog.replaceSelection(dateString + " ");

      StyleConstants.setForeground(set, c);
      runningLog.setCharacterAttributes(set, false);
      runningLog.replaceSelection(message + "\n");
    });
  }

  private class LoggerPrinter extends Logger {

    @Override
    public boolean isDebugEnabled() {
      return JuggSettings.INSTANCE.getLogDebug();
    }

    @Override
    public void debug(String message) {
      if (isDebugEnabled()) {
        append(message, JBColor.GRAY);
      }
    }

    @Override
    public void debug(@Nullable Throwable t) {
      if (isDebugEnabled()) {
        append(toStackTrace(t), JBColor.GRAY);
      }
    }

    @Override
    public void debug(String message, @Nullable Throwable t) {
      if (isDebugEnabled()) {
        append(message + toStackTrace(t), JBColor.GRAY);
      }
    }

    @Override
    public void info(String message) {
      append(message, JBColor.DARK_GRAY);
    }

    @Override
    public void info(String message, @Nullable Throwable t) {
      append(message + toStackTrace(t), JBColor.DARK_GRAY);
    }

    @Override
    public void warn(String message, @Nullable Throwable t) {
      append("WARN: " + message + toStackTrace(t), JBColor.ORANGE);
    }

    @Override
    public void error(String message, @Nullable Throwable t, String @NotNull ... details) {
      append("ERROR: " + message, JBColor.RED);
      for (String detail : details) {
        append(detail, JBColor.RED);
      }
      append(toStackTrace(t).substring(1), JBColor.GRAY);
    }

    @Override
    public void setLevel(@NotNull Level level) {

    }

    private String toStackTrace(Throwable t) {
      if (t == null) {
        return "";
      }
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      t.printStackTrace(pw);
      return "\n" + sw;
    }
  }
}