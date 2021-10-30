// Copyright 2000-2020 JetBrains s.r.o. and other contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package com.sickworm.intellij.jugg.ide.toolWindow;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.JBColor;
import com.sickworm.intellij.jugg.JuggManager;
import com.sickworm.intellij.jugg.JuggSettings;
import com.sickworm.intellij.jugg.deploy.DeployAction;
import com.sickworm.intellij.jugg.deploy.DeployState;
import com.sickworm.intellij.jugg.project.JuggLogger;
import org.apache.log4j.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.awt.event.MouseEvent;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Objects;

public class JuggToolWindow implements DeviceStatusListener {

  private JButton deployButton;
  private JPanel myToolWindowContent;
  private JTextPane runningLog;
  private JCheckBox deployOnSaveCheckBox;
  private JCheckBox enableDebugLogCheckBox;
  private JCheckBox restartActivityCheckBox;
  private JLabel statusIconLabel;
  private JLabel statusLabel;
  private JPanel actionPanel;

  private final Project project;

  private final Logger logger;

  private JuggManager juggManager;

  @SuppressWarnings("unused")
  public JuggToolWindow(Project project, ToolWindow toolWindow) {
    this.project = project;
    this.logger = JuggLogger.INSTANCE.getInstance(project, "#Jugg-JuggToolWindow");

    String projectDir = project.getBasePath();
    logger.info("projectOpened " + project + " " + projectDir);
    if (projectDir == null) {
      logger.error("can not get project directory, exit");
      return;
    }

    JuggLogger.INSTANCE.listenProjectLog(project, new LoggerPrinter());
    this.juggManager = new JuggManager(project, projectDir, this);
    juggManager.init();

    deployButton.addActionListener(e -> deploy());

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

    AnAction action = new DeployAction();
    ActionManager.getInstance().registerAction("Jugg Deploy", action);
    DefaultActionGroup actionGroup = new DefaultActionGroup(action);
    ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("JuggToolWindow", actionGroup, false);
    toolbar.setTargetComponent(actionPanel);
    actionPanel.add(toolbar.getComponent());

    statusIconLabel.setOpaque(true);
  }

  @Override
  public void updateStatus(DeployState state) {
    String iconRes;
    if (state.isReadyApply()) {
      iconRes = "/res/icon_green.png";
    } else if (state.isReadyInstall()) {
      iconRes = "/res/icon_yellow.png";
    } else {
      iconRes = "/res/icon_red.png";
    }
    ImageIcon icon = new ImageIcon(Objects.requireNonNull(getClass().getResource(iconRes)));
    Image image = icon.getImage(); // transform it
    Image newImg = image.getScaledInstance(8, 8,  java.awt.Image.SCALE_SMOOTH);
    ImageIcon newImgIcon = new ImageIcon(newImg);
    statusIconLabel.setIcon(newImgIcon);

    statusLabel.setText(state.getMsg());
  }

  public void deploy() {
    logger.info("onDeploy");
    JuggManager manager = JuggManager.Companion.getInstance(project);
    if (manager == null) {
      logger.error("deploy failed for JuggManager not found");
      return;
    }

    manager.deployAsync();
  }

  public JPanel getContent() {
    return myToolWindowContent;
  }

  private void append(String message, Color c) {
    EventQueue.invokeLater(() -> {
      MutableAttributeSet set = new SimpleAttributeSet();
      StyleConstants.setForeground(set, c);

      int len = runningLog.getDocument().getLength();
      runningLog.setCaretPosition(len);
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
      if (isDebugEnabled()) {
        append(toStackTrace(t).substring(1), JBColor.GRAY);
      }
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