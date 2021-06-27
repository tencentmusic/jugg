// Copyright 2000-2020 JetBrains s.r.o. and other contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package com.sickworm.intellij.aidp.toolWindow;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.JBColor;
import com.sickworm.intellij.aidp.AidpLogger;
import com.sickworm.intellij.aidp.AidpManager;
import org.apache.log4j.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.io.PrintWriter;
import java.io.StringWriter;

public class AidpToolWindow {

  private JButton applyButton;
  private JPanel myToolWindowContent;
  private JTextPane runningLog;
  private JCheckBox deployWhenFileSavedCheckBox;

  private final Project project;

  private final Logger logger;

  private AidpManager aidpManager;

  @SuppressWarnings("unused")
  public AidpToolWindow(Project project, ToolWindow toolWindow) {
    this.project = project;
    this.logger = AidpLogger.INSTANCE.getInstance(project, "#AIDP-AidpToolWindow");

    String projectDir = project.getBasePath();
    logger.info("projectOpened " + project + " " + projectDir);
    if (projectDir == null) {
      logger.warn("can not get project directory, exit");
      return;
    }

    AidpLogger.INSTANCE.listenProjectLog(project, new LoggerPrinter());
    this.aidpManager = new AidpManager(project, projectDir, toolWindow);
    aidpManager.start();

    applyButton.addActionListener(e -> apply());

    deployWhenFileSavedCheckBox.setSelected(aidpManager.getDeployOnSave());
    deployWhenFileSavedCheckBox.addItemListener(e -> aidpManager.setDeployOnSave(e.getStateChange() == ItemEvent.SELECTED));

    MutableAttributeSet set = new SimpleAttributeSet(runningLog.getParagraphAttributes());
    StyleConstants.setLineSpacing(set, 0.2f);
    runningLog.setParagraphAttributes(set, true);
  }

  public void apply() {
    logger.info("onApply");
    AidpManager manager = AidpManager.Companion.getInstance(project);
    if (manager == null) {
      logger.error("apply failed for AidpManager not found");
      return;
    }

    manager.applyAsync();
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
      return false;
    }

    @Override
    public void debug(String message) {
      append("DEBUG: " + message, JBColor.DARK_GRAY);
    }

    @Override
    public void debug(@Nullable Throwable t) {
      append("DEBUG: " + toStackTrace(t), JBColor.DARK_GRAY);
    }

    @Override
    public void debug(String message, @Nullable Throwable t) {
      append("DEBUG: " + message + toStackTrace(t), JBColor.DARK_GRAY);
    }

    @Override
    public void info(String message) {
      append("INFO: " + message, JBColor.DARK_GRAY);
    }

    @Override
    public void info(String message, @Nullable Throwable t) {
      append("INFO: " + message + toStackTrace(t), JBColor.DARK_GRAY);
    }

    @Override
    public void warn(String message, @Nullable Throwable t) {
      append("WARN: " + message + "\n" + t, JBColor.RED);
    }

    @Override
    public void error(String message, @Nullable Throwable t, String @NotNull ... details) {
      append("ERROR: " + message + toStackTrace(t), JBColor.RED);
      for (String detail : details) {
        append(detail, JBColor.DARK_GRAY);
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