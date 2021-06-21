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
import javax.swing.text.AttributeSet;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import java.awt.*;

public class AidpToolWindow {

  private JButton applyButton;
  private JLabel currentDate;
  private JLabel currentTime;
  private JLabel timeZone;
  private JPanel myToolWindowContent;
  private JTextPane runningLog;

  private final Project project;

  @SuppressWarnings("unused")
  public AidpToolWindow(Project project, ToolWindow toolWindow) {
    this.project = project;
    applyButton.addActionListener(e -> apply());

    AidpLogger.INSTANCE.listenProjectLog(project, new LoggerPrinter());
  }

  public void apply() {
    append("apply!!", JBColor.RED);
    AidpManager manager = AidpManager.Companion.getInstance(project);
    if (manager != null) {
      manager.apply();
    }
  }

  public JPanel getContent() {
    return myToolWindowContent;
  }

  private void append(String message, Color c) {
    EventQueue.invokeLater(() -> {
      StyleContext sc = StyleContext.getDefaultStyleContext();
      AttributeSet set = sc.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, c);
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
      append("DEBUG: " + t, JBColor.DARK_GRAY);
    }

    @Override
    public void debug(String message, @Nullable Throwable t) {
      append("DEBUG: " + message + "\n" + t, JBColor.DARK_GRAY);
    }

    @Override
    public void info(String message) {
      append("INFO: " + message, JBColor.DARK_GRAY);
    }

    @Override
    public void info(String message, @Nullable Throwable t) {
      append("INFO: " + message + "\n" + t, JBColor.DARK_GRAY);
    }

    @Override
    public void warn(String message, @Nullable Throwable t) {
      append("WARN: " + message + "\n" + t, JBColor.RED);
    }

    @Override
    public void error(String message, @Nullable Throwable t, String @NotNull ... details) {
      append("ERROR: " + message + "\n" + t, JBColor.RED);
      for (String detail : details) {
        append(detail, JBColor.DARK_GRAY);
      }
    }

    @Override
    public void setLevel(@NotNull Level level) {

    }
  }
}